package ai.kidgloves.ykt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll

private val PINNED_CLIENT: ULong = 6_968_031_897_510_372uL

/**
 * The pinned cross-core vector: client 6968031897510372 inserting "hello"
 * into the root text "prompt" in one transaction, diffed against the empty
 * state vector. Base64 AQHkw6PQtaywDAAEAQZwcm9tcHQFaGVsbG8A, which is what
 * the other core is asked to produce byte for byte.
 */
private const val PINNED_UPDATE_HEX: String =
    "0101e4c3a3d0b5acb00c0004010670726f6d70740568656c6c6f00"

/**
 * Scenario S, the canonical case the two cores are compared on. One client,
 * two transactions, an insert, a delete that splits the first block and an
 * insert at the head, then the four artefacts a peer could be handed: the
 * first update, the second update as a diff, the full state, and the state
 * vector. pycrdt (0.14.1 on yrs 0.27.2 and 0.14.4 on 0.27.4) produces the
 * same four byte for byte.
 *
 * The full state is encoded in a transaction of its own, after the one that
 * deleted has committed. That is the one place the two cores first seemed to
 * disagree: an encode taken inside the still-open transaction still carries
 * the deleted "el" as string content, because garbage collection runs at
 * commit, and it matches what pycrdt produces with `skipGc` instead. A
 * snapshot at rest is what the cross-core comparison pins, on both sides.
 */
private const val SCENARIO_S_U1_HEX: String =
    "0101e9f788889a84dc010004010670726f6d70740568656c6c6f00"
private const val SCENARIO_S_U2_HEX: String =
    "0101e9f788889a84dc010544e9f788889a84dc0100014101e9f788889a84dc01010102"
private const val SCENARIO_S_FULL_HEX: String =
    "0104e9f788889a84dc010004010670726f6d7074016881e9f788889a84dc01000284e9f788889a84dc0102026c6f44e9f788889a84dc0100014101e9f788889a84dc01010102"
private const val SCENARIO_S_SV_HEX: String = "01e9f788889a84dc0106"

private val SCENARIO_S_CLIENT: ULong = 967_714_667_641_833uL

private fun compose(client: ULong, edits: List<Edit>): Triple<ByteArray, ByteArray, String> =
    Peer(client, false).use { doc ->
        val model = StringBuilder()
        for (edit in edits) {
            val planned = plan(model, edit)
            if (planned != null) {
                doc.transact { tx -> run(doc.text, tx, planned) }
            }
        }
        doc.transact { tx ->
            Triple(
                doc.doc.encodeDiffV1(tx, ByteArray(0).asUByteList()).asByteArray(),
                tx.transactionEncodeStateAsUpdate().asByteArray(),
                doc.text.getString(tx),
            )
        }
    }

/**
 * P4: byte determinism.
 *
 * Update bytes produced by one core are replayed by the other and compared.
 * That is only worth doing if the bytes are a function of the
 * block set rather than of how the block set came about, so this property
 * checks the claim live: the same client id making the same edits encodes to
 * the same bytes, and a document that learned a state by applying an update
 * re-encodes that state to the bytes it arrived as.
 *
 * It also carries the two fixed vectors the cross-core comparison uses,
 * both pinned to their exact bytes because the other core has reproduced
 * them: the first is a fork-produced update pycrdt replays, the second,
 * scenario S, was computed here and with pycrdt independently and compared
 * before it was pinned, so the constants record an agreement rather than
 * this core's opinion of itself.
 *
 * The Kotlin mirror of
 * kidgloves-inc/yswift's `lib/src/proptests/p4_byte_determinism.rs`.
 * Every constant is copied byte for byte from it; the two fixed tests and the
 * two properties are the same statements in the same order.
 *
 * The Rust tier has a third fixed test, `the_probe_seed_is_scenario_s_first_update`,
 * which pins `probe::seed_update()` — the one place in that tier where `yrs`
 * rather than the binding is the author — to [SCENARIO_S_U1_HEX]. It would sit
 * here, after `scenario_s_bytes`. There is no probe module on this side (the
 * fuzz targets are Rust's), so there is nothing for it to compare, and it is
 * deliberately left out rather than approximated.
 */
