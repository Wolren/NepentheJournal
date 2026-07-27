package app.journal.ui.components

import androidx.compose.ui.graphics.Color

/** Color mapping for administration routes. Shared across all screens. */
fun routeColor(route: String): Color {
    return when (route.lowercase()) {
        "oral" -> Color(0xFF66BB6A)
        "sublingual" -> Color(0xFF42A5F5)
        "insufflated", "intranasal" -> Color(0xFFAB47BC)
        "inhaled" -> Color(0xFF26C6DA)
        "vaporized" -> Color(0xFFFFA726)
        "intramuscular" -> Color(0xFFEF5350)
        "intravenous" -> Color(0xFFD32F2F)
        "rectal" -> Color(0xFF7E57C2)
        "transdermal" -> Color(0xFF43A047)
        "buccal" -> Color(0xFFEC407A)
        "subcutaneous" -> Color(0xFFFFCA28)
        else -> Color(0xFF78909C)
    }
}

/** Color mapping for substance classes. Shared across all screens. */
fun substanceClassColor(cls: String): Color {
    val c = cls.lowercase().trim()
    return when {
        c.contains("psychedelic") || c.contains("hallucinogen") -> Color(0xFFAB47BC)
        c.contains("stimulant") -> Color(0xFFFF7043)
        c.contains("depressant") || c.contains("sedative") -> Color(0xFF42A5F5)
        c.contains("dissociative") -> Color(0xFF26C6DA)
        c.contains("empathogen") || c.contains("entactogen") -> Color(0xFFEC407A)
        c.contains("opioid") || c.contains("opiate") -> Color(0xFFEF5350)
        c.contains("benzodiazepine") || c.contains("z-drug") -> Color(0xFFFFA726)
        c.contains("maoi") -> Color(0xFFFF6D00)
        c.contains("ssri") || c.contains("antidepressant") -> Color(0xFF66BB6A)
        c.contains("antipsychotic") -> Color(0xFF78909C)
        c.contains("anesthetic") || c.contains("nootropic") -> Color(0xFF5C6BC0)
        c.contains("deliriant") -> Color(0xFF8D6E63)
        c.contains("cannabinoid") -> Color(0xFF9CCC65)
        c.contains("alcohol") -> Color(0xFFBDBDBD)
        else -> Color(0xFF78909C)
    }
}
