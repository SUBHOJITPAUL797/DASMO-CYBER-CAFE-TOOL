fun main() {
    val blacklists = listOf("no", "pin", "tax")
    println(blacklists.any { "manoj".contains(it) })
}
