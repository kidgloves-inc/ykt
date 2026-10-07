package ai.kidgloves.ykt

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choose
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.uLong
import uniffi.yniffi.YrsDoc
import uniffi.yniffi.YrsText
import uniffi.yniffi.YrsTransaction

/**
 * Shared strategies and observers for the Kotlin property tier: the mirror of
 * kidgloves-inc/yswift's `lib/src/proptests/helpers.rs`.
 *
 * The one place this tier cannot mirror the Rust one is the update decoder.
 * `helpers.rs` reaches past the binding for `Update::decode_v1`, because the
 * binding exposes no view of an update's clock ranges; nothing equivalent is
 * reachable from Kotlin, so [UpdateSpan] is an independent port of a V1
 * struct-section reader instead. Everything else here
 * goes through the binding, exactly as the Rust side does.
 *
 * `has_missing_updates` is `YrsTransaction.transactionHasMissingUpdates()`,
 * which is the same method the UDL exposes to Swift.
 */

/** The root text name every property uses. */
const val ROOT: String = "prompt"

/**
 * The client ids that have to be tried every run rather than waited for.
 * 2^31 is where the old Swift side used to clamp, 2^32 is where yrs 0.18's
 * `read_client` started truncating, and 2^53 - 1 is the top of the domain
 * `ClientID::new` admits.
 */
val CORNER_CLIENT_IDS: List<ULong> = listOf(
    1uL,
    (1uL shl 31) - 1uL,
    1uL shl 31,
    (1uL shl 32) - 1uL,
    1uL shl 32,
    (1uL shl 53) - 1uL,
)

/**
 * A client id: the corner set, weighted so it is drawn often, unioned with a
 * uniform draw over the whole 53 bit domain.
 *
 * `Arb.choose` is overloaded: over VALUES, and — the one used here — over
 * generators, which is what proptest's `prop_oneof!` is.
 */
fun clientId(): Arb<ULong> = Arb.choose(
    2 to Arb.of(CORNER_CLIENT_IDS),
    3 to Arb.uLong(1uL, (1uL shl 53) - 1uL),
)

/** `n` distinct client ids, which is what peers in one document need. */
fun distinctClientIds(n: Int): Arb<List<ULong>> =
    Arb.list(clientId(), n..n).filter { it.toSet().size == n }

private val ASCII_ALPHABET: List<String> = codePoints("abcdeFGHIJ0123 ")

/**
 * ASCII only, for the properties whose subject is the channel rather than the
 * text: every index is then a UTF-16 boundary and the model stays obvious.
 */
fun asciiChunk(): Arb<String> =
    Arb.list(Arb.of(ASCII_ALPHABET), 1..5).map { it.joinToString("") }

/**
 * The alphabets P5 mixes: ASCII, accented Latin, Cyrillic, CJK, and non-BMP
 * emoji, the last of which is two UTF-16 code units per character.
 *
 * Rust draws over `char`, a Unicode scalar value; a Kotlin `Char` is a UTF-16
 * code unit, so the alphabet is a list of one-code-point STRINGS and the emoji
 * are two code units each rather than two alphabet entries.
 */
private val MIXED_ALPHABET: List<String> =
    codePoints("abZ9 " + "éàüñçö" + "привет" + "日本語漢字" + "😀🎉🌍🧪")

fun mixedChunk(): Arb<String> =
    Arb.list(Arb.of(MIXED_ALPHABET), 1..4).map { it.joinToString("") }

/** Every Unicode code point of [s], each as its own string. */
private fun codePoints(s: String): List<String> =
    s.codePoints().toArray().map { String(Character.toChars(it)) }

/**
 * One generated edit, in code point positions. The positions are raw draws;
 * they are taken modulo the model's length when the edit is planned, so a
 * shrunk case stays meaningful whatever the text has become by then.
 */
sealed class Edit {
    data class Insert(val pos: Int, val chunk: String) : Edit()
    data class Delete(val pos: Int, val len: Int) : Edit()
}

/**
 * A run of edits. Unlike the Rust `Range<usize>`, a Kotlin [IntRange] is
 * INCLUSIVE at both ends: proptest's `1..6` is written `1..5` here.
 */
