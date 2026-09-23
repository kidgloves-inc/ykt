package ai.kidgloves.ykt

import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choose
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.assume
import io.kotest.property.checkAll

/**
 * P1: convergence under a lossy channel.
 *
 * A generated program over two or three peers, interpreted by a channel that
 * reorders, duplicates and drops, which is what a forwarding server's channel
 * actually is: updates arrive out of order, twice, or
 * not at all until a later resync. What is asserted, in order of how much it
 * says: an update the receiver is ready for (it holds everything the composer
 * held, and the update lays down contiguously on its clocks) is integrated at
 * once; an update starting above anything the receiver was ever handed is
 * not; a peer that has only ever been handed updates it was ready for reports
 * nothing missing; and at quiescence every peer holds the same text and the
 * same state vector.
 *
 * `has_missing_updates` is weaker on yrs 0.27.4 than its name suggests, in
 * both directions, and each direction was established by probing the core
 * rather than by reading it. Each also has a committed reproducer, so neither
 * claim rests on this comment.
 *
 * It reads FALSE while a peer is behind. A hole in one client's own sequence
 * is recorded as a Skip block in the block store, not as a pending update;
 * only an unresolved dependency (a missing origin, or a delete naming an
 * unknown id) sets the flag. P3's
 * `a_diff_against_a_partial_state_vector_repairs_the_peer` exercises exactly
 * that shape, which is why it asserts the flag in one direction only.
 *
 * It reads TRUE after a peer is fully caught up. A pending record whose
 * `missing` clock the store has already reached is never retried and never
 * cleared, because `apply_update` retries only when that clock is strictly
 * below the store's clock for the client (`transaction.rs`, step 3). Such a
 * peer agrees with its neighbours on text and on state vector and still
 * answers `true`. `a resynced peer reports nothing missing` below is that
 * case, disabled because it fails on this core.
 *
 * Quiescence therefore means a resync, not just a redelivery: yrs 0.27.4 does
 * not always re-integrate a pending update when the one it was waiting for
 * arrives (`reordered same author updates are all integrated`, also disabled),
 * so the channel ends with what the real protocol does, each peer asking the
 * others for the difference against its state vector. The property tolerates
 * a peer still reporting something missing after plain redelivery, which is
 * exactly why it does not catch those two core bugs itself.
 *
 * The mirror of kidgloves-inc/yswift's `lib/src/proptests/p1_lossy_channel.rs`,
 * statement for statement. The one structural difference: Rust's private
 * `Peer { doc, text, client, seen }` is the harness [Peer] here (which owns
 * the document and the root text and closes both), with `client` and `seen`
 * carried beside it on the [Channel], because the harness type is what keeps
 * every Rust object closed.
 */
