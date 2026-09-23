package ai.kidgloves.ykt

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll

/**
 * P5: offsets on real text.
 *
 * `YrsDoc()` builds its document with `OffsetKind::Utf16`, so every index and
 * length crossing the FFI is a count of UTF-16 code units, which is what a
 * Kotlin `String.length` gives and what this tier hands in — the same contract
 * the Rust tier states with `String::encode_utf16` and the Swift wrapper with
 * `String.utf16`. This property holds the binding to that against a plain
 * string model over text that mixes ASCII, accented Latin, Cyrillic, CJK and
 * non-BMP emoji, the last of which costs two units per character.
 *
 * It stands guard over the 0.27.1 skip-item slicing underflow and the 0.27.3
 * missing right neighbour on an insert at index 0: both were failures of where
 * an edit lands, and both would show here as a model mismatch rather than as
 * an error.
 *
 * The mirror of kidgloves-inc/yswift's `lib/src/proptests/p5_offsets.rs`,
 * statement for statement. The last two tests are not contracts: they pin
 * DEFECTS of the pinned core — an offset inside a surrogate pair rounding up,
 * and a delete cutting one desynchronising the state vector — so that a change
 * in either is visible instead of silent.
 */
class P5OffsetsSpec : StringSpec({

    // After every edit the document's text is the model's text and its length
    // is the model's length in UTF-16 code units, and a peer fed each update as
    // it was made ends at the same text.
    "text_and_length_track_a_plain_string_model" {
        checkAll(PropTestConfig(iterations = 128), mixedEdits(1..15)) { edits ->
            Peer(null).use { doc ->
                Peer(null).use { peer ->
                    val model = StringBuilder()
                    var seen = doc.stateVector()

                    for (edit in edits) {
                        val planned = plan(model, edit) ?: continue
                        val (rendered, length, update) = doc.transact { txn ->
                            run(doc.text, txn, planned)
                            Triple(
                                doc.text.getString(txn),
                                doc.text.length(txn),
                                doc.doc.encodeDiffV1(txn, seen.asUByteList()).asByteArray(),
                            )
                        }

                        rendered shouldBe model.toString()
                        length shouldBe utf16Units(model.toString()).toUInt()

                        seen = doc.stateVector()
                        peer.apply(update)
                    }

                    val (peerRendered, peerLength) = peer.transact { txn ->
                        peer.text.getString(txn) to peer.text.length(txn)
                    }
                    peerRendered shouldBe model.toString()
                    peerLength shouldBe utf16Units(model.toString()).toUInt()
                    peer.observe().missing shouldBe false
                }
            }
        }
    }

    // The same run of edits reaching a peer as one full state rather than as a
    // stream: the same text, so nothing in the per-edit path depends on having
    // seen the edits separately.
    "a_peer_given_the_full_state_reaches_the_same_text" {
        checkAll(PropTestConfig(iterations = 128), mixedEdits(1..15)) { edits ->
            Peer(null).use { doc ->
                val model = StringBuilder()
                for (edit in edits) {
                    val planned = plan(model, edit)
                    if (planned != null) {
                        doc.transact { txn -> run(doc.text, txn, planned) }
                    }
                }
                val full = doc.snapshot()

                Peer(null).use { peer ->
                    val (rendered, length) = peer.transact { txn ->
                        txn.transactionApplyUpdate(full.asUByteList())
                        peer.text.getString(txn) to peer.text.length(txn)
                    }
                    rendered shouldBe model.toString()
                    length shouldBe utf16Units(model.toString()).toUInt()
                }
            }
        }
    }

    // An offset that falls inside a surrogate pair is not a position in the
    // text, and the binding neither rejects it nor panics: it rounds up to the
    // end of the pair, so an insert at 1 into a document holding one emoji
    // lands after the emoji, exactly where an insert at 2 lands. This test pins
    // what the binding does TODAY rather than what it should do; it is here so
    // that a change in the core's clamping is visible instead of silent, and a
    // caller that wants a different answer has to say so.
    "an_offset_inside_a_surrogate_pair_rounds_up_to_the_pair_end" {
        fun landed(offset: UInt): Pair<String, UInt> = Peer(null).use { doc ->
            doc.transact { txn -> doc.text.insert(txn, 0u, GRINNING) }
            doc.transact { txn ->
                doc.text.insert(txn, offset, "x")
                doc.text.getString(txn) to doc.text.length(txn)
            }
        }

        landed(0u) shouldBe ("x$GRINNING" to 3u)
        // Offset 1 is the middle of the pair. It does not split it, and it does
        // not fail: it behaves as offset 2, the position after it.
        landed(1u) shouldBe ("${GRINNING}x" to 3u)
        landed(2u) shouldBe ("${GRINNING}x" to 3u)
    }

    // A delete whose range cuts a surrogate pair in half leaves the document's
    // own clock one unit ahead of what its encoded state carries, so a peer
    // restored from that state reports a permanently lower state vector for the
    // same text. This test pins the DEFECT, not the contract: what ought to
    // happen is that the two state vectors agree.
    //
    // Minimal reproducer, and it is the core rather than the binding: a
    // document with `OffsetKind::Utf16` holding "a\u{1F600}b" and asked to
    // `remove_range(1, 1)` renders "ab" and reports clock 4, while its own full
    // state re-integrates as clock 3. Reproduced against stock yrs 0.27.4 with
    // no binding in the way. The text still converges, so this is a bookkeeping
    // divergence rather than data loss, but two peers holding identical content
    // disagree about what they have seen.
    //
    // The properties above never generate such an offset: they plan every edit
    // against the text the document is actually holding, which is what a caller
    // does. This is the one place the tier says what happens when a caller does
    // not.
    "a_delete_that_cuts_a_surrogate_pair_desynchronises_the_state_vector" {
        Peer(null).use { author ->
            author.transact { txn -> author.text.insert(txn, 0u, "a${GRINNING}b") }
            // The unit at offset 1 is the high surrogate of the emoji. Deleting
            // it takes the whole character with it.
            author.transact { txn -> author.text.removeRange(txn, 1u, 1u) }

            val (rendered, authorState) = author.transact { txn ->
                author.text.getString(txn) to stateMap(txn)
            }
            val full = author.snapshot()

            Peer(null).use { peer ->
                val (peerRendered, peerState) = peer.transact { txn ->
                    txn.transactionApplyUpdate(full.asUByteList())
                    peer.text.getString(txn) to stateMap(txn)
                }

                rendered shouldBe "ab"
                withClue("the text does converge") { peerRendered shouldBe rendered }
                val client = authorState.keys.first()
                withClue("the author counts four units") { authorState[client] shouldBe 4u }
                withClue("and the peer restored from its state counts three") {
                    peerState[client] shouldBe 3u
                }
            }
        }
    }
})

/**
 * U+1F600 GRINNING FACE, spelled as its surrogate pair: one code point, two
 * UTF-16 code units, which is the whole subject of the two tests that use it.
 */
private const val GRINNING: String = "😀"
