package ai.kidgloves.ykt

/**
 * The retired decoder, as a model: the Kotlin side of P6.
 *
 * yrs 0.18's `DecoderV1::read_client` read a client id into a `u32`, so every
 * id at or above 2^32 arrived under a different, scrambled author and the
 * document forked silently (fixed by the 53 bit client ids in
 * 0.26).
 *
 * The Rust tier can run the retired core itself — `yrs 0.18.2` is a
 * dev-dependency beside the pinned 0.27.4 — and pins this model against it on
 * every case. Nothing equivalent is on the JVM: there is one compiled core
 * behind the binding and it is the current one. So on this side the table
 * below is the statement of record, and it is the SAME table, row for row and
 * in the same order, that `p6_retired_decoder.rs` measured with 0.18.2 and
 * that the same property states against pycrdt. A row that disagrees across
 * the harnesses is the finding.
 */

/**
 * Every id this tier pins, and the author yrs 0.18.2 credits an update from
 * it to. Measured with 0.18.2 itself, not derived.
 *
 * The first five rows and the tenth are the corner set; the last four are ids
 * observed in the wild, two of which forked a real document.
 */
val RETIRED_DECODER_TABLE: List<Pair<ULong, ULong>> = listOf(
    1uL to 1uL,
    2_147_483_647uL to 2_147_483_647uL,
    2_147_483_648uL to 2_147_483_648uL,
    4_294_967_295uL to 4_294_967_295uL,
    4_294_967_296uL to 0uL,
    4_294_967_297uL to 1uL,
    8_589_934_592uL to 0uL,
    1_099_511_627_776uL to 256uL,
    281_474_976_710_656uL to 65_536uL,
    9_007_199_254_740_991uL to 4_294_967_295uL,
    967_714_667_641_833uL to 2_701_360_105uL,
    4_792_597_679_421_530uL to 2_587_606_746uL,
    6_968_031_897_510_372uL to 1_511_580_132uL,
    6_009_215_146_349_235uL to 1_849_515_003uL,
)

/**
 * A model of yrs 0.18's `read_client`: a u32 accumulator fed 7 bit pieces,
 * each shifted by `shift % 32` and masked back to 32 bits. Every piece above
 * the 32nd bit wraps around and lands on top of bits already written, which is
 * why the result is not merely truncated but scrambled.
 *
 * The accumulator is a [UInt], so the mask the Rust model spells out is what
 * the type already does; the result widens to [ULong] because that is the
 * domain a client id lives in.
 */
fun legacyReadClient(varint: ByteArray): ULong {
    var result = 0u
    var shift = 0
    for (byte in varint) {
        val piece = (byte.toInt() and 0x7F).toUInt()
        result = result or (piece shl (shift % 32))
        if (byte.toInt() and 0x80 == 0) break
        shift += 7
    }
    return result.toULong()
}

/**
 * lib0 V1 variable length encoding of a u64, which is how a client id goes on
 * the wire, then and now.
 */
fun writeVarU64(value: ULong): ByteArray {
    var v = value
    val out = ArrayList<Byte>()
    while (true) {
        val byte = (v and 0x7FuL).toByte()
        v = v shr 7
        if (v == 0uL) {
            out.add(byte)
            return out.toByteArray()
        }
        out.add((byte.toInt() or 0x80).toByte())
    }
}

/**
 * What the retired decoder makes of [id]: encode it the way the wire does,
 * then read it back the way yrs 0.18 did.
 *
 * P8's negative builds its session peer under this, rather than deriving the
 * same arithmetic a second time — a second copy could be "fixed" into a wrong
 * model of the bug (a plain `% 2**32`) without anything noticing.
 */
fun modelled(id: ULong): ULong = legacyReadClient(writeVarU64(id))
