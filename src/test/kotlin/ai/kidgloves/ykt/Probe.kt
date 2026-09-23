package ai.kidgloves.ykt

import uniffi.yniffi.CodingException
import uniffi.yniffi.YrsArray
import uniffi.yniffi.YrsArrayEachDelegate
import uniffi.yniffi.YrsArrayObservationDelegate
import uniffi.yniffi.YrsChange
import uniffi.yniffi.YrsDelta
import uniffi.yniffi.YrsDoc
import uniffi.yniffi.YrsDocException
import uniffi.yniffi.YrsMap
import uniffi.yniffi.YrsMapChange
import uniffi.yniffi.YrsMapIteratorDelegate
import uniffi.yniffi.YrsMapKvIteratorDelegate
import uniffi.yniffi.YrsMapObservationDelegate
import uniffi.yniffi.YrsText
import uniffi.yniffi.YrsTextObservationDelegate
import uniffi.yniffi.YrsTransaction
import uniffi.yniffi.YrsCollectionPtr
import uniffi.yniffi.YrsUndoEvent
import uniffi.yniffi.YrsUndoManager
import uniffi.yniffi.YrsUndoException
import uniffi.yniffi.YrsUndoManagerObservationDelegate

/**
 * The child half of the no-panic tier, and the parent-side runner that spawns
 * it.
 *
 * ## Why a child process at all
 *
 * `ykt/Cargo.toml`'s `[profile.release]` restates the fork's own
 * (kidgloves-inc/yswift's `lib/Cargo.toml:6-11`): `panic = "abort"`.
 * The library these tests load, `target/release/libykt.so`, IS that profile —
 * `build.gradle.kts` points `jna.library.path` at `target/release` and the
 * `buildNative` task builds it with `cargo build --release`. So a panic
 * anywhere in the core or in the binding does not unwind into an exception a
 * `try`/`catch` could see: it calls `abort()`, and the whole JVM dies with
 * SIGABRT. The Rust P7 can use `catch_unwind` only because `cargo test` builds
 * under the test profile, which unwinds (`p7_no_panic.rs:4-7`); nothing on this
 * side of the FFI has that option.
 *
 * "The process survived" is therefore the property, and it can only be observed
 * from outside the process. Every probe below runs in a CHILD JVM, spawned by
 * [ChildJvm] with the same `jna.library.path` and the same classpath as the
 * test that spawned it. This mirrors the same property as stated against
 * pycrdt, which reaches the same conclusion for the same reason and runs its
 * child under `subprocess.run`.
 *
 * ## The protocol
 *
 * The child runs ONE probe, or one batch of inputs against one entry point, and
 * prints one verdict line per input:
 *
 *  - `answered` — the call returned, or threw one of the binding's own
 *    exceptions ([CodingException], [YrsDocException], [YrsUndoException]).
 *    Either is a call that came back.
 *  - `raised <ExceptionClass>` — any other JVM exception. A failure: the
 *    binding owes an error of its own, not an `IllegalStateException` or an
 *    `IllegalArgumentException` leaking out of the scaffolding.
 *
 * `done` is printed last, and the child exits 0. A parent that sees exit code
 * 134 (128 + SIGABRT) saw the core panic across the FFI; a parent that sees
 * `done` missing saw it die some other way.
 *
 * Inputs are hex strings, taken from the arguments after the mode, or — when
 * there are none — one per line on stdin, which is how the 256-case batches are
 * passed without a command line long enough to hit `E2BIG`.
 */
object Probe {

    /**
     * A known good update: client 967714667641833 inserting "hello" into the
     * root text, encoded V1. The same 27 bytes `probe::seed_update()` builds
     * (kidgloves-inc/yswift's `lib/src/probe.rs:34-45`), pinned by P4's
     * `SCENARIO_S_U1_HEX`.
     */
    const val SEED_UPDATE_HEX: String = "0101e9f788889a84dc010004010670726f6d70740568656c6c6f00"

    /** The text every probe document holds before it is driven. */
    private const val PRELOAD = "hello"

