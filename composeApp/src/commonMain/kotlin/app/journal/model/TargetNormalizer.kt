package app.journal.model

/**
 * Canonical name for a pharmacological target, used to merge duplicate
 * rows that spell the same receptor differently across sources.
 *
 * Examples merged by one key: "5-HT2A" (PDSP), "5-hydroxytryptamine
 * receptor 2A" (BindingDB), "5-HT<sub>2A</sub> receptor" (IUPHAR).
 */
data class NormalizedTarget(
    /** Lowercase grouping key. Equal keys mean the same target. */
    val key: String,
    /** Short display label, e.g. "5-HT2A", "D2", "SERT". */
    val label: String,
)

/**
 * Normalizes pharmacological target names from PDSP, BindingDB, IUPHAR,
 * ChEMBL, and Wikipedia binding tables into canonical keys.
 *
 * Rules are conservative: variants merge only when the receptor identity
 * is unambiguous (same family plus same subtype). Entries without a
 * subtype ("Adrenergic Alpha") never merge into subtyped rows.
 */
object TargetNormalizer {

    private val htmlTag = Regex("<[^>]+>")
    private val spaces = Regex("\\s+")

    fun normalize(raw: String?): NormalizedTarget {
        if (raw.isNullOrBlank()) return NormalizedTarget("unknown", "Unknown target")
        val cleaned = spaces.replace(htmlTag.replace(raw, " ").replace("\u00A0", " "), " ").trim()
        if (cleaned.isEmpty()) return NormalizedTarget("unknown", "Unknown target")
        val flat = fold(cleaned)

        serotonin(flat)?.let { return it }
        dopamine(flat)?.let { return it }
        adrenergic(flat)?.let { return it }
        histamine(flat)?.let { return it }
        transporter(flat)?.let { return it }
        taar(flat)?.let { return it }
        opioid(flat)?.let { return it }
        glutamate(flat)?.let { return it }

        val label = stripSuffixWords(cleaned)
        return NormalizedTarget("x:" + label.lowercase(), label)
    }

    // ------------------------------------------------------------------
    // Text folding: lowercase, greek to latin, punctuation to spaces.
    // ------------------------------------------------------------------

    private fun fold(s: String): String {
        var t = s.lowercase()
        t = t.replace("α", "alpha").replace("β", "beta")
            .replace("μ", "mu").replace("κ", "kappa").replace("δ", "delta")
        t = t.replace("(", " ").replace(")", " ")
            .replace("[", " ").replace("]", " ")
            .replace("-", " ").replace("/", " ").replace("_", " ")
            .replace(",", " ").replace(".", "")
        return spaces.replace(t, " ").trim()
    }

    // ------------------------------------------------------------------
    // Receptor families. Each returns null when the name is not a member.
    // ------------------------------------------------------------------

    private val serotoninHead = Regex("^(\\d)([a-z]?)(l?)\$")

    private fun serotonin(flat: String): NormalizedTarget? {
        val head = when {
            flat.contains("hydroxytryptamine") -> {
                val after = flat.substringAfter("hydroxytryptamine")
                    .replace("receptor", " ").trim()
                if (after.isEmpty()) return null
                after
            }
            Regex("^5\\s*h\\s*t?").containsMatchIn(flat) ->
                flat.replace(Regex("^5\\s*h\\s*t?\\s*"), "").trim()
            else -> return null
        }
        // Head must start with a digit (the subtype); anything else
        // ("transporter") is not a serotonin receptor row. Only the
        // leading token carries the subtype ("2a" in "2a receptor").
        val first = head.split(" ").firstOrNull() ?: return null
        if (first.isEmpty() || !first[0].isDigit()) return null
        val m = serotoninHead.find(first) ?: return null
        // A trailing "l" is an assay label, not a subtype (5-HT7L is
        // the 5-HT7 receptor); real subtypes never end in L.
        var sub = m.groupValues[1] + m.groupValues[2].uppercase()
        if (sub.length > 1 && sub.endsWith("L")) sub = sub.dropLast(1)
        if (sub.isEmpty()) return null
        return NormalizedTarget("htr$sub", "5-HT$sub")
    }

    private val dopamineFull = Regex("dopamine\\s*d\\s*([1-5])([a-z]?)(?=\\s|$)")
    private val dopaminePrefix = Regex("^d\\s*([1-5])([a-z]?)(?=\\s|$)")
    private val dopamineBare = Regex("^d\\s*([1-5])$")

    private fun dopamine(flat: String): NormalizedTarget? {
        // A letter after the digit refines the subtype: "1a" is D1, but
        // "1b" (avian/Xenopus D1B, closer to D5) must not merge into D1.
        fun digitOf(m: MatchResult?): String? {
            if (m == null) return null
            if (m.groupValues[2].isNotEmpty() && m.groupValues[2] != "a") return null
            return m.groupValues[1]
        }
        val n = digitOf(dopamineFull.find(flat))
            ?: dopamineBare.find(flat)?.groupValues?.get(1)
            ?: digitOf(dopaminePrefix.find(flat)?.takeIf { flat.contains("dopamine") })
            ?: return null
        return NormalizedTarget("drd$n", "D$n")
    }