class P4ByteDeterminismSpec : StringSpec({

    "the_pinned_cross_core_vector_still_encodes_to_its_bytes" {
        Peer(PINNED_CLIENT, false).use { doc ->
            val bytes = doc.transact { tx ->
                doc.text.insert(tx, 0u, "hello")
                doc.doc.encodeDiffV1(tx, ByteArray(0).asUByteList()).asByteArray()
            }
            hex(bytes) shouldBe PINNED_UPDATE_HEX
        }
    }

    "scenario_s_bytes" {
        Peer(SCENARIO_S_CLIENT, false).use { doc ->
            val (u1, svAfterT1) = doc.transact { tx ->
                doc.text.insert(tx, 0u, "hello")
                doc.doc.encodeDiffV1(tx, ByteArray(0).asUByteList()).asByteArray() to
                    tx.transactionStateVector().asByteArray()
            }

            val (u2, rendered) = doc.transact { tx ->
                doc.text.removeRange(tx, 1u, 2u)
                doc.text.insert(tx, 0u, "A")
                doc.doc.encodeDiffV1(tx, svAfterT1.asUByteList()).asByteArray() to
                    doc.text.getString(tx)
            }
            val (full, sv) = doc.transact { tx ->
                tx.transactionEncodeStateAsUpdate().asByteArray() to
                    tx.transactionStateVector().asByteArray()
            }

            // "hello" without the units at [1, 3) is "hlo"; "A" at the head makes
            // "Ahlo".
            rendered shouldBe "Ahlo"
            hex(u1) shouldBe SCENARIO_S_U1_HEX
            hex(u2) shouldBe SCENARIO_S_U2_HEX
            hex(full) shouldBe SCENARIO_S_FULL_HEX
            hex(sv) shouldBe SCENARIO_S_SV_HEX

            // A peer that replays U1 then U2 reaches the same text as one that is
            // handed FULL, which is what makes the four artefacts one scenario.
            Peer(null).use { replayed ->
                val a = replayed.transact { tx ->
                    tx.transactionApplyUpdate(u1.asUByteList())
                    tx.transactionApplyUpdate(u2.asUByteList())
                    replayed.text.getString(tx)
                }
                Peer(null).use { whole ->
                    val b = whole.transact { tx ->
                        tx.transactionApplyUpdate(full.asUByteList())
                        whole.text.getString(tx)
                    }
                    a shouldBe "Ahlo"
                    b shouldBe "Ahlo"
                }
            }
        }
    }

    /**
     * (i) The bytes are a function of the client id and the edits. Two
     * documents that never met, given the same id and the same run of edits,
     * encode to the same diff and the same full state.
     */
    "the_same_edits_under_the_same_client_id_encode_identically" {
        checkAll(
            PropTestConfig(iterations = 96),
            clientId(),
            mixedEdits(1..11),
        ) { client, edits ->
            val (diffA, fullA, textA) = compose(client, edits)
            val (diffB, fullB, textB) = compose(client, edits)
            textA shouldBe textB
            hex(diffA) shouldBe hex(diffB)
            hex(fullA) shouldBe hex(fullB)
        }
    }

    /**
     * (ii) Re-encoding is stable across the wire: a fresh document handed A's
     * full state encodes that state back to the same bytes. If this failed,
     * pinned bytes would record a peer's history rather than its state, and
     * the cross-core comparison would be meaningless.
     */
    "a_document_re_encodes_the_full_state_it_was_given" {
        checkAll(
            PropTestConfig(iterations = 96),
            clientId(),
            mixedEdits(1..11),
        ) { client, edits ->
            val (_, fullA, textA) = compose(client, edits)

            Peer(null).use { peer ->
                val (fullB, textB) = peer.transact { tx ->
                    tx.transactionApplyUpdate(fullA.asUByteList())
                    tx.transactionEncodeStateAsUpdate().asByteArray() to
                        peer.text.getString(tx)
                }

                textA shouldBe textB
                hex(fullA) shouldBe hex(fullB)
            }
        }
    }
})