private fun edits(chunk: Arb<String>, count: IntRange): Arb<List<Edit>> {
    val inserts: Arb<Edit> = Arb.bind(Arb.int(0..63), chunk) { pos, c -> Edit.Insert(pos, c) }
    val deletes: Arb<Edit> = Arb.bind(Arb.int(0..63), Arb.int(0..7)) { pos, len -> Edit.Delete(pos, len) }
    return Arb.list(Arb.choose(3 to inserts, 1 to deletes), count)
}

/** A run of edits over the ASCII alphabet. */
fun asciiEdits(count: IntRange): Arb<List<Edit>> = edits(asciiChunk(), count)

/**
 * A run of edits over the mixed alphabets, which is where UTF-16 offsets stop
 * being the same number as code point offsets.
 */
fun mixedEdits(count: IntRange): Arb<List<Edit>> = edits(mixedChunk(), count)

/**
 * The same edit in the units the binding speaks: UTF-16 code units, because
 * `YrsDoc` builds a document with `OffsetKind::Utf16`.
 */
sealed class Planned {
    data class Insert(val offset: UInt, val chunk: String) : Planned()
    data class Delete(val offset: UInt, val length: UInt) : Planned()
}

/**
 * The number of UTF-16 code units [s] occupies. A Kotlin `String.length` IS
 * that count — a `Char` is one UTF-16 code unit — which is why the mixed
 * alphabet has to be handled in code points everywhere else in this file.
 */
fun utf16Units(s: String): Int = s.length

private fun join(cps: List<String>, from: Int, to: Int): String =
    cps.subList(from, to).joinToString("")

/**
 * Plan an edit against a plain string model, mutating the model and returning
 * what to hand the binding. `null` means the edit had nothing to work on (a
 * delete against empty text) and should be skipped.
 *
 * Indices are over Unicode code points, exactly as the Rust `Vec<char>` is;
 * offsets and lengths are UTF-16 code units. Rust inserts into its model at a
 * UTF-8 BYTE index; a Kotlin `StringBuilder` indexes in UTF-16 code units, so
 * the insertion index here is the offset itself.
 */
fun plan(model: StringBuilder, edit: Edit): Planned? {
    val cps = codePoints(model.toString())
    return when (edit) {
        is Edit.Insert -> {
            val idx = edit.pos % (cps.size + 1)
            val offset = utf16Units(join(cps, 0, idx))
            model.insert(offset, edit.chunk)
            Planned.Insert(offset.toUInt(), edit.chunk)
        }
        is Edit.Delete -> {
            if (cps.isEmpty()) return null
            val start = edit.pos % cps.size
            val count = (edit.len % (cps.size - start)) + 1
            val offset = utf16Units(join(cps, 0, start))
            val length = utf16Units(join(cps, start, start + count))
            val next = join(cps, 0, start) + join(cps, start + count, cps.size)
            model.setLength(0)
            model.append(next)
            Planned.Delete(offset.toUInt(), length.toUInt())
        }
    }
}

/**
 * Plan an edit against the text a document is actually holding, which is what
 * a caller does: it computes an offset from the string it just rendered.
 * A peer that has taken remote edits no longer matches any private model, and
 * planning against one would generate offsets that fall inside a character.
 */
fun planFor(current: String, edit: Edit): Planned? = plan(StringBuilder(current), edit)

/**
 * The number of UTF-16 code units the first character of [text] occupies, or
 * zero for empty text. Used to delete a whole character rather than half of
 * one.
 */
fun firstCharUnits(text: String): UInt =
    if (text.isEmpty()) 0u else Character.charCount(text.codePointAt(0)).toUInt()

/** Run a planned edit through the binding, inside an already open transaction. */
fun run(text: YrsText, tx: YrsTransaction, planned: Planned) {
    when (planned) {
        is Planned.Insert -> text.insert(tx, planned.offset, planned.chunk)
        is Planned.Delete -> text.removeRange(tx, planned.offset, planned.length)
    }
}

/**
 * A document with a chosen client id, and optionally without garbage
 * collection of deleted blocks. Throws `YrsDocException.ClientIdOutOfRange`
 * above 2^53 - 1, which no id this tier draws is.
 */
fun docWith(client: ULong, skipGc: Boolean): YrsDoc = YrsDoc.withClientId(client, skipGc)

