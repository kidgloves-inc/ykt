import uniffi.yniffi.YrsDoc

fun main() {
  val doc = YrsDoc()
  val text = doc.getText("my_text")

  val tx = doc.transact(null)

  text.append(tx, "Hello, World!")
  println(text.getString(tx))

  tx.free()
}
