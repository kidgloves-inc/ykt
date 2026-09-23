package ai.kidgloves.ykt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.uLong
import io.kotest.property.checkAll
import uniffi.yniffi.YrsDoc
import uniffi.yniffi.YrsDocException

/**
 * P6: the retired decoder as a negative.
 *
 * This property does not test the binding as it is. It tests that the harness
 * can still see the bug the fork exists for: yrs 0.18's `DecoderV1::read_client`
 * read a client id into a `u32`, so every id at or above 2^32 arrived under a
 * different, scrambled author and the document forked silently (fixed by the 53 bit client ids in 0.26).
 *
 * **Where this tier differs from the Rust one.** The Rust tier can run the
 * retired core itself — `yrs 0.18.2` is a dev-dependency beside the pinned
 * 0.27.4 — and pins this model against it on every case. Nothing equivalent is
 * on the JVM: there is one compiled core behind the binding and it is the
 * current one. So on this side the table in [RETIRED_DECODER_TABLE] is the
 * statement of record, and it is the SAME table, row for row and in the same
 * order, that `p6_retired_decoder.rs` measured with 0.18.2 and that the same
 * property states against pycrdt. A row that disagrees across the harnesses
 * is the finding.
 *
 * Concretely, Rust's `credited_by_the_retired_decoder(id)` becomes
 * [creditedByTheRetiredDecoder]: the real update this binding produces is
 * decoded here (by [UpdateSpan], a port of a V1 struct-section reader) to learn
 * which client its struct section names, and [modelled] — the model of the u32
 * accumulator, applied to the varint of exactly that id — says who yrs 0.18.2
 * would have credited it to. The id is read from the UPDATE and not from the
 * state vector on purpose, as `p6_retired_decoder.rs:16-20` records: the
 * truncation lived in the update decoder, while 0.18.2's
 * `StateVector::decode_v1` reads a client with a full width varint and gets a
 * 53 bit id right, so a state vector round trip proves nothing here.
 *
 * The constructive half requires the binding, on the core it actually pins, to
 * credit an update to exactly the id that authored it. The corner ids are
 * enumerated rather than drawn: 2^32 is the boundary the old decoder broke at,
 * and a uniform draw over 53 bits would hit it about once in two million.
 */
class P6RetiredDecoderSpec : StringSpec({

    // The table, row by row, against the model that is the statement of record
    // on this side. This is the table the pycrdt statement of the property
    // compares against, so a disagreement here is a disagreement between the
    // harnesses and not a detail of one.
    "the_retired_decoder_credits_exactly_the_table" {
        for ((id, expected) in RETIRED_DECODER_TABLE) {
            withClue("yrs 0.18.2 credited an update from $id to something other than $expected") {
                creditedByTheRetiredDecoder(id) shouldBe expected
            }
            withClue("the model disagrees with 0.18.2 on $id") {
                modelled(id) shouldBe expected
            }
        }
    }

    // Below 2^32 the old decoder is exact, which is why the bug hid for as long
    // as it did: every id yrs 0.18 itself generated survived it.
    "the_corner_ids_split_at_2_to_the_32" {
        for (id in CORNER_CLIENT_IDS) {
            val credited = creditedByTheRetiredDecoder(id)
            if (id < (1uL shl 32)) {
                withClue("$id is inside the old decoder's range") { credited shouldBe id }
            } else {
                withClue("$id must not survive the old decoder") { credited shouldNotBe id }
            }
        }
    }

    // The top of the domain is closed: 2^53 is the first id the wire cannot
    // carry (2^53 is an explicit invalid input, not a value to wrap), and the
    // constructor refuses it rather than let `ClientID::new` mask it to 0 — an
    // id nobody issued, which is the u32 client-id truncation's shape (an
    // update filed under an author that never wrote it) by a third road.
    "an_id_at_2_to_the_53_is_refused_by_the_constructor" {
        shouldThrow<YrsDocException.ClientIdOutOfRange> {
            YrsDoc.withClientId(1uL shl 53, false)
        }
        YrsDoc.withClientId((1uL shl 53) - 1uL, false).close()
    }

    // The constructive half, over the corners: on the core the fork pins, a
    // document authoring under a corner id has that exact id credited on a
    // fresh peer. This is the property the unclamp opens up.
    "every_corner_id_is_credited_unchanged_on_a_fresh_peer" {
        for (id in CORNER_CLIENT_IDS) {
            Peer(id).use { author ->
                val update = author.transact { txn ->
                    author.text.insert(txn, 0u, "hello")
                    author.doc.encodeDiffV1(txn, emptyList()).asByteArray()
                }

                Peer(null).use { peer ->
                    val credited = peer.transact { txn ->
                        txn.transactionApplyUpdate(update.asUByteList())
                        stateMap(txn) to peer.text.getString(txn)
                    }

                    withClue("the peer must credit the update to $id itself") {
                        credited.first shouldBe mapOf(id to 5u)
                    }
                    credited.second shouldBe "hello"
                    peer.observe().missing shouldBe false
                }
            }
        }
    }

    // Every id in the half of the domain the unclamp opened up is mangled by
    // the retired decoder, and mangled in the way the model says. If this ever
    // passed for some id, that id would be one a peer on yswift 0.2.1 could have
    // handled, and the harness would be blind there.
    "no_id_at_or_above_2_to_the_32_survives_the_retired_decoder" {
        checkAll(
            PropTestConfig(iterations = 256),
            Arb.uLong(1uL shl 32, (1uL shl 53) - 1uL),
        ) { id ->
            val credited = creditedByTheRetiredDecoder(id)
            credited shouldNotBe id
            credited shouldBe modelled(id)
        }
    }

    // And the same decoder is exact below 2^32, so a failure above it is about
    // the width of the accumulator and not about the encoding.
    "every_id_below_2_to_the_32_survives_the_retired_decoder" {
        checkAll(
            PropTestConfig(iterations = 256),
            Arb.uLong(1uL, (1uL shl 32) - 1uL),
        ) { id ->
            val credited = creditedByTheRetiredDecoder(id)
            credited shouldBe id
            credited shouldBe modelled(id)
        }
    }
})

/**
 * Author an update under [id] through the binding, read back the client the
 * update's struct section names, and report who yrs 0.18.2 would have said
 * wrote it.
 *
 * Rust hands the very same bytes to a linked `yrs 0.18.2` and asks that core.
 * This side cannot link it, so the answer comes from [modelled] — but applied
 * to the id the REAL update carries, decoded from the real bytes, rather than
 * to the id that was asked for: the round trip through the binding is still
 * what is under test, and a binding that wrote a different id than it was
 * given would show up here exactly as it does in Rust.
 */
private fun creditedByTheRetiredDecoder(id: ULong): ULong = Peer(id).use { author ->
    val update = author.transact { txn ->
        author.text.insert(txn, 0u, "hello")
        author.doc.encodeDiffV1(txn, emptyList()).asByteArray()
    }

    val clients = UpdateSpan.decode(update).inserts.keys.toList()
    withClue("the update has exactly one author") { clients.size shouldBe 1 }
    modelled(clients[0])
}