class P1LossyChannelSpec : StringSpec({

    "peers converge under a lossy channel" {
        checkAll(
            PropTestConfig(iterations = 64),
            distinctClientIds(3),
            Arb.int(2..3),
            Arb.list(channelOp(), 4..39),
        ) { clients, peerCount, ops ->
            Channel(clients.take(peerCount)).use { channel ->
                for (o in ops) {
                    when (o) {
                        is ChannelOp.Edit -> channel.edit(o.peer, o.edit)
                        is ChannelOp.Deliver -> channel.deliver(o.peer, o.index)
                        is ChannelOp.Duplicate -> channel.duplicate(o.peer, o.index)
                        is ChannelOp.Drop -> channel.dropOne(o.peer, o.index)
                        is ChannelOp.Quiesce -> channel.quiesce()
                    }
                }
                channel.quiesce()
            }
        }
    }

    /**
     * yrs 0.26.0 through 0.27.2 placed an insert at index 0 by comparing
     * client ids instead of anchoring it to the start of the type, so a peer
     * with the LARGER client id prepending to text it had already received
     * landed at index 1 on every peer (y-crdt #636, fixed by ed78a05 in
     * 0.27.3). The fix was in `Branch::insert_at`, on the XML path, so a root
     * text on 0.27.4 has to pass this; the shape is cheap and specific enough
     * to be worth stating separately from the channel above.
     *
     * Both id orderings are run for every case, because the bug was a
     * tiebreak: whichever peer holds the larger id is the one that used to
     * land in the wrong place.
     */
    "sequential prepend keeps index 0" {
        checkAll(
            PropTestConfig(iterations = 64),
            distinctClientIds(2),
            Arb.int(2..3),
            clientId(),
            mixedChunk(),
            mixedChunk(),
        ) { pair, peerCount, third, first, second ->
            assume(!pair.contains(third))
            for (order in listOf(listOf(pair[0], pair[1]), listOf(pair[1], pair[0]))) {
                val clients = if (peerCount == 3) order + third else order
                for (atEnd in listOf(false, true)) {
                    val peers = clients.map { Peer(it) }
                    try {
                        // A inserts, and everyone receives it.
                        val fromA = peers[0].transact { tx ->
                            peers[0].text.insert(tx, 0u, first)
                            peers[0].doc.encodeDiffV1(tx, ByteArray(0).asUByteList()).asByteArray()
                        }
                        for (i in 1 until peers.size) peers[i].apply(fromA)

                        // B then edits the text it has already received: sequential,
                        // not concurrent. At index 0, or at the very end.
                        val seenB = peers[1].stateVector()
                        val fromB = peers[1].transact { tx ->
                            val offset = if (atEnd) peers[1].text.length(tx) else 0u
                            peers[1].text.insert(tx, offset, second)
                            peers[1].doc.encodeDiffV1(tx, seenB.asUByteList()).asByteArray()
                        }
                        for (i in peers.indices) if (i != 1) peers[i].apply(fromB)

                        for (i in peers.indices) {
                            val rendered = peers[i].render()
                            if (atEnd) {
                                if (!rendered.endsWith(second)) {
                                    fail(
                                        "peer $i rendered ${debugString(rendered)}, " +
                                            "which does not end with ${debugString(second)}",
                                    )
                                }
                            } else {
                                if (!rendered.startsWith(second)) {
                                    fail(
                                        "peer $i rendered ${debugString(rendered)}, " +
                                            "which does not start with ${debugString(second)}",
                                    )
                                }
                            }
                            renderedCharCount(rendered) shouldBe renderedCharCount(first) + renderedCharCount(second)
                        }
                    } finally {
                        peers.forEach(Peer::close)
                    }
                }
            }
        }
    }

    /**
     * Four sequential updates from ONE author, delivered with the second one
     * last. yrs 0.27.4 does not re-integrate the update that was waiting on it:
     * the document ends holding "ZXZ" at clock 3 with `has_missing_updates()`
     * true, where every one of the four updates has been delivered and the text
     * should be "ZXZZ" at clock 4. Delivering the stuck update a second time
     * repairs it, and so does an ordinary sync round (a diff against the peer's
     * state vector), which is why the channel property above ends with one and
     * does not catch this.
     *
     * DISABLED because it fails on the core this fork pins. It is here as the
     * reproducer, not as a gate. The same sequence loses the update outright on
     * pycrdt 0.14.1 and 0.14.4, where it is not even reported as missing.
     *
     * Rust carries this as
     * `#[ignore = "fails on yrs 0.27.4: a pending update is not re-integrated
     * when its dependency arrives"]`.
     */
    "reordered same author updates are all integrated".config(enabled = false) {
        // Client 2147483647 on root text "prompt": insert(0,"X"), insert(1,"Z"),
        // insert(0,"Z"), insert(3,"Z"), which renders "ZXZZ".
        val updates = listOf(
            unhexBytes("0101ffffffff070004010670726f6d7074015800"),
            unhexBytes("0101ffffffff070184ffffffff0700015a00"),
            unhexBytes("0101ffffffff070244ffffffff0700015a00"),
            unhexBytes("0101ffffffff070384ffffffff0701015a00"),
        )

        for (order in listOf(listOf(0, 2, 3, 1), listOf(0, 3, 2, 1))) {
            Peer(null).use { peer ->
                val observed = peer.transact { tx ->
                    for (index in order) tx.transactionApplyUpdate(updates[index].asUByteList())
                    Observed(
                        peer.text.getString(tx),
                        stateMap(tx),
                        tx.transactionHasMissingUpdates(),
                    )
                }
                withClue("order $order lost an update") { observed.text shouldBe "ZXZZ" }
                observed.state shouldBe mapOf(2_147_483_647uL to 4u)
                observed.missing shouldBe false
            }
        }
    }

    /**
     * A peer that has been handed every update, redelivered what was dropped and
     * then resynced against its neighbour, holding the same text and the same
     * state vector as that neighbour, still answers `has_missing_updates()` with
     * true. It is holding a `PendingUpdate` whose `missing` clock its own store
     * has already reached, which `apply_update` never retries and never clears:
     * step 3 of that function retries only when the missing clock is strictly
     * below `store.blocks.get_clock(client)`, so equality means never.
     *
     * The program below is the shrunk case, replayed through the same channel the
     * property above uses. Two peers, one of them (client 1) doing all the
     * composing, one update dropped in flight and offered again at quiescence.
     * At the end both peers render the same text and report the same state
     * vector, and the observed store on the peer that reports something missing
     * holds:
     *
     * ```text
     * pending: PendingUpdate {
     *     update: { ClientID(1): [(<1#6> len 2, origin-r <1#2>), (<1#8> len 1,
     *               origin-r <1#6>), (<1#9> len 4, origin-l <1#8>, origin-r <1#6>)] },
     *     missing: StateVector({ClientID(1): 14}),
     * }
     * ```
     *
     * DISABLED because it fails on the core this fork pins. It is here as the
     * reproducer, not as a gate. It also matters beyond tidiness: the UDL
     * exposes `has_missing_updates`, and a caller that treats it as "am I
     * behind" would resync forever on a document that is already complete.
     *
     * Rust carries this as
     * `#[ignore = "fails on yrs 0.27.4: a pending record whose missing clock the
     * store has reached is never cleared"]`.
     */
    "a resynced peer reports nothing missing".config(enabled = false) {
        // Peer 0 composes everything; peer 1 loses one update in flight and is
        // offered it again at quiescence.
        fun insert(pos: Int, chunk: String) = ChannelOp.Edit(0, Edit.Insert(pos, chunk))
        fun delete(pos: Int, len: Int) = ChannelOp.Edit(0, Edit.Delete(pos, len))
        val program = listOf(
            insert(0, "aa"),
            insert(0, "aaaa"),
            insert(0, "aa"),
            ChannelOp.Drop(1, 1),
            insert(0, "a"),
            delete(0, 0),
            insert(0, "aaaa"),
            delete(0, 2),
            insert(8, "a"),
        )

        Channel(listOf(1uL, 2_147_483_647uL)).use { channel ->
            for (o in program) {
                when (o) {
                    is ChannelOp.Edit -> channel.edit(o.peer, o.edit)
                    is ChannelOp.Deliver -> channel.deliver(o.peer, o.index)
                    is ChannelOp.Duplicate -> channel.duplicate(o.peer, o.index)
                    is ChannelOp.Drop -> channel.dropOne(o.peer, o.index)
                    is ChannelOp.Quiesce -> channel.quiesce()
                }
            }
            // Redelivery, drain, resync. This asserts convergence of text and state
            // vector, and it passes: the peers do agree.
            channel.quiesce()

            channel.peers.forEachIndexed { i, peer ->
                val view = peer.observe()
                if (view.missing) {
                    fail(
                        "peer $i agrees with the others on ${debugString(view.text)} and " +
                            "${view.state} and still reports a missing update",
                    )
                }
            }
        }
    }
})

