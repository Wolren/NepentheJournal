package app.journal.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

/** One piece of a receptor name; [subscript] renders it lowered and smaller, as 5-HT<sub>2A</sub>. */
internal data class ReceptorSegment(val text: String, val subscript: Boolean = false)

private val asciiPatterns: List<Regex> = listOf(
    Regex("^(5-HT)([0-9]+[A-Za-z]?)$"),
    Regex("^(TAAR)([0-9]+)$"),
    Regex("^(Alpha-|Beta-)([0-9]+[A-Za-z]?)$"),
    Regex("^(D|H|M|N)([0-9]+[A-Za-z]?)$"),
    Regex("^(P2X purinoceptor )([0-9]+)$"),
)

private val greekSubunit = Regex("([\u03b1-\u03c9])([0-9]+)")

/**
 * Splits a receptor name so its subtype renders lowered, as 5-HT<sub>2A</sub>.
 * Every Greek letter followed by digits becomes a subscript, so a composition
 * such as GABA-A alpha6beta2gamma2 renders all three subunits. Names with no
 * recognizable subtype come back whole.
 */
internal fun receptorSegments(label: String): List<ReceptorSegment> {
    val text = label.trim()
    if (text.isEmpty()) return emptyList()
    for (pattern in asciiPatterns) {
        val match = pattern.find(text) ?: continue
        return listOf(
            ReceptorSegment(match.groupValues[1]),
            ReceptorSegment(match.groupValues[2], subscript = true),
        )
    }
    if (!greekSubunit.containsMatchIn(text)) return listOf(ReceptorSegment(text))
    val out = mutableListOf<ReceptorSegment>()
    var cursor = 0
    for (match in greekSubunit.findAll(text)) {
        val greekEnd = match.range.first + 1
        if (greekEnd > cursor) out += ReceptorSegment(text.substring(cursor, greekEnd))
        out += ReceptorSegment(match.groupValues[2], subscript = true)
        cursor = match.range.last + 1
    }
    if (cursor < text.length) out += ReceptorSegment(text.substring(cursor))
    return out
}

/** Natural order: 5-HT1A before 5-HT2A before 5-HT10, which plain text order gets wrong. */
internal fun compareReceptorLabels(first: String, second: String): Int {
    var i = 0
    var j = 0
    while (i < first.length && j < second.length) {
        val a = first[i]
        val b = second[j]
        if (a.isDigit() && b.isDigit()) {
            val startA = i
            val startB = j
            while (i < first.length && first[i].isDigit()) i++
            while (j < second.length && second[j].isDigit()) j++
            val numberA = first.substring(startA, i).trimStart('0')
            val numberB = second.substring(startB, j).trimStart('0')
            if (numberA.length != numberB.length) return numberA.length - numberB.length
            val byNumber = numberA.compareTo(numberB)
            if (byNumber != 0) return byNumber
            continue
        }
        val byChar = a.lowercaseChar().compareTo(b.lowercaseChar())
        if (byChar != 0) return byChar
        i++
        j++
    }
    return (first.length - i) - (second.length - j)
}

/** Receptor name with its subtype subscripted, as a pharmacology table prints it. */
@Composable
fun ReceptorText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    fontWeight: FontWeight? = null,
    subscriptSize: TextUnit = style.fontSize.times(0.78f),
) {
    Text(
        text = receptorAnnotatedString(text, subscriptSize),
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        fontWeight = fontWeight,
        modifier = modifier,
    )
}

internal fun receptorAnnotatedString(text: String, subscriptSize: TextUnit = TextUnit.Unspecified): AnnotatedString =
    buildAnnotatedString {
        receptorSegments(text).forEach { segment ->
            if (segment.subscript) {
                withStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = subscriptSize)) {
                    append(segment.text)
                }
            } else {
                append(segment.text)
            }
        }
    }
