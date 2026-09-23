package ai.kidgloves.ykt

import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choose
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.uLong
import io.kotest.property.assume
import io.kotest.property.checkAll

/**
 * P8: a third writer class behind a forwarding server's client-id gate.
 *
 * The first two writer classes are peers of each other: each mints its own
 * client id and the wire takes its word for it. A writer whose client id the
 * server assigns is not one. The server draws its id, records it against the
 * session, and refuses any update whose struct section names a different
 * author — it admits an update only when every block in its struct section is
 * filed under the client id the server assigned to the sender — so a writer
 * that minted its own id could not fork a document the way the u32 client-id
 * truncation in yrs 0.18's V1 decoder did.
 *
 * The positive is that the gate costs a well-bound session nothing: it never
 * refuses one of its updates, all three peers converge under a lossy
 * schedule, and no peer ends up holding a block under an id nobody issued.
 * The negative is that truncation's shape arriving by a second road — a
 * session whose binding mangled its id — and it says the mangled writer is
 * stopped at the server rather than in the two core peers' documents.
 *
 * Quiescence here is a replay of the server's LOG, not the peer-to-peer diff
 * round P1 settles with, and the difference is this file's whole subject: a
 * session's bytes reach another peer only by passing the gate, so a diff
 * composed by the session and applied directly to a peer would be the test
 * walking around the thing it is testing. The log is every update the server
 * admitted, in the order it admitted them, which is what a peer attaching to
 * the server is replayed — and replaying it is also what repairs the pending
 * update this core does not re-integrate when its dependency arrives late
 * (P1's `reordered…`).
 *
 * The gate is read here with [updateIsOnlyFrom], for the reason `UpdateSpan`
 * gives: the binding exposes no view of an update's authors, and Kotlin has
 * no door to yrs's own decoder the way Rust's `p8_third_writer.rs` does. It is
 * a port of a V1 struct-section reader, stating the same rule over the same
 * bytes.
 *
 * One asymmetry is deliberate and is the reason this file carries a test the
 * Rust one does not. Rust's `update_is_only_from` decodes with yrs's own
 * `Update::decode_v1`, whose Any reader recurses without a bound, so a value
 * nested thousands deep overflows the stack there rather than answering; the
 * Kotlin gate carries a depth-64 bound ([MAX_ANY_DEPTH]), the bound other
 * readers of the same bytes hold, so
 * `a deeply nested Any value is refused rather than recursed into` — which
 * mirrors the same property as stated against pycrdt — is a statement about
 * it that has somewhere to live.
 */