/**
 * One instruction of a generated program. Peer and inbox indices are raw
 * draws, taken modulo the live counts when interpreted.
 */
private sealed class ChannelOp {
    /**
     * A peer edits its own copy, which puts one update in every other peer's
     * inbox.
     */
    data class Edit(val peer: Int, val edit: ai.kidgloves.ykt.Edit) : ChannelOp()

    /** A peer takes one update out of its inbox and applies it. */
    data class Deliver(val peer: Int, val index: Int) : ChannelOp()

    /** The channel delivers the same update twice. */
    data class Duplicate(val peer: Int, val index: Int) : ChannelOp()

    /**
     * The channel loses an update. It is remembered, and offered again at the
     * next quiescence: a lost update is late, not gone.
     */
    data class Drop(val peer: Int, val index: Int) : ChannelOp()

    /**
     * Everything settles: what was dropped is offered again, the inboxes are
     * drained, and the peers resync.
     */
    object Quiesce : ChannelOp()
}

private fun channelOp(): Arb<ChannelOp> = Arb.choose(
    4 to Arb.bind(Arb.int(0..2), asciiEdits(1..1)) { peer, edits -> ChannelOp.Edit(peer, edits[0]) },
    6 to Arb.bind(Arb.int(0..2), Arb.int(0..7)) { peer, index -> ChannelOp.Deliver(peer, index) },
    1 to Arb.bind(Arb.int(0..2), Arb.int(0..7)) { peer, index -> ChannelOp.Duplicate(peer, index) },
    2 to Arb.bind(Arb.int(0..2), Arb.int(0..7)) { peer, index -> ChannelOp.Drop(peer, index) },
    1 to Arb.constant(ChannelOp.Quiesce),
)

