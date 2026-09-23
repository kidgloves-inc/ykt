package ai.kidgloves.ykt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.take
import kotlin.random.Random

/**
 * P7 — no panic across the FFI, in Kotlin.
 *
 * The mirror of kidgloves-inc/yswift's `lib/src/proptests/p7_no_panic.rs`, and
 * of the same property as stated against pycrdt.
 * Every `[Throws]` entry in the UDL that takes bytes has to answer arbitrary
 * bytes with an error, and a rejected update has to leave the document a caller
 * was holding exactly as it was: the same text, the same state vector.
 *
 * ## The mechanism, and why it is not a `try`/`catch`
 *
 * The shared library these tests load is built with `panic = "abort"` —
 * `ykt/Cargo.toml`'s `[profile.release]` restates the fork's own
 * (`yswift/lib/Cargo.toml:6-11`), and `build.gradle.kts` points
 * `jna.library.path` at `target/release`, which is what `cargo build --release`
 * produces. A Rust panic anywhere under one of these calls therefore does not
 * unwind into a Kotlin exception; it calls `abort()` and the JVM dies with
 * SIGABRT. Rust's own P7 can use `catch_unwind` only because `cargo test`
 * builds under the unwinding test profile (`p7_no_panic.rs:4-7`); this side has
 * no such profile, and neither does an Android app's release build.
 *
 * So every probe runs in a CHILD JVM, spawned by [ChildJvm] as
 * `ProcessBuilder(javaBin, "-Djna.library.path=<the test's own>", "-cp",
 * System.getProperty("java.class.path"), "ai.kidgloves.ykt.Probe", mode,
 * args...)` with `javaBin` taken from `ProcessHandle.current().info().command()`,
 * stdout and stderr captured, and a 60 second timeout whose expiry is a failure
 * naming the probe. The child runs one probe, or one batch of inputs against
 * one entry point, and prints `answered` (the call returned, or threw one of
 * the binding's own exceptions) or `raised <ExceptionClass>` (any other JVM
 * exception — a failure) per input, then `done`, then exits 0. The parent
 * asserts exit code 0, the verdict lines, and `done` last; exit code 134 is
 * SIGABRT, and the message for it names the input the child never got to
 * report. This is the arrangement the pycrdt statement of P7 uses — a child
 * under `subprocess.run`, verdict per input, `done` last — for the same
 * reason: "the process survived" cannot be observed from inside the process.
 *
 * ## Batching, and the seeds
 *
 * 256 child JVMs per property would cost minutes, so the parent draws the
 * inputs and hands a whole batch to ONE child, over stdin rather than argv so
 * that half a megabyte of hex cannot hit `E2BIG`. Every batch is drawn from a
 * named seed (702) and the seed is in the test
 * name, so a failure reproduces.
 *
 * ## What is NOT driven with arbitrary bytes, and why
 *
 * `transactionApplyUpdate` is offered only truncations of a real update, and
 * that is a finding rather than an oversight — `p7_no_panic.rs:79-89, 218-226`
 * carries it in full. yrs 0.27.4 reads a string out of an update with
 * `std::str::from_utf8_unchecked` and never validates it, so arbitrary bytes
 * reach `char::from_u32_unchecked` with a value that is not a character. That
 * is undefined behaviour; it aborts rather than unwinding, and no test can
 * survive it. [P7_INVALID_UTF8_CONTENT] below is the one input of that class
 * this file drives, deliberately, as a pinned abort: it does not test that the
 * binding is safe, it pins that it is not.
 *
 * The allocation bomb is out of scope for the same reason it is out of scope in
 * Rust ([MAX_DECLARED_CLIENTS], `p7_no_panic.rs:165-179`): a state vector whose
 * leading count is in the plausible band asks for gigabytes, the reservation
 * SUCCEEDS on an overcommitting Linux host, and the property would pass for a
 * reason having nothing to do with the binding — while on a phone the same
 * input is a jetsam kill. Generated inputs are filtered by
 * [withinTheAllocationBound]; the two fixed bombs the pycrdt tier pins are
 * driven anyway, because a fixed input is an assertion about one known case
 * rather than a claim about a class.
 */
