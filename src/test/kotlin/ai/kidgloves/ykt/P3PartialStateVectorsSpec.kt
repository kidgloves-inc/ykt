package ai.kidgloves.ykt

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.assume
import io.kotest.property.checkAll

/**
 * Author a run of updates, each the diff since the author's state before it.
 *
 * The Rust `author_run` returns a `YrsDoc` the caller lets fall out of scope;
 * every Kotlin handle holds a Rust pointer until `close()`, so the caller
 * takes the [Peer] into a `use {}` block instead.
 */
private fun authorRun(client: ULong, edits: List<Edit>): Pair<Peer, List<ByteArray>> {
    val doc = Peer(client, false)
    val model = StringBuilder()
    var seen = doc.stateVector()
    val updates = mutableListOf<ByteArray>()
    for (edit in edits) {
        val planned = plan(model, edit) ?: continue
        val update = doc.transact { tx ->
            run(doc.text, tx, planned)
            doc.doc.encodeDiffV1(tx, seen.asUByteList()).asByteArray()
        }
        seen = doc.stateVector()
        updates.add(update)
    }
    return doc to updates
}

/**
 * P3: partial state vectors.
 *
 * A peer that is behind says so with a state vector, and the answer it gets
 * back has to be exactly what it lacks: applying the diff must leave it
 * holding the same text as the peer that produced it and waiting for nothing.
 * The hard case is a state vector with a hole in the middle of a client's
 * sequence, which is the shape the skip-item paths handle and where yrs
 * 0.27.1 fixed a slicing underflow and a swallowed pending update. This
 * property builds that hole on purpose: a peer takes a prefix of a run of
 * updates, then a strictly later one, and only then asks for the difference.
 *
 * The Kotlin mirror of
 * kidgloves-inc/yswift's `lib/src/proptests/p3_partial_state_vectors.rs`,
 * statement for statement. The one thing that cannot be mirrored is the
 * oracle: Rust reaches past the binding for `Update::decode_v1`, while
 * [UpdateSpan] here is an independent port of a V1 struct-section reader.
 */
class P3PartialStateVectorsSpec : StringSpec({

    /**
     * A peer holding a prefix, handed a strictly later update first, is
     * exactly repaired by a diff against the state vector it can honestly
     * report.
     */
    "a_diff_against_a_partial_state_vector_repairs_the_peer" {
        checkAll(
            PropTestConfig(iterations = 128),
            clientId(),
            mixedEdits(3..13),
            Arb.int(0..11),
            Arb.int(0..11),
        ) { client, edits, prefix, skip ->
            val (author, updates) = authorRun(client, edits)
            author.use {
                assume(updates.size >= 3)

                // The prefix the peer already has, and a strictly later update
                // that leaves a hole behind it.
                val k = prefix % (updates.size - 2)
                val j = k + 2 + (skip % (updates.size - k - 2))

                Peer(null).use { peer ->
                    for (update in updates.subList(0, k)) {
                        peer.apply(update)
                    }

                    // The later update leaves a hole. What the peer must NOT do
                    // is integrate it: its blocks stay outside the peer's state
                    // vector until the hole is filled.
                    val stateBefore = peer.transact { tx -> stateMap(tx) }
                    val span = UpdateSpan.decode(updates[j])
                    val covered = span.coveredBy(stateBefore)
                    peer.apply(updates[j])
                    val stateAfter = peer.transact { tx -> stateMap(tx) }
                    val first = span.firstInsertClock(client)
                    val hole = if (first != null) (stateBefore[client] ?: 0u) < first else false
                    if (hole) {
                        withClue(
                            "an update past a hole in its author's own sequence must not be integrated",
                        ) {
                            span.coveredBy(stateAfter) shouldBe false
                        }
                    }
                    // `hasMissingUpdates` may only be true if something really is
                    // outstanding. The converse does not hold on this core: a
                    // hole in one client's own sequence is recorded as a Skip
                    // block in the store, not as a pending update, so the flag
                    // stays false while the peer is demonstrably behind. See the
                    // module note in `p1_lossy_channel`.
                    if (covered) {
                        peer.observe().missing shouldBe false
                    }

                    // What the peer can honestly say it has seen, which does not
                    // include anything it is holding pending.
                    val sv = peer.stateVector()
                    val diff = author.diffAgainst(sv)
                    peer.apply(diff)

                    val authorView = author.transact { tx ->
                        author.text.getString(tx) to stateMap(tx)
                    }
                    val peerView = peer.transact { tx -> peer.text.getString(tx) to stateMap(tx) }
                    peerView.first shouldBe authorView.first
                    peerView.second shouldBe authorView.second
                    peer.observe().missing shouldBe false
                }
            }
        }
    }

    /**
     * A document's own diff against its own state vector adds nothing to it.
     * The delete set still travels, so the update is not empty; applying it
     * has to be a no-op all the same.
     */
    "a_documents_diff_against_itself_changes_nothing" {
        checkAll(
            PropTestConfig(iterations = 128),
            clientId(),
            mixedEdits(1..11),
        ) { client, edits ->
            val (doc, _) = authorRun(client, edits)
            doc.use {
                val before = doc.transact { tx -> doc.text.getString(tx) to stateMap(tx) }
                val sv = doc.stateVector()
                val diff = doc.diffAgainst(sv)
                doc.apply(diff)
                val after = doc.transact { tx -> doc.text.getString(tx) to stateMap(tx) }
                before shouldBe after
                doc.observe().missing shouldBe false
            }
        }
    }

    /**
     * The empty slice means "seen nothing" whatever the document holds. This
     * is a unit test in `doc.rs` for one document; here it is a property over
     * documents, because the equivalence is what lets the Swift side pass an
     * empty array straight through.
     */
    "an_empty_slice_is_the_encoded_empty_state_vector" {
        checkAll(
            PropTestConfig(iterations = 128),
            clientId(),
            mixedEdits(1..11),
        ) { client, edits ->
            val (doc, _) = authorRun(client, edits)
            doc.use {
                val (whole, fromZero) = doc.transact { tx ->
                    doc.doc.encodeDiffV1(tx, ByteArray(0).asUByteList()).asByteArray() to
                        doc.doc.encodeDiffV1(tx, byteArrayOf(0).asUByteList()).asByteArray()
                }
                // Rust compares the two `Vec<u8>`; a Kotlin `ByteArray` compares
                // by identity, so the bytes are compared through [hex].
                hex(whole) shouldBe hex(fromZero)
            }
        }
    }
})