    private val alphaSub = Regex("alpha\\s*(\\d)\\s*([ab]?)")
    private val betaSub = Regex("beta\\s*(\\d)\\s*")

    private fun adrenergic(flat: String): NormalizedTarget? {
        if (!flat.contains("adrenergic") && !flat.contains("adreno")
            && !flat.contains("alpha") && !flat.contains("beta")
        ) return null
        // Reject non-adrenergic alpha/beta uses (e.g. "alpha-2/beta-2"
        // nicotinic subunits carry "neuronal acetylcholine" context).
        if (flat.contains("acetylcholine") || flat.contains("nicotinic")) return null
        val a = alphaSub.find(flat)
        if (a != null && (flat.contains("adrenergic") || flat.contains("adreno") || isBareAdreno(flat, "alpha"))) {
            val sub = a.groupValues[1] + a.groupValues[2].uppercase()
            return NormalizedTarget("adra$sub", "Alpha-$sub")
        }
        val b = betaSub.find(flat)
        if (b != null && (flat.contains("adrenergic") || flat.contains("adreno") || isBareAdreno(flat, "beta"))) {
            val sub = b.groupValues[1]
            return NormalizedTarget("adrb$sub", "Beta-$sub")
        }
        if (flat.contains("adrenergic")) {
            // Generic entry without a subtype: its own group, never
            // merged into subtyped rows.
            return NormalizedTarget("x:" + flat, titleLabel(flat))
        }
        return null
    }

    private fun isBareAdreno(flat: String, word: String): Boolean {
        val tokens = flat.split(" ")
        return tokens.size == 1 && tokens[0].startsWith(word)
    }

    private val histamineFull = Regex("histamine\\s*h\\s*([1-4])")
    private val histamineBare = Regex("^h\\s*([1-4])\$")

    private fun histamine(flat: String): NormalizedTarget? {
        val n = histamineFull.find(flat)?.groupValues?.get(1)
            ?: histamineBare.find(flat)?.groupValues?.get(1)
            ?: return null
        return NormalizedTarget("hrh$n", "H$n")
    }

    private fun transporter(flat: String): NormalizedTarget? {
        val tokens = flat.split(" ").toSet()
        if ("sert" in tokens || (flat.contains("serotonin") && flat.contains("transporter"))) {
            return NormalizedTarget("slc6a4", "SERT")
        }
        if ("dat" in tokens || (flat.contains("dopamine") && flat.contains("transporter"))) {
            return NormalizedTarget("slc6a3", "DAT")
        }
        if ("net" in tokens || flat.contains("noradrenaline transporter")
            || flat.contains("norepinephrine transporter")
        ) {
            return NormalizedTarget("slc6a2", "NET")
        }
        return null
    }

    private fun taar(flat: String): NormalizedTarget? {
        if (flat.contains("taar") || flat.contains("trace amine")) {
            return NormalizedTarget("taar1", "TAAR1")
        }
        return null
    }

    private fun opioid(flat: String): NormalizedTarget? {
        if (!flat.contains("opioid") && !flat.contains("opiate")) return null
        val tokens = flat.split(" ").toSet()
        return when {
            "mu" in tokens -> NormalizedTarget("oprm1", "Mu opioid")
            "kappa" in tokens -> NormalizedTarget("oprk1", "Kappa opioid")
            "delta" in tokens -> NormalizedTarget("oprd1", "Delta opioid")
            else -> null
        }
    }

    private val mglu = Regex("m\\s*glu\\s*r?\\s*(\\d)|metabotropic glutamate[^0-9]*(\\d)")

    private fun glutamate(flat: String): NormalizedTarget? {
        val m = mglu.find(flat)
        if (m != null) {
            val n = m.groupValues[1].ifEmpty { m.groupValues[2] }
            return NormalizedTarget("grm$n", "mGluR$n")
        }
        if (flat.contains("nmda")) {
            val sub = Regex("nmda\\s*([12][ab]?)").find(flat)?.groupValues?.get(1)?.uppercase()
            return if (sub != null && sub.isNotEmpty()) {
                NormalizedTarget("grin$sub", "NMDA $sub")
            } else {
                NormalizedTarget("x:" + flat, titleLabel(flat))
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Fallback helpers.
    // ------------------------------------------------------------------

    private val suffixWords = Regex("\\s+(receptors?|transporters?|channels?|enzymes?|proteins?|subunits?|subtype)\\s*\$")
    private val parenTail = Regex("\\s*\\([^)]*\\)\\s*\$")

    private fun stripSuffixWords(cleaned: String): String {
        var t = cleaned.trim()
        t = parenTail.replace(t, "")
        t = suffixWords.replace(t, "")
        return spaces.replace(t, " ").trim().ifEmpty { cleaned.trim() }
    }

    private fun titleLabel(flat: String): String =
        flat.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