class P7NoPanicSpec : StringSpec({

    "arbitrary bytes are answered not unwound by the two encoders (256 cases, RandomSource seed 702)" {
        val inputs = arbitraryBytes(cases = 256, seed = 702).map { hex(it) }
        for (mode in listOf("diff", "sv")) {
            ChildJvm.run(mode, stdin = inputs)
                .requireEveryInputAnswered(encoderName(mode), inputs)
        }
    }

    "a truncated update is answered by transactionApplyUpdate on a fresh document" {
        val inputs = truncations().map { hex(it) }
        inputs.size shouldBe SEED_UPDATE.size + 1
        ChildJvm.run("apply", stdin = inputs)
            .requireEveryInputAnswered("transactionApplyUpdate", inputs)
    }

    "a truncated update that is rejected leaves a seeded document alone" {
        val inputs = truncations().map { hex(it) }
        val result = ChildJvm.run("seeded", stdin = inputs)
        result.requireEveryInputAnswered("transactionApplyUpdate (seeded)", inputs)
        result.verdicts.forEachIndexed { i, line -> assertUnchangedIfRejected(inputs[i], line) }
    }

    "a damaged state vector is answered not unwound by the two encoders (256 mutations, Random(702))" {
        val inputs = damaged(VALID_STATE_VECTOR, cases = 256, seed = 702).map { hex(it) }
        for (mode in listOf("diff", "sv")) {
            ChildJvm.run(mode, stdin = inputs)
                .requireEveryInputAnswered(encoderName(mode), inputs)
        }
    }

    "an update offered where a state vector belongs is answered (256 mutations, Random(702))" {
        val inputs = damaged(SEED_UPDATE, cases = 256, seed = 702).map { hex(it) }
        for (mode in listOf("diff", "sv")) {
            ChildJvm.run(mode, stdin = inputs)
                .requireEveryInputAnswered(encoderName(mode), inputs)
        }
    }

    // ---- the pycrdt tier's fixed inputs -----------------------------------

    /**
     * The one input this file expects to kill the process, and the test is
     * strict in that direction: it FAILS when the child survives, so whoever
     * fixes the core or the binding has to come here and flip it.
     *
     * `01010100040101700268ef00` is one struct — client 1, clock 0, ref 4
     * (String), root key "p", content `68 ef`. `ef` is a UTF-8 lead byte with
     * no continuation, and yrs 0.27.4 hands the bytes to
     * `std::str::from_utf8_unchecked` without looking
     * (`p7_no_panic.rs:79-89`, `:218-226`). The result here is not even a
     * panic: the child dies on SIGSEGV inside `libykt.so`, which the JVM's
     * fatal error handler turns into the same abort. The pycrdt tier pins the
     * same input as a strict expected failure.
     */
    "the string content that kills apply_update still kills the process (pinned defect)" {
        val result = ChildJvm.run("apply", listOf(P7_INVALID_UTF8_CONTENT))
        withClue(result) {
            result.aborted shouldBe true
        }
    }

    /**
     * Two state vectors whose leading length prefix declares far more than the
     * bytes carry. The pycrdt tier pins these as `raised ValueError` from pycrdt
     * 0.14.4 onward; the fork on yrs 0.27.4 answers them — `try_reserve` in
     * `StateVector::decode_v1` fails and the binding maps it to a
     * `CodingException.DecodingException`.
     */
    "a length prefix bomb in a state vector is answered by both encoders" {
        val inputs = P7_LENGTH_PREFIX_BOMBS
        for (mode in listOf("diff", "sv")) {
            ChildJvm.run(mode, inputs).requireEveryInputAnswered(encoderName(mode), inputs)
        }
    }

    "honest garbage is answered by transactionApplyUpdate" {
        ChildJvm.run("apply", P7_HONEST_GARBAGE)
            .requireEveryInputAnswered("transactionApplyUpdate", P7_HONEST_GARBAGE)
    }

    "honest garbage leaves text and state vector unchanged" {
        val result = ChildJvm.run("seeded", P7_HONEST_GARBAGE)
        result.requireEveryInputAnswered("transactionApplyUpdate (seeded)", P7_HONEST_GARBAGE)
        result.verdicts.forEachIndexed { i, line ->
            assertUnchangedIfRejected(P7_HONEST_GARBAGE[i], line)
        }
    }
})

// ---------------------------------------------------------------- the inputs

/** The invalid-UTF-8 String content, the same bytes the pycrdt tier pins. */
private const val P7_INVALID_UTF8_CONTENT = "01010100040101700268ef00"

/** The length prefix bombs, the same bytes the pycrdt tier pins. */
private val P7_LENGTH_PREFIX_BOMBS = listOf("d9bfd18ee9443f30", "ffffffffff0f")

/** The honest garbage, the same bytes the pycrdt tier pins. */
private val P7_HONEST_GARBAGE = listOf("", "00", "ffffffffffffffffffff", "0101")

/** The 27 bytes `probe::seed_update()` builds, as bytes. */
private val SEED_UPDATE: ByteArray = Probe.unhex(Probe.SEED_UPDATE_HEX)

/**
 * A valid encoded state vector, to be damaged: the state vector of a document
 * that has taken the seed update, which is what `valid_state_vector()` returns
 * (`p7_no_panic.rs:204-209`). Taken in THIS process, because the seed is known
 * good and nothing here can abort on it.
 */
private val VALID_STATE_VECTOR: ByteArray = Peer(null).use { peer ->
    peer.apply(SEED_UPDATE)
    peer.stateVector()
}

private fun encoderName(mode: String) =
    if (mode == "diff") "encodeDiffV1" else "transactionEncodeStateAsUpdateFromSv"

// ------------------------------------------------------------- the generators

/**
 * How many clients a generated state vector may claim to hold, and the reason
 * the bound exists rather than being squeamishness: see the class comment and
 * `p7_no_panic.rs:165-180`.
 */
private const val MAX_DECLARED_CLIENTS: Long = 1024