/**
 * An update in flight, with what its composer HELD when it made it.
 *
 * `deps` is not the composer's state vector. A peer whose own store has a
 * hole reports the hole's start as its clock for that client while still
 * holding, and being able to reference, blocks above it, so its state vector
 * understates what an update it composes can depend on. What is recorded here
 * is every clock range the composer has ever been handed or authored, which
 * is an upper bound on what the update can reference and therefore the honest
 * precondition for "the receiver was ready for this".
 */
private class Envelope(
    val update: ByteArray,
    val deps: Map<ULong, UInt>,
    val author: ULong,
    val span: UpdateSpan,
)

/**
 * Raise every client's clock in `into` to the highest one `span` names, on
 * either side of the insert and delete split.
 */
private fun absorb(into: MutableMap<ULong, UInt>, span: UpdateSpan) {
    for ((client, ranges) in span.inserts.entries + span.deletes.entries) {
        val end = ranges.maxOfOrNull { it.last + 1u } ?: continue
        into[client] = maxOf(into[client] ?: 0u, end)
    }
}

private class Channel(private val clients: List<ULong>) : AutoCloseable {
    val peers: List<Peer> = clients.map { Peer(it) }

    /**
     * The state vector each peer had when it last composed, so its next update
     * is a diff rather than its whole state. On the Rust side this is a field
     * of its private `Peer`; the harness [Peer] is the document handle, so it
     * lives here.
     */
    private val seen: MutableList<ByteArray> = peers.map { it.stateVector() }.toMutableList()

    private val inboxes: List<MutableList<Envelope>> = peers.map { mutableListOf<Envelope>() }
    private val dropped: MutableList<Pair<Int, Envelope>> = mutableListOf()

    /** Everything each peer has actually been handed. */
    private val delivered: List<MutableList<Envelope>> = peers.map { mutableListOf<Envelope>() }

    /**
     * Whether a peer has only ever been handed updates whose dependencies it
     * already held. Such a peer has never had a reason to pend anything.
     */
    private val inOrder: MutableList<Boolean> = peers.map { true }.toMutableList()

    /**
     * The highest clock per client each peer has ever been handed or
     * authored, integrated or not. See [Envelope.deps].
     */
    private val holdings: List<MutableMap<ULong, UInt>> = peers.map { mutableMapOf<ULong, UInt>() }

    fun edit(peerDraw: Int, edit: Edit) {
        val peer = peerDraw % peers.size
        val p = peers[peer]
        val deps = holdings[peer].toMap()
        val update = p.transact { tx ->
            when (val planned = planFor(p.text.getString(tx), edit)) {
                null -> null
                else -> {
                    run(p.text, tx, planned)
                    p.doc.encodeDiffV1(tx, seen[peer].asUByteList()).asByteArray()
                }
            }
        } ?: return
        val author = clients[peer]
        seen[peer] = p.stateVector()
        val envelope = Envelope(update, deps, author, UpdateSpan.decode(update))
        absorb(holdings[peer], envelope.span)
        for (other in peers.indices) {
            if (other != peer) inboxes[other].add(envelope)
        }
    }

    /**
     * The highest clock per client the peer could hold given what it has been
     * handed: its own state vector extended by every delivered update whose
     * blocks join onto it, to a fixpoint. A peer that was never given the
     * bytes for a range cannot integrate anything above that range, whatever
     * it is holding pending.
     */
    private fun reachable(before: Map<ULong, UInt>, delivered: List<Envelope>): Map<ULong, UInt> {
        val reach = before.toMutableMap()
        while (true) {
            var changed = false
            for (envelope in delivered) {
                for ((client, ranges) in envelope.span.inserts) {
                    for (range in ranges) {
                        val clock = reach[client] ?: 0u
                        if (range.first <= clock && range.last + 1u > clock) {
                            reach[client] = range.last + 1u
                            changed = true
                        }
                    }
                }
            }
            if (!changed) return reach
        }
    }