    // ---------------------------------------------------------------- child

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.firstOrNull() ?: error("usage: Probe <mode> [hex...]")
        val rest = args.drop(1)
        when (mode) {
            "entry" -> {
                val name = rest.firstOrNull() ?: error("usage: Probe entry <name>")
                val probe = ENTRY_POINTS[name] ?: error("no such entry point: $name")
                println(verdict { probe() })
            }
            "diff" -> forEachInput(rest) { bytes -> verdict { encodeDiffV1(bytes) } }
            "sv" -> forEachInput(rest) { bytes -> verdict { encodeStateFromSv(bytes) } }
            "apply" -> forEachInput(rest) { bytes -> verdict { applyUpdate(bytes) } }
            "seeded" -> forEachInput(rest) { bytes -> seededLine(bytes) }
            else -> error("no such mode: $mode")
        }
        println("done")
    }

    private fun forEachInput(args: List<String>, f: (ByteArray) -> String) {
        // A blank line is an input — the empty byte string is one of the cases
        // every mode here is asked about (`Truncate { at: 0 }`, and the first
        // of `P7_HONEST_GARBAGE`) — so the lines are taken as they come. The
        // parent counts verdicts against inputs, and a dropped blank would show
        // up there as a child that answered one fewer than it was given.
        val hexes = if (args.isNotEmpty()) args else System.`in`.bufferedReader().readLines()
        for (h in hexes) println(f(unhex(h.trim())))
    }

    /** The verdict rule, and the only place an exception is classified. */
    private inline fun verdict(f: () -> Unit): String =
        try {
            f()
            "answered"
        } catch (e: CodingException) {
            "answered"
        } catch (e: YrsDocException) {
            "answered"
        } catch (e: YrsUndoException) {
            "answered"
        } catch (e: Throwable) {
            "raised ${e.javaClass.name}"
        }

    // ------------------------------------------------- the three byte-taking
    // entry points, each the Kotlin twin of the `probe` module's function of
    // the same name (`lib/src/probe.rs`).

    /** `YrsDoc.encodeDiffV1` against arbitrary state vector bytes. */
    private fun encodeDiffV1(stateVector: ByteArray) {
        preloaded { doc, text, tx -> doc.encodeDiffV1(tx, stateVector.asUByteList()) }
    }

    /** `YrsTransaction.transactionEncodeStateAsUpdateFromSv`, same preload. */
    private fun encodeStateFromSv(stateVector: ByteArray) {
        preloaded { _, _, tx -> tx.transactionEncodeStateAsUpdateFromSv(stateVector.asUByteList()) }
    }

    /** `YrsTransaction.transactionApplyUpdate` on a fresh document. */
    private fun applyUpdate(update: ByteArray) {
        val doc = YrsDoc()
        try {
            val tx = doc.transact(null)
            try {
                tx.transactionApplyUpdate(update.asUByteList())
            } finally {
                tx.free(); tx.close()
            }
        } finally {
            doc.close()
        }
    }

    /**
     * `apply_update_to_seeded`: apply the known good seed, record what the
     * document renders and reports, apply the candidate, record again. The
     * parent compares the two halves — a rejected update owes the caller a
     * document that has not moved (`probe.rs:100-126`).
     *
     * The line is `<verdict> <applied|rejected> <textBefore> <svBefore>
     * <textAfter> <svAfter>`, texts hex-encoded so no field can carry a space.
     */
    private fun seededLine(candidate: ByteArray): String {
        val doc = YrsDoc()
        try {
            val text = doc.getText(ROOT)
            try {
                val tx = doc.transact(null)
                try {
                    // A malformed seed would make the probe meaningless; callers
                    // pass SEED_UPDATE_HEX, and its result is deliberately dropped.
                    try {
                        tx.transactionApplyUpdate(unhex(SEED_UPDATE_HEX).asUByteList())
                    } catch (e: CodingException) {
                        // ignored, exactly as `let _ =` ignores it in Rust
                    }
                    val textBefore = text.getString(tx)
                    val svBefore = tx.transactionStateVector().asByteArray()

                    var outcome = "applied"
                    var verdict = "answered"
                    try {
                        tx.transactionApplyUpdate(candidate.asUByteList())
                    } catch (e: CodingException) {
                        outcome = "rejected"
                    } catch (e: Throwable) {
                        outcome = "rejected"
                        verdict = "raised ${e.javaClass.name}"
                    }

                    val textAfter = text.getString(tx)
                    val svAfter = tx.transactionStateVector().asByteArray()
                    return listOf(
                        verdict,
                        outcome,
                        hex(textBefore.toByteArray(Charsets.UTF_8)),
                        hex(svBefore),
                        hex(textAfter.toByteArray(Charsets.UTF_8)),
                        hex(svAfter),
                    ).joinToString(" ")
                } finally {
                    tx.free(); tx.close()
                }
            } finally {
                text.close()
            }
        } finally {
            doc.close()
        }
    }

    // ------------------------------------------------------- probe scaffolding

    /** A document holding [PRELOAD] in its root text, with a live transaction. */
    private inline fun preloaded(f: (YrsDoc, YrsText, YrsTransaction) -> Unit) {
        val doc = YrsDoc()
        try {
            val text = doc.getText(ROOT)
            try {
                val tx = doc.transact(null)
                try {
                    text.insert(tx, 0u, PRELOAD)
                    f(doc, text, tx)
                } finally {
                    tx.free(); tx.close()
                }
            } finally {
                text.close()
            }
        } finally {
            doc.close()
        }
    }

    /** A document whose root array holds one element, with a live transaction. */
    private inline fun withArray(f: (YrsDoc, YrsArray, YrsTransaction) -> Unit) {
        val doc = YrsDoc()
        try {
            val array = doc.getArray(ROOT)
            try {
                val tx = doc.transact(null)
                try {
                    array.pushBack(tx, "\"a\"")
                    f(doc, array, tx)
                } finally {
                    tx.free(); tx.close()
                }
            } finally {
                array.close()
            }
        } finally {
            doc.close()
        }
    }

    /** A document whose root map holds `k -> "v"`, with a live transaction. */
    private inline fun withMap(f: (YrsDoc, YrsMap, YrsTransaction) -> Unit) {
        val doc = YrsDoc()
        try {
            val map = doc.getMap(ROOT)
            try {
                val tx = doc.transact(null)
                try {
                    map.insert(tx, "k", "\"v\"")
                    f(doc, map, tx)
                } finally {
                    tx.free(); tx.close()
                }
            } finally {
                map.close()
            }
        } finally {
            doc.close()
        }
    }

    /**
     * A document and its root text with NO transaction open, for the undo
     * manager: `YrsUndoManager.undo` probes the document for a pending
     * transaction first and answers `PendingTransaction` if it finds one
     * (`lib/src/undo.rs:56-64`), so a probe that left one open would only ever
     * exercise that guard.
     */
    private inline fun withUndo(f: (YrsDoc, YrsText, YrsUndoManager) -> Unit) {
        val doc = YrsDoc()
        try {
            val text = doc.getText(ROOT)
            try {
                val manager = doc.undoManager(listOf(text.rawPtr()))
                try {
                    f(doc, text, manager)
                } finally {
                    manager.close()
                }
            } finally {
                text.close()
            }
        } finally {
            doc.close()
        }
    }

    /** One edit, committed, so the undo stack has something on it. */
    private fun edit(doc: YrsDoc, text: YrsText, chunk: String) {
        val tx = doc.transact(null)
        try {
            text.insert(tx, 0u, chunk)
        } finally {
            tx.free(); tx.close()
        }
    }

    // The delegates the iterating and observing methods take. One object per
    // interface rather than one implementing them all: after erasure
    // `call(List<YrsChange>)`, `call(List<YrsMapChange>)` and
    // `call(List<YrsDelta>)` are the same JVM signature, so a single class
    // cannot carry all three. Each records nothing; the entry point being
    // reached is the whole of what these probes ask.
    private object EachSink : YrsArrayEachDelegate, YrsMapIteratorDelegate {
        override fun call(value: String) = Unit
    }

    private object KvSink : YrsMapKvIteratorDelegate {
        override fun call(key: String, value: String) = Unit
    }

    private object ArraySink : YrsArrayObservationDelegate {
        override fun call(value: List<YrsChange>) = Unit
    }

    private object MapSink : YrsMapObservationDelegate {
        override fun call(value: List<YrsMapChange>) = Unit
    }

    private object TextSink : YrsTextObservationDelegate {
        override fun call(value: List<YrsDelta>) = Unit
    }

    // --------------------------------------------------------- entry points

    /**
     * Every method of every interface the UDL declares
     * (kidgloves-inc/yswift's `lib/src/yniffi.udl`), driven with an
     * in-domain input and — where one exists — an out-of-domain one.
     *
     * `YSubscription` carries no methods, and the callback interfaces are
     * inputs rather than entry points, so neither has rows of its own; the
     * methods that TAKE a delegate (`each`, `keys`, `values`, `observe`) are
     * here, driven with a delegate that records nothing.
     *
     * The one out-of-domain input deliberately absent is a second `transact`
     * on a document that already has one open. That does not panic: yrs 0.27
     * waits for exclusive access to the store, so the probe would hang rather
     * than answer, and a hang is the one outcome this harness cannot tell from
     * a slow machine. `YrsUndoManager` guards the same case with
     * `try_transact_mut` (`undo.rs:52-64`), and `YrsUndoManager.undo.out-pending`
     * below is that guard being exercised.
     */
    val ENTRY_POINTS: Map<String, () -> Unit> = linkedMapOf(

        // ---- YrsDoc
        "YrsDoc.new.in" to { YrsDoc().use { } },
        "YrsDoc.withClientId.in" to { YrsDoc.withClientId(1uL, false).use { } },
        "YrsDoc.withClientId.out-above-2^53" to { YrsDoc.withClientId(1uL shl 53, false).use { } },
        "YrsDoc.encodeDiffV1.in" to {
            preloaded { doc, _, tx ->
                val sv = tx.transactionStateVector()
                doc.encodeDiffV1(tx, sv)
            }
        },
        "YrsDoc.encodeDiffV1.out-garbage-sv" to { encodeDiffV1(unhex("ffffffffffffffffffff")) },
        "YrsDoc.getText.in" to { YrsDoc().use { d -> d.getText(ROOT).close() } },
        "YrsDoc.getText.out-empty-name" to { YrsDoc().use { d -> d.getText("").close() } },
        "YrsDoc.getArray.in" to { YrsDoc().use { d -> d.getArray(ROOT).close() } },
        "YrsDoc.getArray.out-empty-name" to { YrsDoc().use { d -> d.getArray("").close() } },
        "YrsDoc.getMap.in" to { YrsDoc().use { d -> d.getMap(ROOT).close() } },
        "YrsDoc.getMap.out-empty-name" to { YrsDoc().use { d -> d.getMap("").close() } },
        "YrsDoc.transact.in" to {
            YrsDoc().use { d ->
                val tx = d.transact(null); tx.free(); tx.close()
            }
        },
        "YrsDoc.transact.out-empty-origin" to {
            YrsDoc().use { d ->
                val tx = d.transact(emptyList()); tx.free(); tx.close()
            }
        },
        "YrsDoc.undoManager.in" to {
            YrsDoc().use { d ->
                d.getText(ROOT).use { t -> d.undoManager(listOf(t.rawPtr())).close() }
            }
        },
        "YrsDoc.undoManager.out-null-ref" to {
            YrsDoc().use { d -> d.undoManager(listOf(0uL)).close() }
        },

        // ---- YrsTransaction
        "YrsTransaction.transactionClientStates.in" to {
            preloaded { _, _, tx -> tx.transactionClientStates() }
        },
        "YrsTransaction.transactionHasMissingUpdates.in" to {
            preloaded { _, _, tx -> tx.transactionHasMissingUpdates() }
        },
        "YrsTransaction.transactionApplyUpdate.in" to { applyUpdate(unhex(SEED_UPDATE_HEX)) },
        "YrsTransaction.transactionApplyUpdate.out-garbage" to {
            applyUpdate(unhex("ffffffffffffffffffff"))
        },
        "YrsTransaction.transactionEncodeStateAsUpdateFromSv.in" to {
            preloaded { _, _, tx ->
                tx.transactionEncodeStateAsUpdateFromSv(tx.transactionStateVector())
            }
        },
        "YrsTransaction.transactionEncodeStateAsUpdateFromSv.out-garbage-sv" to {
            encodeStateFromSv(unhex("ffffffffffffffffffff"))
        },
        "YrsTransaction.transactionEncodeStateAsUpdate.in" to {
            preloaded { _, _, tx -> tx.transactionEncodeStateAsUpdate() }
        },
        "YrsTransaction.transactionEncodeUpdate.in" to {
            preloaded { _, _, tx -> tx.transactionEncodeUpdate() }
        },
        "YrsTransaction.transactionStateVector.in" to {
            preloaded { _, _, tx -> tx.transactionStateVector() }
        },
        "YrsTransaction.transactionGetText.in" to {
            preloaded { _, _, tx -> tx.transactionGetText(ROOT)?.close() }
        },
        "YrsTransaction.transactionGetText.out-absent" to {
            preloaded { _, _, tx -> tx.transactionGetText("absent")?.close() }
        },
        "YrsTransaction.transactionGetArray.in" to {
            withArray { _, _, tx -> tx.transactionGetArray(ROOT)?.close() }
        },
        "YrsTransaction.transactionGetArray.out-absent" to {
            withArray { _, _, tx -> tx.transactionGetArray("absent")?.close() }
        },
        "YrsTransaction.transactionGetMap.in" to {
            withMap { _, _, tx -> tx.transactionGetMap(ROOT)?.close() }
        },
        "YrsTransaction.transactionGetMap.out-absent" to {
            withMap { _, _, tx -> tx.transactionGetMap("absent")?.close() }
        },
        "YrsTransaction.origin.in" to {
            YrsDoc().use { d ->
                val tx = d.transact(listOf(1u.toUByte(), 2u.toUByte()))
                try {
                    tx.origin()
                } finally {
                    tx.free(); tx.close()
                }
            }
        },
        "YrsTransaction.origin.out-none" to {
            YrsDoc().use { d ->
                val tx = d.transact(null)
                try {
                    tx.origin()
                } finally {
                    tx.free(); tx.close()
                }
            }
        },
        "YrsTransaction.free.in" to {
            YrsDoc().use { d ->
                val tx = d.transact(null); tx.free(); tx.close()
            }
        },
        "YrsTransaction.free.out-twice" to {
            YrsDoc().use { d ->
                val tx = d.transact(null)
                try {
                    tx.free(); tx.free()
                } finally {
                    tx.close()
                }
            }
        },
        "YrsTransaction.transactionStateVector.out-after-free" to {
            YrsDoc().use { d ->
                val tx = d.transact(null)
                try {
                    tx.free()
                    tx.transactionStateVector()
                } finally {
                    tx.close()
                }
            }
        },

        // ---- YrsText
        "YrsText.rawPtr.in" to { preloaded { _, text, _ -> text.rawPtr() } },
        "YrsText.format.in" to {
            preloaded { _, text, tx -> text.format(tx, 0u, 5u, "{\"bold\":true}") }
        },
        "YrsText.format.out-index-past-end" to {
            preloaded { _, text, tx -> text.format(tx, 99u, 1u, "{\"bold\":true}") }
        },
        "YrsText.format.out-attrs-not-json" to {
            preloaded { _, text, tx -> text.format(tx, 0u, 5u, "not json") }
        },
        "YrsText.append.in" to { preloaded { _, text, tx -> text.append(tx, " world") } },
        "YrsText.insert.in" to { preloaded { _, text, tx -> text.insert(tx, 0u, "x") } },
        "YrsText.insert.out-index-past-end" to {
            preloaded { _, text, tx -> text.insert(tx, 99u, "x") }
        },
        "YrsText.insert.out-foreign-transaction" to {
            val other = YrsDoc()
            try {
                val otherTx = other.transact(null)
                try {
                    preloaded { _, text, _ -> text.insert(otherTx, 0u, "x") }
                } finally {
                    otherTx.free(); otherTx.close()
                }
            } finally {
                other.close()
            }
        },
        "YrsText.insert.out-freed-transaction" to {
            YrsDoc().use { d ->
                d.getText(ROOT).use { text ->
                    val tx = d.transact(null)
                    try {
                        tx.free()
                        text.insert(tx, 0u, "x")
                    } finally {
                        tx.close()
                    }
                }
            }
        },
        "YrsText.insertWithAttributes.in" to {
            preloaded { _, text, tx -> text.insertWithAttributes(tx, 0u, "x", "{\"bold\":true}") }
        },
        "YrsText.insertWithAttributes.out-index-past-end" to {
            preloaded { _, text, tx -> text.insertWithAttributes(tx, 99u, "x", "{\"bold\":true}") }
        },
        "YrsText.insertEmbed.in" to {
            preloaded { _, text, tx -> text.insertEmbed(tx, 0u, "{\"w\":1}") }
        },
        "YrsText.insertEmbed.out-index-past-end" to {
            preloaded { _, text, tx -> text.insertEmbed(tx, 99u, "{\"w\":1}") }
        },
        "YrsText.insertEmbed.out-empty-content" to {
            preloaded { _, text, tx -> text.insertEmbed(tx, 0u, "") }
        },
        "YrsText.insertEmbedWithAttributes.in" to {
            preloaded { _, text, tx ->
                text.insertEmbedWithAttributes(tx, 0u, "{\"w\":1}", "{\"bold\":true}")
            }
        },
        "YrsText.insertEmbedWithAttributes.out-index-past-end" to {
            preloaded { _, text, tx ->
                text.insertEmbedWithAttributes(tx, 99u, "{\"w\":1}", "{\"bold\":true}")
            }
        },
        "YrsText.insertEmbedWithAttributes.out-empty-content" to {
            preloaded { _, text, tx -> text.insertEmbedWithAttributes(tx, 0u, "", "{\"bold\":true}") }
        },
        "YrsText.getString.in" to { preloaded { _, text, tx -> text.getString(tx) } },
        "YrsText.removeRange.in" to { preloaded { _, text, tx -> text.removeRange(tx, 0u, 1u) } },
        "YrsText.removeRange.out-index-past-end" to {
            preloaded { _, text, tx -> text.removeRange(tx, 99u, 1u) }
        },
        "YrsText.removeRange.out-length-past-end" to {
            preloaded { _, text, tx -> text.removeRange(tx, 0u, 99u) }
        },
        "YrsText.length.in" to { preloaded { _, text, tx -> text.length(tx) } },
        "YrsText.observe.in" to { preloaded { _, text, _ -> text.observe(TextSink).close() } },

        // ---- YrsArray
        "YrsArray.rawPtr.in" to { withArray { _, array, _ -> array.rawPtr() } },
        "YrsArray.each.in" to { withArray { _, array, tx -> array.each(tx, EachSink) } },
        "YrsArray.get.in" to { withArray { _, array, tx -> array.get(tx, 0u) } },
        "YrsArray.get.out-index-past-end" to { withArray { _, array, tx -> array.get(tx, 99u) } },
        "YrsArray.insert.in" to { withArray { _, array, tx -> array.insert(tx, 0u, "\"b\"") } },
        "YrsArray.insert.out-index-past-end" to {
            withArray { _, array, tx -> array.insert(tx, 99u, "\"b\"") }
        },
        "YrsArray.insert.out-value-not-json" to {
            withArray { _, array, tx -> array.insert(tx, 0u, "not json") }
        },
        "YrsArray.insertRange.in" to {
            withArray { _, array, tx -> array.insertRange(tx, 0u, listOf("\"b\"", "\"c\"")) }
        },
        "YrsArray.insertRange.out-index-past-end" to {
            withArray { _, array, tx -> array.insertRange(tx, 99u, listOf("\"b\"")) }
        },
        "YrsArray.length.in" to { withArray { _, array, tx -> array.length(tx) } },
        "YrsArray.pushBack.in" to { withArray { _, array, tx -> array.pushBack(tx, "\"b\"") } },
        "YrsArray.pushBack.out-value-not-json" to {
            withArray { _, array, tx -> array.pushBack(tx, "not json") }
        },
        "YrsArray.pushFront.in" to { withArray { _, array, tx -> array.pushFront(tx, "\"b\"") } },
        "YrsArray.remove.in" to { withArray { _, array, tx -> array.remove(tx, 0u) } },
        "YrsArray.remove.out-index-past-end" to {
            withArray { _, array, tx -> array.remove(tx, 99u) }
        },
        "YrsArray.removeRange.in" to { withArray { _, array, tx -> array.removeRange(tx, 0u, 1u) } },
        "YrsArray.removeRange.out-index-past-end" to {
            withArray { _, array, tx -> array.removeRange(tx, 99u, 1u) }
        },
        "YrsArray.removeRange.out-length-past-end" to {
            withArray { _, array, tx -> array.removeRange(tx, 0u, 99u) }
        },
        "YrsArray.toA.in" to { withArray { _, array, tx -> array.toA(tx) } },
        "YrsArray.observe.in" to { withArray { _, array, _ -> array.observe(ArraySink).close() } },

        // ---- YrsMap
        "YrsMap.rawPtr.in" to { withMap { _, map, _ -> map.rawPtr() } },
        "YrsMap.length.in" to { withMap { _, map, tx -> map.length(tx) } },
        "YrsMap.containsKey.in" to { withMap { _, map, tx -> map.containsKey(tx, "k") } },
        "YrsMap.containsKey.out-absent-key" to {
            withMap { _, map, tx -> map.containsKey(tx, "absent") }
        },
        "YrsMap.insert.in" to { withMap { _, map, tx -> map.insert(tx, "j", "\"w\"") } },
        "YrsMap.insert.out-value-not-json" to {
            withMap { _, map, tx -> map.insert(tx, "j", "not json") }
        },
        "YrsMap.get.in" to { withMap { _, map, tx -> map.get(tx, "k") } },
        "YrsMap.get.out-absent-key" to { withMap { _, map, tx -> map.get(tx, "absent") } },
        "YrsMap.remove.in" to { withMap { _, map, tx -> map.remove(tx, "k") } },
        "YrsMap.remove.out-absent-key" to { withMap { _, map, tx -> map.remove(tx, "absent") } },
        "YrsMap.clear.in" to { withMap { _, map, tx -> map.clear(tx) } },
        "YrsMap.keys.in" to { withMap { _, map, tx -> map.keys(tx, EachSink) } },
        "YrsMap.values.in" to { withMap { _, map, tx -> map.values(tx, EachSink) } },
        "YrsMap.each.in" to { withMap { _, map, tx -> map.each(tx, KvSink) } },
        "YrsMap.observe.in" to { withMap { _, map, _ -> map.observe(MapSink).close() } },

        // ---- YrsUndoManager
        "YrsUndoManager.addOrigin.in" to {
            withUndo { _, _, m -> m.addOrigin(listOf(1u.toUByte())) }
        },
        "YrsUndoManager.removeOrigin.in" to {
            withUndo { _, _, m ->
                m.addOrigin(listOf(1u.toUByte())); m.removeOrigin(listOf(1u.toUByte()))
            }
        },
        "YrsUndoManager.removeOrigin.out-never-added" to {
            withUndo { _, _, m -> m.removeOrigin(listOf(9u.toUByte())) }
        },
        "YrsUndoManager.addScope.in" to {
            withUndo { _, text, m -> m.addScope(text.rawPtr()) }
        },
        "YrsUndoManager.addScope.out-null-ref" to { withUndo { _, _, m -> m.addScope(0uL) } },
        "YrsUndoManager.undo.in" to {
            withUndo { doc, text, m -> edit(doc, text, "hello"); m.undo() }
        },
        "YrsUndoManager.undo.out-no-history" to { withUndo { _, _, m -> m.undo() } },
        "YrsUndoManager.undo.out-pending-transaction" to {
            withUndo { doc, _, m ->
                val tx = doc.transact(null)
                try {
                    m.undo()
                } finally {
                    tx.free(); tx.close()
                }
            }
        },
        "YrsUndoManager.redo.in" to {
            withUndo { doc, text, m -> edit(doc, text, "hello"); m.undo(); m.redo() }
        },
        "YrsUndoManager.redo.out-no-history" to { withUndo { _, _, m -> m.redo() } },
        "YrsUndoManager.wrapChanges.in" to {
            withUndo { doc, text, m -> edit(doc, text, "hello"); m.wrapChanges() }
        },
        "YrsUndoManager.clear.in" to {
            withUndo { doc, text, m -> edit(doc, text, "hello"); m.clear() }
        },
        "YrsUndoManager.clear.out-no-history" to { withUndo { _, _, m -> m.clear() } },
        "YrsUndoManager.observeAdded.in" to {
            withUndo { _, _, m -> m.observeAdded(UndoSink()).close() }
        },
        "YrsUndoManager.observeUpdated.in" to {
            withUndo { _, _, m -> m.observeUpdated(UndoSink()).close() }
        },
        "YrsUndoManager.observePopped.in" to {
            withUndo { _, _, m -> m.observePopped(UndoSink()).close() }
        },

        // ---- YrsUndoEvent, reachable only from inside an observation
        "YrsUndoEvent.origin.in" to { inUndoEvent { e, _ -> e.origin() } },
        "YrsUndoEvent.kind.in" to { inUndoEvent { e, _ -> e.kind() } },
        "YrsUndoEvent.hasChanged.in" to { inUndoEvent { e, ref -> e.hasChanged(ref) } },
        "YrsUndoEvent.hasChanged.out-null-ref" to { inUndoEvent { e, _ -> e.hasChanged(0uL) } },
    )

    /** A delegate that does nothing but keep the meta value it was handed. */
    private class UndoSink : YrsUndoManagerObservationDelegate {
        override fun call(e: YrsUndoEvent, ptr: ULong): ULong = ptr
    }

    /**
     * Drive one [YrsUndoEvent] method from inside an `observeAdded` callback,
     * which is the only place the UDL ever hands one out. The probe fails
     * loudly if the callback never fired, because a probe that did not reach
     * the entry point has not tested it.
     */
    private fun inUndoEvent(f: (YrsUndoEvent, YrsCollectionPtr) -> Unit) {
        withUndo { doc, text, m ->
            val ref = text.rawPtr()
            var fired = false
            val subscription = m.observeAdded(object : YrsUndoManagerObservationDelegate {
                override fun call(e: YrsUndoEvent, ptr: ULong): ULong {
                    fired = true
                    f(e, ref)
                    return ptr
                }
            })
            try {
                edit(doc, text, "hello")
            } finally {
                subscription.close()
            }
            check(fired) { "the undo observation never fired, so the entry point was not driven" }
        }
    }

    // --------------------------------------------------------- parent runner

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd-length hex: $s" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** What a child JVM said, and how it ended. */
data class ChildResult(
    val mode: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    /**
     * The protocol lines, and only those.
     *
     * A child that dies on SIGSEGV rather than on a Rust panic is killed by the
     * JVM's own fatal error handler, which writes its `hs_err` report to
     * STDOUT — pages of it, interleaved with whatever the probe had printed. So
     * stdout is filtered to the shapes this protocol defines rather than taken
     * whole; otherwise "how many inputs did the child answer" counts crash
     * report lines and the failure message names the wrong input. The raw text
     * is kept in [stdout] for whoever has to read it.
     */
    val lines: List<String> = stdout.lines().map { it.trim() }.filter {
        it == "done" || it.startsWith("answered") || it.startsWith("raised ")
    }

    /** The verdict lines: everything the child printed before `done`. */
    val verdicts: List<String> get() = lines.dropLast(if (lines.lastOrNull() == "done") 1 else 0)

    val aborted: Boolean get() = exitCode == SIGABRT_EXIT

    companion object {
        /** 128 + SIGABRT(6): the exit code the JVM's own `abort()` produces. */
        const val SIGABRT_EXIT = 134
    }
}

