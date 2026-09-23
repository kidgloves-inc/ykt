package ai.kidgloves.ykt

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.TreeMap

/**
 * The clock ranges an encoded update carries, per client: what it inserts and
 * what it deletes.
 *
 * `helpers.rs` gets this from `Update::decode_v1` — the one place the Rust
 * tier reaches past the binding, because the binding exposes no view of an
 * update's authors or clocks. Kotlin has no such door: yrs is behind the FFI
 * and nothing re-exports its reader. So the independent oracle here is a port
 * of a V1 struct-section reader (a byte reader, an Any skipper, a content
 * length table and a span parser), which is an independent statement of the
 * same wire format beside yrs's own.
 *
 * Where a reader that only credits authors keeps the highest clock end per
 * client, this keeps the ranges, because [gappedClient] and [unreachableClient]
 * ask about holes that a maximum cannot see. A client is a KEY here exactly
 * when the struct section NAMES it (a client is credited the moment a struct
 * is filed under it), so a named client whose
 * blocks are all zero length maps to an empty range list rather than vanishing
 * — which is what keeps [updateIsOnlyFrom] faithful. Stored ranges are never
 * empty, and are half open: `first` is the first clock, [endExclusive] the one
 * past the last, matching Rust's `Range<u32>`.
 */
class UpdateSpan(
    val inserts: Map<ULong, List<UIntRange>>,
    val deletes: Map<ULong, List<UIntRange>>,
) {
    /** The lowest clock this update inserts for [client], if any. */
    fun firstInsertClock(client: ULong): UInt? = inserts[client]?.minOfOrNull { it.first }

    /**
     * A client whose FIRST block in this update sits above what [sv] has
     * seen: the receiver cannot reach the update's own starting point for
     * that client, so nothing of it can be integrated.
     */
    fun unreachableClient(sv: Map<ULong, UInt>): ULong? =
        inserts.entries.firstNotNullOfOrNull { (client, ranges) ->
            val first = ranges.minOfOrNull { it.first }
            if (first != null && (sv[client] ?: 0u) < first) client else null
        }

    /**
     * A client whose blocks this update cannot lay down contiguously on top
     * of [sv]: either it starts above what the receiver has, or it has a hole
     * of its own.
     *
     * An update is not described by its author's state vector alone. A peer
     * whose own store holds blocks past a Skip re-encodes them in its next
     * diff while reporting the skip's start as its clock, so an update can
     * carry a hole its composer's state vector does not mention.
     */
    fun gappedClient(sv: Map<ULong, UInt>): ULong? =
        inserts.entries.firstNotNullOfOrNull { (client, ranges) ->
            var reachable = sv[client] ?: 0u
            var gapped: ULong? = null
            for (range in ranges.sortedBy { it.first }) {
                if (range.first > reachable) {
                    gapped = client
                    break
                }
                if (range.endExclusive > reachable) reachable = range.endExclusive
            }
            gapped
        }

    /**
     * Every id this update INSERTS is at or below the clock [sv] records for
     * its client: the receiver holds the blocks, whatever it makes of the
     * delete set. The delete set is kept out of this on purpose: a diff
     * carries the composer's whole delete set, which on yrs 0.27.4 can name
     * ids above the composer's own state vector, so a receiver that has
     * integrated everything integrable still would not "cover" it.
     */
    fun insertsCoveredBy(sv: Map<ULong, UInt>): Boolean = coveredBy(inserts, sv)

    /**
     * Every id this update names, insert or delete, is at or below the clock
     * [sv] records for its client: the document has the update, it is not
     * waiting for any part of it.
     */
    fun coveredBy(sv: Map<ULong, UInt>): Boolean = coveredBy(inserts, sv) && coveredBy(deletes, sv)

    override fun toString(): String = "UpdateSpan(inserts=$inserts, deletes=$deletes)"

    companion object {
        /**
         * Decode a yrs V1 update far enough to know which (client, clock)
         * ranges it supplies and deletes — the port of the reader's span
         * parser.
         *
         * Throws [IllegalArgumentException] on bytes that do not decode,
         * which includes an Any value nested at or past [MAX_ANY_DEPTH].
         */
        fun decode(bytes: ByteArray): UpdateSpan {
            val r = Reader(bytes)
            val inserts = TreeMap<ULong, MutableList<UIntRange>>()
            var clientsLeft = r.varUint()
            while (clientsLeft > 0uL) {
                clientsLeft--
                var structsLeft = r.varUint()
                val client = r.varUint()
                var clock = clockOf(r.varUint())
                while (structsLeft > 0uL) {
                    structsLeft--
                    val info = r.u8()
                    val ref = info and 0x1F
                    if (ref == 0 || ref == 10) { // GC / Skip: bare length
                        val start = clock
                        clock = advance(clock, r.varUint())
                        // GC supplies the range; Skip declares a hole and names nobody.
                        if (ref == 0) record(inserts, client, start, clock)
                        continue
                    }
                    if (info and 0x80 != 0) { // left origin
                        r.varUint()
                        r.varUint()
                    }
                    if (info and 0x40 != 0) { // right origin
                        r.varUint()
                        r.varUint()
                    }
                    if (info and 0xC0 == 0) {
                        if (r.varUint() == 1uL) {
                            r.varString() // root key parent
                        } else {
                            r.varUint()
                            r.varUint() // parent ID
                        }
                        if (info and 0x20 != 0) r.varString() // parentSub
                    }
                    val start = clock
                    clock = advance(clock, contentLen(r, ref))
                    record(inserts, client, start, clock)
                }
            }
            val deletes = TreeMap<ULong, MutableList<UIntRange>>()
            var deleteClientsLeft = r.varUint()
            while (deleteClientsLeft > 0uL) {
                deleteClientsLeft--
                val client = r.varUint()
                var rangesLeft = r.varUint()
                while (rangesLeft > 0uL) {
                    rangesLeft--
                    val start = clockOf(r.varUint())
                    record(deletes, client, start, advance(start, r.varUint()))
                }
            }
            return UpdateSpan(inserts, deletes)
        }
    }
}

