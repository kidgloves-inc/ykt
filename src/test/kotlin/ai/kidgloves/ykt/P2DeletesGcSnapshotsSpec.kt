package ai.kidgloves.ykt

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.assume
import io.kotest.property.checkAll

/**
 * P2: deletes, garbage collection and snapshots.
 *
 * This is the daily path of a compacting server: a document is served as a
 * snapshot of the whole document plus the updates that came after it, and a
 * peer joining that way has to end up where a peer that watched every update
 * go by ended up. Deletes are what makes it interesting. With garbage
 * collection on (the default) a deleted run stops being an item and becomes a
 * tombstone, so the snapshot a late joiner gets is not the same block set the
 * early peer assembled, and the delete set has to carry the difference. yrs
 * 0.27.2 fixed a delete set that was lost after `apply_update`, which is
 * exactly the failure this property would show: the late joiner's text would
 * carry back text the early peer had deleted.
 *
 * The property is run with the default garbage collection and with
 * `skipGc = true`, because the two produce different block sets from the
 * same history and both have to converge.
 *
 * The Kotlin mirror of
 * kidgloves-inc/yswift's `lib/src/proptests/p2_deletes_gc_snapshots.rs`,
 * statement for statement: the same generators, the same case count, the same
 * assertions in the same words. A property that fails here and holds in Rust
 * is itself a finding.
 */
class P2DeletesGcSnapshotsSpec : StringSpec({

    /**
     * A late joiner given a snapshot plus the tail reaches the same document
     * as the peer that produced them, and as a peer that was fed every update
     * one at a time.
     */
    "a_snapshot_plus_the_tail_equals_the_whole_history" {
        checkAll(
            PropTestConfig(iterations = 64),
            distinctClientIds(2),
            Arb.boolean(),
            mixedEdits(2..11),
            mixedEdits(0..5),
            Arb.int(0..23),
        ) { clients, skipGc, editsA, editsB, cut ->
            Peer(clients[0], skipGc).use { a ->
                Peer(clients[1]).use { b ->
                    // Every update A emits, in order: its own edits and what it
                    // learned from B, each a diff against A's state before that
                    // step.
                    val stream = mutableListOf<ByteArray>()
                    var seen = a.stateVector()
                    var seenB = b.stateVector()

                    val steps = maxOf(editsA.size, editsB.size)
                    for (i in 0 until steps) {
                        var mutated = false
                        val editA = editsA.getOrNull(i)
                        if (editA != null) {
                            val ran = a.transact { tx ->
                                when (val planned = planFor(a.text.getString(tx), editA)) {
                                    null -> false
                                    else -> {
                                        run(a.text, tx, planned)
                                        true
                                    }
                                }
                            }
                            mutated = mutated || ran
                        }
                        // B edits without having seen A, so its blocks arrive
                        // concurrent.
                        val editB = editsB.getOrNull(i)
                        if (editB != null) {
                            val update = b.transact { tx ->
                                planFor(b.text.getString(tx), editB)?.let { planned ->
                                    run(b.text, tx, planned)
                                    b.doc.encodeDiffV1(tx, seenB.asUByteList()).asByteArray()
                                }
                            }
                            if (update != null) {
                                seenB = b.stateVector()
                                a.apply(update)
                                mutated = true
                            }
                        }
                        if (mutated) {
                            val update = a.transact { tx ->
                                a.doc.encodeDiffV1(tx, seen.asUByteList()).asByteArray()
                            }
                            seen = a.stateVector()
                            stream.add(update)
                        }
                    }
                    assume(stream.isNotEmpty())

                    // The peer that watched every update go by.
                    Peer(null).use { d ->
                        for (update in stream) {
                            d.apply(update)
                        }

                        // The snapshot is taken part way through, which means
                        // replaying the stream from the start up to the cut and
                        // then asking A's earlier self for its full state. A only
                        // exists once, so the earlier self is a peer built from
                        // the prefix.
                        val at = cut % stream.size
                        val snapshot = Peer(null).use { snapshotPeer ->
                            for (update in stream.subList(0, at + 1)) {
                                snapshotPeer.apply(update)
                            }
                            snapshotPeer.snapshot()
                        }

                        // The late joiner: snapshot first, then the tail.
                        Peer(null).use { c ->
                            c.apply(snapshot)
                            for (update in stream.subList(at + 1, stream.size)) {
                                c.apply(update)
                            }

                            val viewA = a.transact { tx -> a.text.getString(tx) to stateMap(tx) }
                            val viewC = c.transact { tx -> c.text.getString(tx) to stateMap(tx) }
                            val viewD = d.transact { tx -> d.text.getString(tx) to stateMap(tx) }

                            withClue("late joiner text") { viewC.first shouldBe viewA.first }
                            withClue("late joiner state vector") { viewC.second shouldBe viewA.second }
                            withClue("streamed peer text") { viewD.first shouldBe viewC.first }
                            withClue("streamed peer state vector") { viewD.second shouldBe viewC.second }
                            a.observe().missing shouldBe false
                            c.observe().missing shouldBe false
                            d.observe().missing shouldBe false
                        }
                    }
                }
            }
        }
    }

    /**
     * Deleted text does not come back. A run of deletes, then a snapshot, then
     * a fresh peer from that snapshot alone: what the peer renders is what the
     * author renders, tombstones and all. With garbage collection on, the
     * deleted items are gone from the snapshot entirely and only the delete
     * set says they ever existed.
     */
    "a_snapshot_carries_the_deletes" {
        checkAll(
            PropTestConfig(iterations = 64),
            clientId(),
            Arb.boolean(),
            mixedEdits(2..13),
        ) { client, skipGc, edits ->
            Peer(client, skipGc).use { a ->
                val model = StringBuilder()
                for (edit in edits) {
                    val planned = plan(model, edit)
                    if (planned != null) {
                        a.transact { tx -> run(a.text, tx, planned) }
                    }
                }
                // One more delete, to be sure at least one exists.
                val deleted = a.transact { tx ->
                    // A whole character, never half of a surrogate pair.
                    val units = firstCharUnits(a.text.getString(tx))
                    if (units > 0u) {
                        a.text.removeRange(tx, 0u, units)
                    }
                    a.text.getString(tx)
                }
                val snapshot = a.snapshot()

                Peer(null).use { peer ->
                    val view = peer.transact { tx ->
                        tx.transactionApplyUpdate(snapshot.asUByteList())
                        peer.text.getString(tx) to stateMap(tx)
                    }
                    val author = a.transact { tx -> a.text.getString(tx) to stateMap(tx) }
                    view.first shouldBe deleted
                    view.first shouldBe author.first
                    view.second shouldBe author.second
                    peer.observe().missing shouldBe false
                }
            }
        }
    }
})
