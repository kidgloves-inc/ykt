package ai.kidgloves.ykt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import uniffi.yniffi.YrsDoc
import uniffi.yniffi.YrsDocException
import uniffi.yniffi.YrsTransaction

/**
 * The binding's own surface, against a host build of the library: that it
 * loads, that a document written on one side reads back on the other, the
 * client-id range `with_client_id` accepts, the pending-update flag, and that
 * offsets and lengths are UTF-16 code units.
 */
class BindingSpec : StringSpec({

    "the library loads and a document renders what was inserted" {
        YrsDoc.withClientId(1uL, false).use { doc ->
            doc.getText("prompt").use { text ->
                doc.write { tx ->
                    text.insert(tx, 0u, "hello")
                    text.getString(tx) shouldBe "hello"
                    tx.transactionClientStates().map { it.clientId to it.clock } shouldBe listOf(1uL to 5u)
                }
            }
        }
    }

    "an update written by one document reads back in another" {
        YrsDoc.withClientId(7uL, false).use { source ->
            val update = source.getText("prompt").use { text ->
                source.write { tx ->
                    text.insert(tx, 0u, "hello")
                    text.insert(tx, 5u, ", world")
                    tx.transactionEncodeStateAsUpdate()
                }
            }
            YrsDoc().use { peer ->
                peer.getText("prompt").use { text ->
                    peer.write { tx ->
                        tx.transactionApplyUpdate(update)
                        text.getString(tx) shouldBe "hello, world"
                        tx.transactionClientStates().map { it.clientId to it.clock } shouldBe listOf(7uL to 12u)
                    }
                }
            }
        }
    }

    "with_client_id accepts 2^53 - 1 and refuses 2^53" {
        val largest = (1uL shl 53) - 1uL
        YrsDoc.withClientId(largest, false).use { doc ->
            doc.getText("prompt").use { text ->
                doc.write { tx ->
                    text.insert(tx, 0u, "x")
                    tx.transactionClientStates().map { it.clientId } shouldBe listOf(largest)
                }
            }
        }
        shouldThrow<YrsDocException.ClientIdOutOfRange> { YrsDoc.withClientId(1uL shl 53, false) }
    }

    "a withheld dependency leaves the document missing updates until it arrives" {
        val (first, second) = YrsDoc.withClientId(3uL, false).use { source ->
            source.getText("prompt").use { text ->
                val first = source.write { tx ->
                    text.insert(tx, 0u, "a")
                    tx.transactionEncodeStateAsUpdate()
                }
                val afterFirst = source.write { it.transactionStateVector() }
                // "b"'s left origin is the "a" above, so it cannot be
                // integrated without it.
                val second = source.write { tx ->
                    text.insert(tx, 1u, "b")
                    source.encodeDiffV1(tx, afterFirst)
                }
                first to second
            }
        }

        YrsDoc().use { peer ->
            peer.getText("prompt").use { text ->
                peer.write { tx -> tx.transactionHasMissingUpdates() shouldBe false }
                peer.write { tx ->
                    tx.transactionApplyUpdate(second)
                    tx.transactionHasMissingUpdates() shouldBe true
                    text.getString(tx) shouldBe ""
                }
                peer.write { tx ->
                    tx.transactionApplyUpdate(first)
                    tx.transactionHasMissingUpdates() shouldBe false
                    text.getString(tx) shouldBe "ab"
                }
            }
        }
    }

    // Both constructors set the offset kind, so both are checked.
    for ((constructor, make) in listOf<Pair<String, () -> YrsDoc>>(
        "YrsDoc()" to { YrsDoc() },
        "YrsDoc.withClientId" to { YrsDoc.withClientId(9uL, false) },
    )) {
        "offsets and lengths are UTF-16 code units: $constructor" {
            make().use { doc ->
                doc.getText("prompt").use { text ->
                    doc.write { tx ->
                        // U+1F600 is two UTF-16 units; the Cyrillic and CJK
                        // characters are one each.
                        text.insert(tx, 0u, "a😀b")
                        text.length(tx) shouldBe 4u
                        text.insert(tx, 3u, "ж中")
                        text.getString(tx) shouldBe "a😀ж中b"
                        text.length(tx) shouldBe 6u
                        text.removeRange(tx, 1u, 2u)
                        text.getString(tx) shouldBe "aж中b"
                        text.length(tx) shouldBe 4u
                    }
                }
            }
        }
    }
})

/** One transaction, committed and released whatever [f] does. */
private inline fun <R> YrsDoc.write(f: (YrsTransaction) -> R): R {
    val tx = transact(null)
    try {
        return f(tx)
    } finally {
        tx.free()
        tx.close()
    }
}