/**
 * The format admits arbitrary nesting and the bytes are untrusted, so without
 * a bound a few thousand nested arrays overflow the JVM's call stack with a
 * StackOverflowError that [updateCovered] and [updateIsOnlyFrom] do not catch
 * — an escaping throwable where the documented behavior is "bytes that will
 * not decode are refused". Matches the depth-64 bound other readers of the
 * same bytes hold, so this one refuses exactly what they refuse.
 */
const val MAX_ANY_DEPTH: Int = 64

/** The end one past the last clock of a (never empty) stored range. */
private val UIntRange.endExclusive: UInt get() = last + 1u

private val MAX_CLOCK: ULong = UInt.MAX_VALUE.toULong()

private fun coveredBy(ranges: Map<ULong, List<UIntRange>>, sv: Map<ULong, UInt>): Boolean =
    ranges.all { (client, rs) -> rs.all { it.endExclusive <= (sv[client] ?: 0u) } }

/**
 * Name [client] in [into] and, when the block is not zero length, record its
 * range. The key goes in either way: a client is credited the moment a struct
 * is filed under it, and that is the set [updateIsOnlyFrom] reads.
 */
private fun record(
    into: TreeMap<ULong, MutableList<UIntRange>>,
    client: ULong,
    start: UInt,
    end: UInt,
) {
    val ranges = into.getOrPut(client) { mutableListOf() }
    if (end > start) ranges.add(start until end)
}

/**
 * yrs clocks are 32 bit, as is `Range<u32>` in the Rust harness, so a clock
 * this wide is not an update any core wrote. A reader with unbounded integers
 * would carry it; this refuses it, which the callers read as "does not decode".
 */
private fun clockOf(value: ULong): UInt {
    require(value <= MAX_CLOCK) { "clock beyond u32: $value" }
    return value.toUInt()
}

private fun advance(clock: UInt, delta: ULong): UInt {
    require(delta <= MAX_CLOCK) { "clock length beyond u32: $delta" }
    val next = clock.toULong() + delta
    require(next <= MAX_CLOCK) { "clock beyond u32: $next" }
    return next.toUInt()
}

/**
 * Consume one struct's content, returning its clock length: the port of the
 * reader's content length table.
 */
private fun contentLen(r: Reader, ref: Int): ULong = when (ref) {
    1 -> r.varUint() // Deleted
    2 -> { // JSON
        val n = r.varUint()
        var left = n
        while (left > 0uL) {
            left--
            r.varString()
        }
        n
    }
    3 -> { // Binary
        r.skipBuf()
        1uL
    }
    4 -> utf16Units(r.varString()).toULong() // String: UTF-16 code units
    5 -> { // Embed (JSON as var-string in v1)
        r.varString()
        1uL
    }
    6 -> { // Format
        r.varString()
        r.varString()
        1uL
    }
    7 -> { // Type
        val typeRef = r.varUint()
        if (typeRef == 3uL || typeRef == 5uL) r.varString() // XmlElement / XmlHook carry a name
        1uL
    }
    8 -> { // Any
        val n = r.varUint()
        var left = n
        while (left > 0uL) {
            left--
            skipAny(r, 0)
        }
        n
    }
    9 -> { // Doc
        r.varString()
        skipAny(r, 0)
        1uL
    }
    else -> throw IllegalArgumentException("unknown content ref $ref")
}

/**
 * Walk one lib0 Any value (content we never inspect, only step over): the port
 * of the reader's Any skipper. The depth is checked on entry, so a value nested a
 * hundred or five thousand deep is REFUSED at 64 rather than recursed into.
 */
