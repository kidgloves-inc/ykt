package ai.kidgloves.ykt

import io.kotest.core.spec.style.StringSpec

/**
 * Every entry point on the UDL surface, driven once in a child JVM.
 *
 * ## What this list is for
 *
 * The library these tests load is built with `panic = "abort"` —
 * `ykt/Cargo.toml`'s `[profile.release]`, restating the fork's own
 * (`yswift/lib/Cargo.toml:6-11`) — and it is that same profile an Android app
 * ships. So each abort recorded in [ABORTS_TODAY] below is not a test failure
 * that has been tolerated: it is a **process death an app cannot catch**. There
 * is no `try`/`catch` on the Kotlin side that sees it, no crash handler that
 * runs first, and no state left to save. A caller that passes an index one
 * past the end takes its whole process down.
 *
 * The fix belongs in the fork's binding — validate the argument and throw a
 * `CodingException`, in `yswift/lib/src/{text,array,map,doc,undo}.rs` — and
 * NOT here. **This list is the work order.** Nothing below is fixed by this
 * file; the file only refuses to let the list go stale.
 *
 * It is therefore strict in both directions. An entry point not in
 * [ABORTS_TODAY] must answer, and an entry point IN it must still abort, with
 * the panic text it is pinned to. When someone fixes one, this test goes red
 * and they delete its row — which is the only way a work order stays honest.
 *
 * ## How each probe is driven
 *
 * One child JVM per entry point, spawned by [ChildJvm] exactly as the P7 tier
 * spawns its batches: the same `java`, the same `-Djna.library.path`, the same
 * classpath, a 60 second timeout. The child runs `Probe entry <name>`, prints
 * `answered` or `raised <ExceptionClass>` and then `done`, and exits 0 — unless
 * the core takes it with it, in which case the parent sees exit code 134 and
 * the panic on stderr. The probes themselves, with the in-domain and
 * out-of-domain input each is given, are `Probe.ENTRY_POINTS`.
 *
 * ## Two findings this sweep turned up that are not aborts
 *
 * `YrsText.insert` and `YrsText.format` past the end of the text do NOT panic
 * on yrs 0.27.4, although both carry a `panic!("The type or the position
 * doesn't exist!")` arm and the doc comments promise one. `find_position`
 * (`yrs-0.27.4/src/types/text.rs:734-804`) walks to the end of the block list
 * and returns `Some` whatever index it was asked for, so those arms are
 * unreachable and an insert past the end silently APPENDS. That is a wrong
 * answer rather than a dead process, so it is not on this list; it is worth a
 * line in whatever fixes the list, because a validating binding owes an error
 * there too.
 *
 * `YrsText.insert` with a transaction belonging to a DIFFERENT document also
 * answers — it writes the branch of one document through the store of another
 * without complaint. This tier only asks whether a call returns, so it passes;
 * what it leaves behind is nobody's contract.
 *
 * One out-of-domain input is deliberately absent: a second `transact` on a
 * document that already has one open. yrs 0.27 waits for exclusive access to
 * the store rather than failing, so that probe hangs instead of answering, and
 * a hang is the one outcome this harness cannot tell from a slow machine.
 * `YrsUndoManager` guards the same case itself (`undo.rs:52-64`), and
 * `YrsUndoManager.undo.out-pending-transaction` is that guard being exercised.
 */
class EntryPointsSpec : StringSpec({

    Probe.ENTRY_POINTS.keys.forEach { name ->
        val pinned = ABORTS_TODAY[name]
        if (pinned == null) {
            "$name answers" {
                val result = ChildJvm.run("entry", listOf(name))
                if (result.aborted) {
                    throw AssertionError(
                        "aborted: the core panicked across the FFI on $name\n${result.stderr}",
                    )
                }
                if (result.exitCode != 0) {
                    throw AssertionError(
                        "the child running $name exited ${result.exitCode}\n${result.stderr}",
                    )
                }
                if (result.lines != listOf("answered", "done")) {
                    throw AssertionError("$name: ${result.lines}\n${result.stderr}")
                }
            }
        } else {
            "$name aborts the process today, on: $pinned" {
                val result = ChildJvm.run("entry", listOf(name))
                if (!result.aborted) {
                    throw AssertionError(
                        "$name no longer aborts (it printed ${result.lines} and exited " +
                            "${result.exitCode}). If the binding now validates this input, " +
                            "delete its row from ABORTS_TODAY.",
                    )
                }
                if (!result.stderr.contains(pinned)) {
                    throw AssertionError(
                        "$name still aborts, but not on the pinned panic. Expected stderr to " +
                            "carry \"$pinned\"; it carried:\n${result.stderr}",
                    )
                }
            }
        }
    }

    "every pinned abort names an entry point that exists" {
        val unknown = ABORTS_TODAY.keys - Probe.ENTRY_POINTS.keys
        if (unknown.isNotEmpty()) {
            throw AssertionError("ABORTS_TODAY names entry points that no longer exist: $unknown")
        }
    }
})