class P8ThirdWriterSpec : StringSpec({

    /**
     * An update nobody can read cannot be shown to be this session's, so the
     * server's answer to one is no. The pycrdt statement of the property
     * refuses the same two inputs.
     */
    "the gate refuses bytes it cannot decode" {
        val garbage = byteArrayOf(0x01, 0x02, 0x03) + "garbage".toByteArray()
        updateIsOnlyFrom(garbage, 1uL) shouldBe false
        updateIsOnlyFrom(ByteArray(0), 1uL) shouldBe false
    }

    /**
     * A session that inserts and deletes in the same breath still wrote the
     * blocks it deleted, and the gate still has to see them.
     *
     * A state encoded at rest carries a deleted run in place of the string it
     * replaced: a block with a client id and no live content — but that shape
     * holds whichever way `Update::insertions`'s "include deleted" argument is
     * passed, since a `remove_range` leaves the ContentDeleted run either way.
     * It is `a garbage collected block still names its author` below that pins
     * the flag: only a fully garbage-collected block disappears from
     * `insertions(false)`.
     */
    "the gate reads an author whose every block is deleted" {
        Peer(7uL).use { author ->
            author.insert(0u, "hello")
            author.removeRange(0u, 5u)
            val update = author.diffAgainst(ByteArray(0))

            withClue("update ${hex(update)}") {
                updateIsOnlyFrom(update, 7uL) shouldBe true
            }
            updateIsOnlyFrom(update, 8uL) shouldBe false
        }
    }

    /**
     * A collected block names the client that wrote it, and the gate reads it.
     *
     * `Update::insertions` reports garbage-collected blocks only when asked to
     * include deleted ones, so a gate that asked it the other way round would
     * read this update as naming nobody and admit it from any session at all.
     * The mutation pass found exactly that; this is what it costs to close.
     */
    "a garbage collected block still names its author" {
        updateIsOnlyFrom(GC_BLOCK_UPDATE, 7uL) shouldBe true
        updateIsOnlyFrom(GC_BLOCK_UPDATE, 8uL) shouldBe false
    }

    /**
     * A Skip declares a hole in someone else's clock range and supplies no
     * content, so it names nobody — which means the gate passes it against ANY
     * client id, correctly: refusing it would refuse a session for a hole it
     * did not create.
     */
    "a skip names nobody" {
        updateIsOnlyFrom(SKIP_UPDATE, 7uL) shouldBe true
        updateIsOnlyFrom(SKIP_UPDATE, 11uL) shouldBe true
    }

    /**
     * Two different failure modes without the bound, both closed by it.
     *
     * At 100, an unbounded reader decodes the whole value and ADMITS the
     * update — `updateIsOnlyFrom` answers true, a silent divergence from
     * other readers of the same bytes, which refuse past depth 64. The bound
     * is what keeps this statement from admitting bytes they refuse.
     *
     * At 5000, an unbounded reader instead overflows the call stack. On the
     * JVM that is a StackOverflowError, which the gate's `catch` on
     * IllegalArgumentException does not intercept; the bound is what turns
     * that escaping throwable into an ordinary refusal.
     *
     * Both depths answer false, not an exception or an admission. Mirrors the
     * same property as stated against pycrdt, which the Rust tier has no
     * counterpart to: its `update_is_only_from`
     * carries no bound and documents that it overflows the stack instead.
     */
    "a deeply nested Any value is refused rather than recursed into" {
        for (depth in listOf(100, 5000)) {
            val update = nestedAnyUpdate(depth, 7uL)
            withClue("depth $depth") {
                updateIsOnlyFrom(update, 7uL) shouldBe false
                updateCovered(byteArrayOf(0), update) shouldBe false
            }
        }
    }

    /**
     * An update filed under the assigned id AND somebody else's. Every other test here offers the gate one author at a time, so a
     * gate that answered from the first client it decoded would pass all of them
     * — and this is the update it would have to pass for a block to land under a
     * client the server never issued.
     *
     * It is asked about BOTH authors, because answering from the first decoded
     * client is right about one of them by luck and the block order is not this
     * test's to fix. The shape is not exotic either: it is what a writer sending
     * its whole state rather than its own transaction's update would put on the
     * wire.
     */
    "the gate refuses an update that carries a second author" {
        val assigned = CORNER_CLIENT_IDS[5]
        val other = CORNER_CLIENT_IDS[0]
        Peer(assigned).use { session ->
            Peer(other).use { outsider ->
                val theirs = outsider.transact { tx ->
                    outsider.text.insert(tx, 0u, "theirs")
                    outsider.doc.encodeDiffV1(tx, ByteArray(0).asUByteList()).asByteArray()
                }
                val whole = session.transact { tx ->
                    session.text.insert(tx, 0u, "mine")
                    tx.transactionApplyUpdate(theirs.asUByteList())
                    session.doc.encodeDiffV1(tx, ByteArray(0).asUByteList()).asByteArray()
                }

                val authors = UpdateSpan.decode(whole).inserts.keys.toList()
                withClue("authors $authors") { authors.size shouldBe 2 }
                updateIsOnlyFrom(whole, assigned) shouldBe false
                updateIsOnlyFrom(whole, other) shouldBe false
            }
        }
    }

    /**
     * A writer writing under the id the server assigned it is an ordinary
     * third peer: the gate never refuses it, the three converge under a lossy
     * schedule, and nobody ends up holding a block under an unissued id.
     *
     * `assigned` is drawn over the whole domain a server may issue — uniform
     * below 2^53, zero included, because a masked uniform draw admits it. The issued set the last assertion checks
     * against is the three ids this case drew, never anything read back out
     * of the documents: a peer that invented an id would otherwise be asked
     * to confirm its own invention.
     *
     * The opening edit is the session's, so `admitted` is never zero and "the
     * gate refuses nothing" has something to be true of.
     */
    "a session on its assigned id converges with the two core peers" {
        checkAll(
            PropTestConfig(iterations = 64),
            distinctClientIds(2),
            Arb.uLong(0uL, (1uL shl 53) - 1uL),
            asciiChunk(),
            Arb.list(relayOp(anyEdit()), 4..39),
        ) { cores, assigned, opening, program ->
            assume(!cores.contains(assigned))
            Relay(listOf(cores[0], cores[1], assigned), 2, assigned).use { relay ->
                relay.edit(2, Edit.Insert(0, opening))
                relay.run(program)

                withClue(
                    "the server refused an update the session composed under the id it " +
                        "was assigned ($assigned)",
                ) { relay.refused shouldBe 0 }
                if (relay.admitted <= 0) fail("the session's opening edit never reached the gate")

                val views = relay.views(0 until 3)
                for (i in 1 until views.size) {
                    withClue("peers disagree once the server has replayed") {
                        views[i].first shouldBe views[0].first
                    }
                    withClue("peers agree on text but not on lineage") {
                        views[i].second shouldBe views[0].second
                    }
                }
                val issued = listOf(cores[0], cores[1], assigned)
                views.forEachIndexed { i, view ->
                    for (client in view.second.keys) {
                        if (!issued.contains(client)) {
                            fail(
                                "peer $i holds blocks under $client, which nobody issued; " +
                                    "the three ids are $issued",
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The u32 client-id truncation in yrs 0.18's V1 decoder arriving by a
     * second road, and the gate standing in it.
     *
     * The session was assigned an id and writes under a different one — here
     * the image the retired yswift decoder produced for it, which is a
     * mangling that really happened and therefore a better negative than an
     * arbitrary wrong number. Every update it composes names that image, so
     * every one is refused, and the two core peers converge holding no client
     * id but their own two: the fork does not happen in their documents, it
     * does not happen at all.
     *
     * `assigned` is drawn at or above 2^32 because below it the retired
     * decoder carried an id whole (P6's table), so there would be nothing
     * mangled and the gate would rightly admit the session. The image is also
     * required to differ from both core ids — one that collided with a core
     * peer's id would file the session's blocks under an issued client and
     * the last assertion would pass while the fork happened.
     */
    "a session whose binding mangled its id never reaches the core peers" {
        checkAll(
            PropTestConfig(iterations = 64),
            distinctClientIds(2),
            Arb.uLong(1uL shl 32, (1uL shl 53) - 1uL),
            asciiChunk(),
            Arb.list(relayOp(insertEdit()), 4..39),
        ) { cores, assigned, opening, program ->
            val mangled = modelled(assigned)
            assume(!cores.contains(assigned) && !cores.contains(mangled))
            Relay(listOf(cores[0], cores[1], mangled), 2, assigned).use { relay ->
                relay.edit(2, Edit.Insert(0, opening))
                relay.run(program)

                withClue(
                    "the server admitted an update filed under $mangled from a session " +
                        "assigned $assigned",
                ) { relay.admitted shouldBe 0 }
                if (relay.refused <= 0) fail("the session's opening edit never reached the gate")

                val views = relay.views(0 until 2)
                withClue("the two core peers disagree") {
                    views[1].first shouldBe views[0].first
                }
                withClue("the two core peers agree on text but not on lineage") {
                    views[1].second shouldBe views[0].second
                }
                views.forEachIndexed { i, view ->
                    for (client in view.second.keys) {
                        if (!cores.contains(client)) {
                            fail(
                                "core peer $i holds blocks under $client; the session was " +
                                    "assigned $assigned and writes under $mangled",
                            )
                        }
                    }
                }
            }
        }
    }
})

/**
 * A one-block update whose block is a garbage-collected run: one client (7),
 * one struct at clock 0, `info` byte 0 for GC, a length, and an empty delete
 * set. Written by hand because neither core reaches this shape from a text
 * document — yrs replaces a deleted item's content with a Deleted run and
 * only collects the block outright when the item's own parent type was
 * deleted too — and the server decodes whatever a session sends rather than
 * whatever the document is supposed to hold. The pycrdt statement of the
 * property pins the same seven bytes.
 */
private val GC_BLOCK_UPDATE: ByteArray =
    byteArrayOf(0x01, 0x01, 0x07, 0x00, 0x00, 0x05, 0x00)

/**
 * A one-block update whose block is a Skip: one client (11), one struct at
 * clock 0, an `info` byte of 0x0A for Skip, a length, and an empty delete
 * set. The pycrdt statement of the property pins the same seven bytes.
 */
private val SKIP_UPDATE: ByteArray =
    byteArrayOf(0x01, 0x01, 0x0B, 0x00, 0x0A, 0x04, 0x00)

/**
 * One struct under [client] holding a ContentAny value nested [depth] arrays
 * deep before an empty array terminates it, followed by an empty delete set:
 * one group, one struct, an info byte of 0x88 (content ref 8, Any, with a
 * left origin so the reader steps over an id rather than a parent block and
 * actually reaches the content), a left origin of (0, 0), an Any count of 1,
 * then [depth] repeats of an array-of-one (tag 117, varUint(1)), then an
 * empty array (tag 117, varUint(0)) at the bottom. The same bytes the pycrdt
 * statement of the property builds for its depth test.
 */
private fun nestedAnyUpdate(depth: Int, client: ULong): ByteArray {
    val head = byteArrayOf(1, 1, client.toByte(), 0, 0x88.toByte(), 0, 0, 1)
    val nesting = ByteArray(depth * 2)
    for (i in 0 until depth) {
        nesting[i * 2] = 117
        nesting[i * 2 + 1] = 1
    }
    return head + nesting + byteArrayOf(117, 0) + byteArrayOf(0)
}

/**
 * One instruction of a generated program, P1's vocabulary: a peer edits, and
 * a channel that delivers out of order, duplicates and drops. Peer and inbox
 * indices are raw draws, taken modulo the live counts when interpreted.
 */
private sealed class RelayOp {
    data class Edit(val peer: Int, val edit: ai.kidgloves.ykt.Edit) : RelayOp()
    data class Deliver(val peer: Int, val index: Int) : RelayOp()
    data class Duplicate(val peer: Int, val index: Int) : RelayOp()
    data class Drop(val peer: Int, val index: Int) : RelayOp()
}

private fun relayOp(edit: Arb<Edit>): Arb<RelayOp> = Arb.choose(
    4 to Arb.bind(Arb.int(0..2), edit) { peer, e -> RelayOp.Edit(peer, e) },
    6 to Arb.bind(Arb.int(0..2), Arb.int(0..7)) { peer, index -> RelayOp.Deliver(peer, index) },
    1 to Arb.bind(Arb.int(0..2), Arb.int(0..7)) { peer, index -> RelayOp.Duplicate(peer, index) },
    2 to Arb.bind(Arb.int(0..2), Arb.int(0..7)) { peer, index -> RelayOp.Drop(peer, index) },
)

/** Inserts and deletes, which is what the positive runs. */
private fun anyEdit(): Arb<Edit> = asciiEdits(1..1).map { it[0] }

/**
 * Inserts only, which is what the negative runs — and not for weight: a
 * delete-only update names no author in its struct section, so the gate
 * admits it by design (see [updateIsOnlyFrom]) and "every update refused"
 * would be false for a reason that has nothing to do with the mangled id.
 */
private fun insertEdit(): Arb<Edit> =
    Arb.bind(Arb.int(0..63), asciiChunk()) { pos, chunk -> Edit.Insert(pos, chunk) }

/**
 * Three peers on a lossy channel behind a forwarding server, with the gate on
 * everything the session peer sends.
 *
 * The Rust `Relay` holds its own `Peer { doc, text }`; that is exactly the
 * harness [Peer], which also closes both Rust objects.
 */
private class Relay(clients: List<ULong>, private val session: Int, private val assigned: ULong) :
    AutoCloseable {
    private val peers: List<Peer> = clients.map { Peer(it) }
    private val inboxes: List<MutableList<ByteArray>> = peers.map { mutableListOf<ByteArray>() }
    private val dropped: MutableList<Pair<Int, ByteArray>> = mutableListOf()

    /** Every update the server admitted, in the order it admitted them. */
    private val log: MutableList<ByteArray> = mutableListOf()

    var admitted: Int = 0
        private set
    var refused: Int = 0
        private set

    private fun send(peer: Int, update: ByteArray) {
        if (update.isEmpty()) return
        if (peer == session) {
            if (!updateIsOnlyFrom(update, assigned)) {
                refused += 1
                return
            }
            admitted += 1
        }
        for (other in peers.indices) {
            if (other != peer) inboxes[other].add(update)
        }
        log.add(update)
    }

    /**
     * A peer edits and sends what that edit produced.
     *
     * The diff is taken against the peer's state vector as it is at the
     * moment of the edit, not against the one it last composed from, and P1's
     * channel differs here on purpose. A peer's outbound bytes on that
     * channel may legitimately relay another peer's blocks; a session's may
     * not, because the server's gate is about authorship and the session sends
     * what its own transaction produced and nothing else. Composing against a
     * stale vector would put a core peer's blocks in the session's update and
     * have the gate refuse it for the test's bookkeeping rather than for the
     * session's id. The pycrdt statement of the property composes the same
     * way.
     */
    fun edit(peerDraw: Int, edit: Edit) {
        val peer = peerDraw % peers.size
        val p = peers[peer]
        val update = p.transact { tx ->
            val before = tx.transactionStateVector()
            when (val planned = planFor(p.text.getString(tx), edit)) {
                null -> null
                else -> {
                    run(p.text, tx, planned)
                    p.doc.encodeDiffV1(tx, before).asByteArray()
                }
            }
        } ?: return
        send(peer, update)
    }

    private fun deliver(peerDraw: Int, indexDraw: Int) {
        val peer = peerDraw % peers.size
        if (inboxes[peer].isEmpty()) return
        val index = indexDraw % inboxes[peer].size
        apply(peer, inboxes[peer].removeAt(index))
    }

    private fun duplicate(peerDraw: Int, indexDraw: Int) {
        val peer = peerDraw % peers.size
        if (inboxes[peer].isEmpty()) return
        val index = indexDraw % inboxes[peer].size
        inboxes[peer].add(inboxes[peer][index])
    }

    private fun dropOne(peerDraw: Int, indexDraw: Int) {
        val peer = peerDraw % peers.size
        if (inboxes[peer].isEmpty()) return
        val index = indexDraw % inboxes[peer].size
        dropped.add(peer to inboxes[peer].removeAt(index))
    }

    private fun apply(peer: Int, update: ByteArray) {
        peers[peer].apply(update)
    }

    /** Offer everything again, drain every inbox, then replay the server's log. */
    private fun settle() {
        for ((peer, update) in dropped) inboxes[peer].add(update)
        dropped.clear()
        for (peer in peers.indices) {
            val queue = inboxes[peer].toMutableList()
            inboxes[peer].clear()
            queue.addAll(log)
            for (update in queue) apply(peer, update)
        }
    }

    fun run(program: List<RelayOp>) {
        for (o in program) {
            when (o) {
                is RelayOp.Edit -> edit(o.peer, o.edit)
                is RelayOp.Deliver -> deliver(o.peer, o.index)
                is RelayOp.Duplicate -> duplicate(o.peer, o.index)
                is RelayOp.Drop -> dropOne(o.peer, o.index)
            }
        }
        settle()
    }

    /** The text and state vector of each peer in [which]. */
    fun views(which: IntRange): List<Pair<String, Map<ULong, UInt>>> =
        which.map { i ->
            val view = peers[i].observe()
            view.text to view.state
        }

    override fun close() {
        peers.forEach(Peer::close)
    }
}