private fun skipAny(r: Reader, depth: Int) {
    require(depth < MAX_ANY_DEPTH) { "Any value nested too deep" }
    when (val t = r.u8()) {
        127, 126, 121, 120 -> return // undefined / null / false / true
        125 -> r.varUint()
        124 -> r.skip(4)
        123, 122 -> r.skip(8)
        119 -> r.varString()
        118 -> { // map
            var left = r.varUint()
            while (left > 0uL) {
                left--
                r.varString()
                skipAny(r, depth + 1)
            }
        }
        117 -> { // array
            var left = r.varUint()
            while (left > 0uL) {
                left--
                skipAny(r, depth + 1)
            }
        }
        116 -> r.skipBuf()
        else -> throw IllegalArgumentException("unknown Any tag $t")
    }
}

/**
 * lib0 V1 reads, over a fixed byte string: the port of the reader's byte
 * reader. Every way of running off the end, or of reading bytes that are not
 * what they claim — an index out of range, a bad value, bytes that are not
 * UTF-8 — is an [IllegalArgumentException], which its callers catch as one.
 */
private class Reader(private val data: ByteArray) {
    private var pos: Int = 0

    fun u8(): Int {
        require(pos < data.size) { "truncated update at byte $pos" }
        return data[pos++].toInt() and 0xFF
    }

    /**
     * A lib0 varuint. An arbitrary-precision reader accumulates without a
     * width; this stops at 64 bits — no core emits a wider one.
     */
    fun varUint(): ULong {
        var shift = 0
        var value = 0uL
        while (true) {
            val b = u8()
            val piece = (b and 0x7F).toULong()
            require(shift < 64) { "varint wider than 64 bits" }
            require(shift == 0 || (piece shr (64 - shift)) == 0uL) { "varint wider than 64 bits" }
            value = value or (piece shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
        }
    }

    fun varString(): String {
        val n = count("string")
        val s = decodeUtf8(data, pos, n)
        pos += n
        return s
    }

    /** `_Reader.var_buf`, whose result every caller discards. */
    fun skipBuf() {
        pos += count("buffer")
    }

    /** Step over a fixed-width value without a bounds check; the next read catches an overrun. */
    fun skip(n: Int) {
        pos += n
    }

    private fun count(what: String): Int {
        val n = varUint()
        val remaining = (data.size - pos).coerceAtLeast(0)
        require(n <= remaining.toULong()) { "truncated $what" }
        return n.toInt()
    }
}

/**
 * Strictly, so that bytes which are not UTF-8 are refused rather than repaired
 * into replacement characters: a String block's clock length is the UTF-16
 * length of what it decodes to, and a repaired decode would report a length
 * nobody wrote.
 */
private fun decodeUtf8(data: ByteArray, from: Int, len: Int): String =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, from, len))
            .toString()
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("invalid utf-8 in var string", e)
    }

/**
 * Decode a yrs state vector: varuint count, then (client, clock) pairs.
 */
fun readStateVector(sv: ByteArray): Map<ULong, UInt> {
    val r = Reader(sv)
    val out = TreeMap<ULong, UInt>()
    var left = r.varUint()
    while (left > 0uL) {
        left--
        out[r.varUint()] = clockOf(r.varUint())
    }
    return out
}

/**
 * True iff every block and delete-set range the update carries is covered by
 * the state vector — i.e. the update is integrated in (or was already part of)
 * the doc that produced the vector. False on any parse failure: an update
 * that cannot be verified must never be counted as integrated.
 */
fun updateCovered(sv: ByteArray, update: ByteArray): Boolean =
    try {
        UpdateSpan.decode(update).coveredBy(readStateVector(sv))
    } catch (e: IllegalArgumentException) {
        false
    }

/**
 * True iff every client the update's struct section names is [client]: a
 * forwarding server's client-id gate (it admits an update only when every
 * block in its struct section is filed under the client id the server
 * assigned to the sender), stated here over the same bytes as P8's Rust
 * `update_is_only_from`.
 *
 * Two shapes pass and are worth naming. An update whose struct section names
 * nobody is admitted, and a delete-only update is the ordinary one: a delete
 * set names the AUTHORS of the text being removed, not the author of the
 * removal, so refusing on it would refuse a session for deleting text it can
 * see. A Skip is the other one: it declares a hole in someone else's clock
 * range and supplies no content, so it names nobody either. A garbage
 * collected block, by contrast, DOES name the client that wrote it, and the
 * gate reads it — P8's `GC_BLOCK_UPDATE`, `01 01 07 00 00 05 00`, names client
 * 7, where its `SKIP_UPDATE`, `01 01 0b 00 0a 04 00`, names nobody.
 *
 * And bytes that will not decode are refused rather than admitted — an update
 * nobody can read cannot be shown to be this session's.
 */
fun updateIsOnlyFrom(update: ByteArray, client: ULong): Boolean =
    try {
        UpdateSpan.decode(update).inserts.keys.all { it == client }
    } catch (e: IllegalArgumentException) {
        false
    }