/**
 * The number of clients a state vector's leading varint declares, read the way
 * `StateVector::decode_v1` reads it: 7 bit pieces accumulated into a u32.
 * `null` when the varint runs off the end of the input, which the decoder
 * answers with an error before it allocates anything
 * (`p7_no_panic.rs:152-163`).
 */
private fun declaredClientCount(bytes: ByteArray): Long? {
    var result = 0L
    var shift = 0
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        result = result or (((v and 0x7f).toLong() shl (shift % 32)) and 0xFFFFFFFFL)
        if (v and 0x80 == 0) return result and 0xFFFFFFFFL
        shift += 7
    }
    return null
}

private fun withinTheAllocationBound(bytes: ByteArray): Boolean =
    declaredClientCount(bytes)?.let { it <= MAX_DECLARED_CLIENTS } ?: true

/** The width of the leading varint, or `null` when it runs off the end. */
private fun leadingVarintWidth(bytes: ByteArray): Int? {
    val i = bytes.indexOfFirst { (it.toInt() and 0x80) == 0 }
    return if (i < 0) null else i + 1
}

/** The ways a valid encoding is broken here (`p7_no_panic.rs:90-103`). */
private sealed class Mutation {
    data class BitFlip(val index: Int, val bit: Int) : Mutation()
    data class Truncate(val at: Int) : Mutation()
    data class Append(val junk: ByteArray) : Mutation()
    data object OverstatedCount : Mutation()
}

private fun mutate(base: ByteArray, mutation: Mutation): ByteArray = when (mutation) {
    is Mutation.BitFlip -> base.copyOf().also {
        if (it.isNotEmpty()) {
            val i = mutation.index % it.size
            it[i] = (it[i].toInt() xor (1 shl mutation.bit)).toByte()
        }
    }
    is Mutation.Truncate -> base.copyOf(mutation.at % (base.size + 1))
    is Mutation.Append -> base + mutation.junk
    // Replace the leading count, whatever its width, with 1024: the decoder is
    // told to read that many clients out of an encoding that holds one.
    Mutation.OverstatedCount -> {
        val width = leadingVarintWidth(base) ?: 0
        byteArrayOf(0x80.toByte(), 0x08) + base.copyOfRange(width.coerceAtMost(base.size), base.size)
    }
}

/**
 * `cases` mutations of `base` that stay inside the allocation bound. Filtering
 * here rather than after the fact is what `damaged()` does in Rust
 * (`p7_no_panic.rs:186-201`): a bit flip on a leading count byte is rare, and
 * it is exactly what turns a one-client state vector into a claim of millions.
 */
private fun damaged(base: ByteArray, cases: Int, seed: Int): List<ByteArray> {
    val random = Random(seed)
    val kept = ArrayList<ByteArray>(cases)
    var drawn = 0
    while (kept.size < cases) {
        check(drawn++ < cases * 100) { "the allocation-bound filter rejected almost everything" }
        val mutation = when (random.nextInt(4)) {
            0 -> Mutation.BitFlip(random.nextInt(256), random.nextInt(8))
            1 -> Mutation.Truncate(random.nextInt(256))
            2 -> Mutation.Append(ByteArray(random.nextInt(32)) { random.nextInt(256).toByte() })
            else -> Mutation.OverstatedCount
        }
        val bytes = mutate(base, mutation)
        if (withinTheAllocationBound(bytes)) kept.add(bytes)
    }
    return kept
}

/** Arbitrary bytes, 0..1023 of them, inside the allocation bound. */
private fun arbitraryBytes(cases: Int, seed: Int): List<ByteArray> =
    Arb.byteArray(Arb.int(0..1023), Arb.byte())
        .take(cases * 4, RandomSource.seeded(seed.toLong()))
        .filter { withinTheAllocationBound(it) }
        .take(cases)
        .toList()
        .also { check(it.size == cases) { "only ${it.size} of $cases draws cleared the bound" } }

/** Every truncation of the seed update, `at` in 0..27 (`p7_no_panic.rs:244`). */
private fun truncations(): List<ByteArray> =
    (0..SEED_UPDATE.size).map { SEED_UPDATE.copyOf(it) }

// ------------------------------------------------------------- the assertions

/**
 * The second half of the property: a rejected update leaves the document
 * rendering the same text and reporting the same state vector
 * (`probe.rs:92-98`, `p7_no_panic.rs:252-262`). The child prints
 * `<verdict> <applied|rejected> <textBefore> <svBefore> <textAfter> <svAfter>`.
 */
private fun assertUnchangedIfRejected(input: String, line: String) {
    val f = line.split(" ")
    check(f.size == 6) { "malformed seeded line: $line" }
    if (f[1] != "rejected") return
    if (f[2] != f[4] || f[3] != f[5]) {
        throw AssertionError(
            "a rejected update changed the document: input $input, " +
                "text ${f[2]} -> ${f[4]}, state vector ${f[3]} -> ${f[5]}",
        )
    }
}

private inline fun withClue(result: ChildResult, block: () -> Unit) {
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("${e.message}\nchild: $result", e)
    }
}