/**
 * Spawns the child JVM every probe runs in: the same `java`, the same
 * classpath, the same `jna.library.path`, so the child loads the very library
 * the test would have loaded.
 */
object ChildJvm {

    private val javaBin: String =
        ProcessHandle.current().info().command()
            .orElse(System.getProperty("java.home") + "/bin/java")

    private val libraryPath: String =
        System.getProperty("jna.library.path") ?: error("jna.library.path is not set")

    private val errorFilePattern: String =
        java.io.File(System.getProperty("java.io.tmpdir"), "ykt-probe-hs_err_%p.log").path

    /**
     * Run [mode] with [args], feeding [stdin] (one hex per line) when the mode
     * takes a batch. A child that has not finished within [timeoutSeconds] is
     * killed and reported as a failure naming the mode.
     */
    fun run(
        mode: String,
        args: List<String> = emptyList(),
        stdin: List<String> = emptyList(),
        timeoutSeconds: Long = 60,
    ): ChildResult {
        val command = listOf(
            javaBin,
            "-Djna.library.path=$libraryPath",
            // A probe that dies on SIGSEGV rather than on a Rust panic is killed
            // by the JVM's own fatal error handler, which drops an `hs_err_pid`
            // file in the working directory — the project root, under Gradle.
            // Keep the report, keep it out of the tree.
            "-XX:ErrorFile=$errorFilePattern",
            "-cp", System.getProperty("java.class.path"),
            "ai.kidgloves.ykt.Probe",
            mode,
        ) + args
        val process = ProcessBuilder(command).start()
        val out = StringBuilder()
        val err = StringBuilder()
        val outReader = drain(process.inputStream, out)
        val errReader = drain(process.errorStream, err)
        process.outputStream.bufferedWriter().use { w ->
            for (line in stdin) {
                w.write(line); w.newLine()
            }
        }
        val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            outReader.join(); errReader.join()
            throw AssertionError(
                "the child JVM running $mode ${args.joinToString(" ")} did not finish " +
                    "within ${timeoutSeconds}s; it printed:\n$out",
            )
        }
        outReader.join(); errReader.join()
        val label = (listOf(mode) + args).joinToString(" ")
        return ChildResult(label, process.exitValue(), out.toString(), err.toString())
    }

    private fun drain(stream: java.io.InputStream, into: StringBuilder): Thread =
        Thread {
            stream.bufferedReader().forEachLine { synchronized(into) { into.append(it).append('\n') } }
        }.also { it.isDaemon = true; it.start() }
}

