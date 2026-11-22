package app.journal.util

/** Windows editors prepend a UTF-8 BOM; it is not part of the document text. */
fun stripUtf8Bom(text: String): String = text.removePrefix("\uFEFF")
