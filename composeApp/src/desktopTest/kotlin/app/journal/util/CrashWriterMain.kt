package app.journal.util

/**
 * Writes growing payloads until the durability test kills the process, so the
 * killed writer really dies mid-file instead of unwinding.
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: return
    var n = 0
    val pad = "x".repeat(240_000)
    while (true) {
        PlatformFile.writeText(path, """{"n":$n,"pad":"$pad"}""")
        n++
    }
}