    /** Apply one envelope and check what the receiver did with it. */
    private fun apply(peer: Int, envelope: Envelope) {
        val p = peers[peer]
        val before = p.transact { stateMap(it) }
        p.apply(envelope.update)
        val after = p.transact { stateMap(it) }

        // Ready means two things: the receiver has integrated everything the
        // composer HELD when it composed (not merely everything the composer's
        // state vector admitted to), and the update starts where the
        // receiver's own clocks are, with no hole in any client's sequence.
        val gap = envelope.span.gappedClient(before)
        val reachable = reachable(before, delivered[peer])
        val inOrderNow = dominates(before, envelope.deps) && gap == null
        if (inOrderNow) {
            if (!envelope.span.insertsCoveredBy(after)) {
                fail(
                    "the receiver was ready for this update, so it had to integrate: " +
                        "peer $peer author ${envelope.author} span ${envelope.span} " +
                        "deps ${envelope.deps} before $before after $after",
                )
            }
        } else if (envelope.span.unreachableClient(reachable) != null) {
            if (envelope.span.insertsCoveredBy(after)) {
                fail(
                    "an update starting above anything the receiver was ever handed " +
                        "must not integrate: peer $peer span ${envelope.span} " +
                        "before $before reachable $reachable after $after",
                )
            }
        }

        absorb(holdings[peer], envelope.span)
        delivered[peer].add(envelope)
        inOrder[peer] = inOrder[peer] && inOrderNow

        // A peer that has only ever been handed updates it was ready for has
        // nothing to wait for. This is the one direction of
        // `has_missing_updates` that holds on yrs 0.27.4; see the class note.
        if (inOrder[peer]) {
            if (peers[peer].observe().missing) {
                fail("a peer handed only updates it was ready for reports one missing")
            }
        }
    }

    fun deliver(peerDraw: Int, indexDraw: Int) {
        val peer = peerDraw % peers.size
        if (inboxes[peer].isEmpty()) return
        val index = indexDraw % inboxes[peer].size
        val envelope = inboxes[peer].removeAt(index)
        apply(peer, envelope)
    }

    fun duplicate(peerDraw: Int, indexDraw: Int) {
        val peer = peerDraw % peers.size
        if (inboxes[peer].isEmpty()) return
        val index = indexDraw % inboxes[peer].size
        inboxes[peer].add(inboxes[peer][index])
    }

    fun dropOne(peerDraw: Int, indexDraw: Int) {
        val peer = peerDraw % peers.size
        if (inboxes[peer].isEmpty()) return
        val index = indexDraw % inboxes[peer].size
        dropped.add(peer to inboxes[peer].removeAt(index))
    }

    /** Offer everything again, drain every inbox, then resync. */
    fun quiesce() {
        for ((peer, envelope) in dropped) inboxes[peer].add(envelope)
        dropped.clear()
        for (peer in peers.indices) {
            while (inboxes[peer].isNotEmpty()) {
                apply(peer, inboxes[peer].removeAt(0))
            }
        }

        // Every peer that believes it is up to date must agree with every
        // other such peer. A peer still reporting something missing is behind,
        // and the resync below is what a real client does about it.
        val settled = peers.map { it.observe() }
        for (i in settled.indices) {
            for (j in (i + 1) until settled.size) {
                if (!settled[i].missing && !settled[j].missing) {
                    withClue("two peers holding nothing outstanding disagree") {
                        settled[i].text shouldBe settled[j].text
                    }
                    settled[i].state shouldBe settled[j].state
                }
            }
        }

        // The resync: every peer asks every other for the difference against
        // what it has. Two rounds, which is one more than convergence needs.
        repeat(2) {
            for (receiver in peers.indices) {
                for (sender in peers.indices) {
                    if (sender == receiver) continue
                    val sv = peers[receiver].stateVector()
                    val diff = peers[sender].diffAgainst(sv)
                    peers[receiver].apply(diff)
                }
            }
        }

        val views = peers.map { it.observe() }
        for (i in 1 until views.size) {
            withClue("peers disagree after a resync") { views[i].text shouldBe views[0].text }
            views[i].state shouldBe views[0].state
        }
        // What is deliberately NOT asserted here: that no peer reports
        // anything missing. It is false on yrs 0.27.4, and
        // `a resynced peer reports nothing missing` above is the reproducer,
        // disabled for that reason.
    }

    override fun close() {
        peers.forEach(Peer::close)
    }
}

/** Unicode code points, which is what a Rust `chars().count()` counts. */
private fun renderedCharCount(s: String): Int = s.codePointCount(0, s.length)

/** Rust's `{:?}` on a string, near enough for a failure message. */
private fun debugString(s: String): String = "\"$s\""

/** Hex to bytes, the local `unhex` of the Rust reproducer. */
private fun unhexBytes(s: String): ByteArray =
    ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
