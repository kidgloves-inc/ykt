package ai.kidgloves.ykt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import uniffi.yniffi.YrsDoc

class SmokeSpec : StringSpec({
    "a document renders what was inserted" {
        val doc = YrsDoc.withClientId(1uL, false)
        val text = doc.getText("prompt")
        val tx = doc.transact(null)
        text.insert(tx, 0u, "hello")
        text.getString(tx) shouldBe "hello"
        tx.transactionClientStates().map { it.clientId to it.clock } shouldBe listOf(1uL to 5u)
        tx.free(); tx.close(); text.close(); doc.close()
    }
})