/**
 * The parent's half of the protocol: a child that ran [probe] over [inputs]
 * survived, answered every one of them, and said so.
 *
 * On an abort the message names the input that killed it — the child prints a
 * verdict per input as it goes, so the one it never reported is the one it
 * died on.
 */
fun ChildResult.requireEveryInputAnswered(probe: String, inputs: List<String>) {
    if (aborted) {
        val killer = inputs.getOrNull(verdicts.size) ?: "<the child died before its first input>"
        throw AssertionError(
            "aborted: the core panicked across the FFI on $probe with $killer\n" +
                "(${verdicts.size} of ${inputs.size} inputs had been answered)\n" +
                "$stderr\n$stdout",
        )
    }
    if (exitCode != 0) {
        throw AssertionError("the child running $probe exited $exitCode rather than 0\n$stderr")
    }
    if (lines.lastOrNull() != "done") {
        throw AssertionError("the child running $probe never printed `done`; it printed $lines\n$stderr")
    }
    if (verdicts.size != inputs.size) {
        throw AssertionError(
            "the child running $probe answered ${verdicts.size} of ${inputs.size} inputs",
        )
    }
    verdicts.forEachIndexed { i, line ->
        if (!line.startsWith("answered")) {
            throw AssertionError("$probe on ${inputs[i]}: $line")
        }
    }
}
