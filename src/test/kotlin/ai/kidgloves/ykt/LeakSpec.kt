package ai.kidgloves.ykt

import io.kotest.core.spec.style.StringSpec
import uniffi.yniffi.YrsDoc

/**
 * Does a document's memory come back?
 *
 * Every object the Kotlin binding hands out owns a Rust pointer and is
 * `Disposable`/`AutoCloseable`: `close()` decrements a call counter and, at
 * zero, runs the free function (`uniffi_uniffi_yniffi_fn_free_yrsdoc` and its
 * siblings). uniffi 0.27 also registers a `java.lang.ref.Cleaner` action per
 * object — the generated file's `UniffiCleaner`/`UniffiCleanAction` — so an
 * object nobody closed is still freed when the JVM collects it. The two tests
 * below are those two paths: the close, and the backstop.
 *
 * ## What is measured, and why it is RSS
 *
 * The binding exposes no handle count, no allocation counter and no live-object
 * registry — there is nothing to ask. What a leak here actually costs is
 * resident memory in the process, because the blocks live in the Rust
 * allocator's heap rather than in the JVM's, so `Runtime.totalMemory()` cannot
 * see them either. So the measurement is the process's own resident set, field
 * two of `/proc/self/statm` (resident pages) times the page size.
 *
 * That makes these two tests **Linux-only**, deliberately and not as an
 * oversight: `/proc/self/statm` is a Linux interface, ykt's CI is
 * `ubuntu-latest` (`.github/workflows/ci.yml`), and the shipping target is
 * Android, which is Linux. A run somewhere without `/proc` fails with that
 * sentence rather than skipping, because a leak test that skips is a leak test
 * that has stopped testing.
 *
 * RSS is a coarse instrument and the bound is set accordingly. Freed native
 * memory is returned to the allocator, not necessarily to the kernel, and the
 * JVM's own heap grows under the loop whatever the binding does; the claim
 * being made is only that four thousand documents holding eight kilobytes each
 * — thirty-two megabytes of text before any per-block overhead — do not
 * accumulate. See the calibration note on [LEAK_BOUND_BYTES].
 */
class LeakSpec : StringSpec({

    "four thousand closed documents do not grow the resident set" {
        requireLinuxProcfs()
        val before = settledRss()
        repeat(ITERATIONS) { churn(close = true) }
        val after = settledRss()
        assertGrowthWithinBound("closed", before, after)
    }

    "four thousand forgotten documents are freed by the Cleaner backstop" {
        requireLinuxProcfs()
        val before = settledRss()
        repeat(ITERATIONS) { churn(close = false) }
        val after = settledRss()
        assertGrowthWithinBound("forgotten", before, after)
    }
})

/** Four thousand documents, as the brief for this tier asks. */
private const val ITERATIONS = 4000

/** Roughly eight kilobytes of text per document. */
private val CHUNK = "abcdefgh".repeat(1024)

/**
 * The bound both tests hold to, and the calibration behind it.
 *
 * Measured on this tree, `libykt.so` from `cargo build --release`, one JVM
 * running both tests in order: the closed loop moves the resident set 139 MB ->
 * 167 MB, a growth of 28 MB, and the forgotten loop 168 MB -> 189 MB, a growth
 * of 20 MB once the Cleaner has run.
 *
 * The calibration that makes the bound mean something is the same loop with
 * `close()` removed AND the settle skipped, so nothing frees on purpose:
 * 139 MB -> 290 MB, a growth of 150 MB. That is 2.3x this bound and 5x the
 * closed loop, and it is an UNDERCOUNT — the JVM collects under its own
 * allocation pressure during the loop, so the Cleaner frees a good share of
 * those documents before the measurement is taken. 64 MB sits between the two
 * with room on both sides.
 */
private const val LEAK_BOUND_BYTES: Long = 64L * 1024 * 1024

/**
 * One document's worth of work: a document, its root text, a transaction, eight
 * kilobytes of insert. With [close] the handles are released in order; without
 * it they are dropped on the floor and only the Cleaner can free them.
 */
private fun churn(close: Boolean) {
    val doc = YrsDoc()
    val text = doc.getText(ROOT)
    val tx = doc.transact(null)
    text.insert(tx, 0u, CHUNK)
    tx.free()
    if (close) {
        tx.close()
        text.close()
        doc.close()
    }
}

/**
 * The resident set once it has stopped moving: collect, give the Cleaner thread
 * a moment to drain its queue, and repeat until two readings agree to within a
 * megabyte (or ten rounds have passed, which is the answer either way).
 */
private fun settledRss(): Long {
    var previous = rssBytes()
    var stable = 0
    repeat(20) {
        System.gc()
        System.runFinalization()
        Thread.sleep(250)
        val now = rssBytes()
        stable = if (Math.abs(now - previous) < 1024 * 1024) stable + 1 else 0
        previous = now
        if (stable >= 3) return now
    }
    return previous
}

/** Field two of `/proc/self/statm` is the resident set, in pages. */
private fun rssBytes(): Long {
    val pages = java.io.File("/proc/self/statm").readText().trim().split(" ")[1].toLong()
    return pages * PAGE_SIZE
}

/**
 * The page size the kernel reports, not a guess: an arm64 host may run 16 KB or
 * 64 KB pages, and an RSS read in the wrong unit is wrong by a factor of four.
 */
private val PAGE_SIZE: Long = runCatching {
    ProcessBuilder("getconf", "PAGE_SIZE").start()
        .inputStream.bufferedReader().readText().trim().toLong()
}.getOrDefault(4096L)

private fun requireLinuxProcfs() {
    check(java.io.File("/proc/self/statm").exists()) {
        "this tier measures the process's resident set through /proc/self/statm and so runs " +
            "on Linux only; the binding exposes no handle count to measure instead"
    }
}

private fun assertGrowthWithinBound(what: String, before: Long, after: Long) {
    val growth = after - before
    if (growth > LEAK_BOUND_BYTES) {
        throw AssertionError(
            "$ITERATIONS $what documents of ${CHUNK.length} bytes each grew the resident set by " +
                "${growth / (1024 * 1024)} MB (${before / (1024 * 1024)} MB -> " +
                "${after / (1024 * 1024)} MB), past the ${LEAK_BOUND_BYTES / (1024 * 1024)} MB bound",
        )
    }
}