/**
 * The entry points that kill the process today, each with the panic it dies on.
 *
 * Read this as a defect list against the fork's binding, in three groups:
 *
 *  - **An index or a length past the end.** `YrsArray`'s insert, remove and
 *    range forms hand the index straight to yrs, whose `panic!` is the
 *    documented behaviour (`yrs-0.27.4/src/types/array.rs:201, :258`);
 *    `YrsText.removeRange` reaches the `remove` helper's own panic
 *    (`types/text.rs:845`). The binding knows the length — `length(tx)` is on
 *    the same object — and owes a `CodingException` instead.
 *  - **A string that is not JSON.** `Any::from_json(...).unwrap()` appears
 *    verbatim in `array.rs:121, :157`, `map.rs:79`, `text.rs:92, :107` and
 *    `attrs.rs:17`. Every one of those takes a `string` on the UDL, so every
 *    caller in every language can reach it with a typo. `YrsMap.get` is the
 *    same shape with a different `unwrap`: `map.get(tx, key).unwrap()`
 *    (`map.rs:129`) dies on an absent key, while `containsKey` and `remove`
 *    answer for the same key.
 *  - **A pointer or a transaction that is gone.** `YrsCollectionPtr`'s
 *    `as_ref` is `unsafe { self.0.as_ref() }.unwrap()` (`doc.rs:135`), so any
 *    `u64` a caller invents — zero included — is a panic; and
 *    `YrsTransaction::free` replaces the transaction with `None`
 *    (`transaction.rs:143-145`), after which every method's `.unwrap()` on it
 *    is one. The Kotlin object is still perfectly alive at that point: the
 *    binding's own `close()` would have raised `IllegalStateException`, which
 *    is catchable, but `free()` leaves a live handle over a dead transaction.
 */
private val ABORTS_TODAY: Map<String, String> = linkedMapOf(

    // An index or a length past the end.
    "YrsText.removeRange.out-index-past-end" to
        "Couldn't remove 1 elements from an array. Only 0 of them were successfully removed.",
    "YrsText.removeRange.out-length-past-end" to
        "Couldn't remove 99 elements from an array. Only 5 of them were successfully removed.",
    "YrsArray.insert.out-index-past-end" to "Index 99 is outside of the range of an array",
    "YrsArray.insertRange.out-index-past-end" to "Index 99 is outside of the range of an array",
    "YrsArray.remove.out-index-past-end" to "Index 99 is outside of the range of an array",
    "YrsArray.removeRange.out-index-past-end" to "Index 99 is outside of the range of an array",
    "YrsArray.removeRange.out-length-past-end" to "Length exceeded",

    // A string that is not JSON, and the map key that is not there.
    "YrsText.format.out-attrs-not-json" to
        "called `Result::unwrap()` on an `Err` value: InvalidJSON",
    "YrsText.insertEmbed.out-empty-content" to
        "called `Result::unwrap()` on an `Err` value: InvalidJSON",
    "YrsText.insertEmbedWithAttributes.out-empty-content" to
        "called `Result::unwrap()` on an `Err` value: InvalidJSON",
    "YrsArray.insert.out-value-not-json" to
        "called `Result::unwrap()` on an `Err` value: InvalidJSON",
    "YrsArray.pushBack.out-value-not-json" to
        "called `Result::unwrap()` on an `Err` value: InvalidJSON",
    "YrsMap.insert.out-value-not-json" to
        "called `Result::unwrap()` on an `Err` value: InvalidJSON",
    "YrsMap.get.out-absent-key" to "called `Option::unwrap()` on a `None` value",

    // A pointer or a transaction that is gone.
    "YrsDoc.undoManager.out-null-ref" to "called `Option::unwrap()` on a `None` value",
    "YrsUndoManager.addScope.out-null-ref" to "called `Option::unwrap()` on a `None` value",
    "YrsUndoEvent.hasChanged.out-null-ref" to "called `Option::unwrap()` on a `None` value",
    "YrsTransaction.transactionStateVector.out-after-free" to
        "called `Option::unwrap()` on a `None` value",
    "YrsText.insert.out-freed-transaction" to "called `Option::unwrap()` on a `None` value",
)