/**
 * The root text of a document. Always taken before a transaction is opened:
 * `getText` transacts internally. The handle owns a Rust object; close it.
 */
fun rootText(doc: YrsDoc): YrsText = doc.getText(ROOT)

/**
 * Run [f] inside one transaction and close it. The binding's transactions are
 * explicit objects; leaving one open blocks the next `transact`. `free()`
 * commits the Rust transaction, `close()` releases the handle — the smoke test
 * does both, in that order.
 */
inline fun <R> withTxn(doc: YrsDoc, f: (YrsTransaction) -> R): R {
    val tx = doc.transact(null)
    try {
        return f(tx)
    } finally {
        tx.free()
        tx.close()
    }
}

/**
 * A document's state vector, decoded, through the binding's own accessor.
 * Sorted, so equality and printing are deterministic the way the Rust
 * `BTreeMap` is.
 */
fun stateMap(tx: YrsTransaction): Map<ULong, UInt> =
    tx.transactionClientStates().associate { it.clientId to it.clock }.toSortedMap()

/**
 * What a peer looks like from outside: its text, its state vector, and
 * whether it is still waiting for something.
 */
data class Observed(
    val text: String,
    val state: Map<ULong, UInt>,
    val missing: Boolean,
)

fun observe(doc: YrsDoc): Observed {
    val text = rootText(doc)
    try {
        return withTxn(doc) { tx ->
            Observed(text.getString(tx), stateMap(tx), tx.transactionHasMissingUpdates())
        }
    } finally {
        text.close()
    }
}

/** Does [sv] know everything [deps] names? */
fun dominates(sv: Map<ULong, UInt>, deps: Map<ULong, UInt>): Boolean =
    deps.all { (client, clock) -> (sv[client] ?: 0u) >= clock }

/**
 * Hex, for the byte level assertions and for the vectors printed for the
 * other core to compare against.
 */
fun hex(bytes: ByteArray): String =
    bytes.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }

/**
 * The binding speaks `List<UByte>` where the Rust side speaks `Vec<u8>`; every
 * byte string in this tier is a [ByteArray], and these two are the seam.
 */
fun List<UByte>.asByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }

fun ByteArray.asUByteList(): List<UByte> = map { it.toUByte() }

/**
 * A document and its root text, closed together.
 *
 * There is no counterpart in `helpers.rs`: Rust drops a `YrsDoc` and its
 * `Arc<YrsText>` on its own, while every object the Kotlin binding hands back
 * holds a Rust pointer until `close()`. The P-tests therefore hold their peers
 * in `use {}` blocks rather than letting them fall out of scope — a leaked
 * document keeps a client id alive and the leak test sees it.
 *
 * Each method below is one [withTxn] and nothing else; anything that needs two
 * operations in ONE transaction opens it with [withTxn] directly.
 */
class Peer(val client: ULong?, val skipGc: Boolean = false) : AutoCloseable {
    /** A null [client] means the core draws the id, and [skipGc] does not apply. */
    val doc: YrsDoc = if (client == null) YrsDoc() else docWith(client, skipGc)
    val text: YrsText = rootText(doc)

    fun <R> transact(f: (YrsTransaction) -> R): R = withTxn(doc, f)

    fun observe(): Observed = transact { tx ->
        Observed(text.getString(tx), stateMap(tx), tx.transactionHasMissingUpdates())
    }

    fun stateVector(): ByteArray = transact { it.transactionStateVector().asByteArray() }

    fun apply(update: ByteArray) {
        transact { it.transactionApplyUpdate(update.asUByteList()) }
    }

    fun diffAgainst(sv: ByteArray): ByteArray =
        transact { doc.encodeDiffV1(it, sv.asUByteList()).asByteArray() }

    fun snapshot(): ByteArray = transact { it.transactionEncodeStateAsUpdate().asByteArray() }

    fun insert(offset: UInt, chunk: String) {
        transact { text.insert(it, offset, chunk) }
    }

    fun removeRange(offset: UInt, len: UInt) {
        transact { text.removeRange(it, offset, len) }
    }

    fun render(): String = transact { text.getString(it) }

    fun length(): UInt = transact { text.length(it) }

    override fun close() {
        text.close()
        doc.close()
    }
}
