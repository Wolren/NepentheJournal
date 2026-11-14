package app.journal.ui.substances

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles

/**
 * One readable piece of an effect article. The bundled body carries `## `
 * heading lines, `- ` bullet lines, `**bold**`, `*italic*`, `{{target|label}}`
 * links, and `:::level label | header` ... `:::` boxes.
 */
sealed interface EffectInline {
    data class Text(val text: String) : EffectInline
    data class Bold(val text: String) : EffectInline
    data class Italic(val text: String) : EffectInline
    data class Link(val target: String, val label: String) : EffectInline
}

sealed interface EffectBlock {
    data class Heading(val text: String) : EffectBlock
    data class Paragraph(val spans: List<EffectInline>) : EffectBlock
    data class Bullet(val spans: List<EffectInline>) : EffectBlock
    data class LevelBox(
        val label: String,
        val header: String,
        val paragraphs: List<List<EffectInline>>,
    ) : EffectBlock
}

internal fun parseInline(text: String): List<EffectInline> {
    val out = mutableListOf<EffectInline>()
    val plain = StringBuilder()

    fun flush() {
        if (plain.isNotEmpty()) {
            out += EffectInline.Text(plain.toString())
            plain.clear()
        }
    }

    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end > index + 2) {
                    flush()
                    out += EffectInline.Bold(text.substring(index + 2, end))
                    index = end + 2
                } else {
                    plain.append(text[index])
                    index++
                }
            }
            text.startsWith("{{", index) -> {
                val end = text.indexOf("}}", index + 2)
                val body = if (end > index) text.substring(index + 2, end) else ""
                val bar = body.indexOf('|')
                if (end > index && bar > 0) {
                    flush()
                    out += EffectInline.Link(body.substring(0, bar), body.substring(bar + 1))
                    index = end + 2
                } else {
                    plain.append(text[index])
                    index++
                }
            }
            text[index] == '*' -> {
                val end = text.indexOf('*', index + 1)
                if (end > index + 1) {
                    flush()
                    out += EffectInline.Italic(text.substring(index + 1, end))
                    index = end + 1
                } else {
                    plain.append(text[index])
                    index++
                }
            }
            else -> {
                plain.append(text[index])
                index++
            }
        }
    }
    flush()
    return out
}

internal fun parseEffectBlocks(body: String): List<EffectBlock> {
    val blocks = mutableListOf<EffectBlock>()
    val paragraph = StringBuilder()
    var boxLabel: String? = null
    var boxHeader: String = ""
    var boxParagraphs = mutableListOf<List<EffectInline>>()

    fun flushParagraph(collect: (List<EffectInline>) -> Unit) {
        val text = paragraph.toString().trim()
        paragraph.clear()
        if (text.isEmpty()) return
        collect(parseInline(text))
    }

    fun closeBox() {
        if (boxLabel == null) return
        flushParagraph { boxParagraphs += it }
        blocks += EffectBlock.LevelBox(boxLabel.orEmpty(), boxHeader, boxParagraphs)
        boxLabel = null
        boxHeader = ""
        boxParagraphs = mutableListOf()
    }

    body.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith(":::level") -> {
                flushParagraph { if (boxLabel != null) boxParagraphs += it else blocks += EffectBlock.Paragraph(it) }
                closeBox()
                val rest = line.removePrefix(":::level").trim()
                val parts = rest.split("|", limit = 2)
                boxLabel = parts.getOrNull(0)?.trim().orEmpty()
                boxHeader = parts.getOrNull(1)?.trim().orEmpty()
            }
            line == ":::" -> {
                flushParagraph { boxParagraphs += it }
                closeBox()
            }
            line.isEmpty() -> flushParagraph {
                if (boxLabel != null) boxParagraphs += it else blocks += EffectBlock.Paragraph(it)
            }
            line.startsWith("## ") -> {
                flushParagraph {
                    if (boxLabel != null) boxParagraphs += it else blocks += EffectBlock.Paragraph(it)
                }
                val heading = line.removePrefix("## ").trim()
                if (heading.isNotEmpty()) {
                    if (boxLabel != null) boxParagraphs += parseInline(heading)
                    else blocks += EffectBlock.Heading(heading)
                }
            }
            line.startsWith("- ") -> {
                flushParagraph {
                    if (boxLabel != null) boxParagraphs += it else blocks += EffectBlock.Paragraph(it)
                }
                val item = line.removePrefix("- ").trim()
                if (item.isNotEmpty()) {
                    val spans = parseInline(item)
                    if (boxLabel != null) boxParagraphs += spans else blocks += EffectBlock.Bullet(spans)
                }
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
        }
    }
    flushParagraph {
        if (boxLabel != null) boxParagraphs += it else blocks += EffectBlock.Paragraph(it)
    }
    closeBox()
    return blocks
}

@Composable
internal fun EffectSpans(
    spans: List<EffectInline>,
    onLinkClick: (String) -> Unit,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        spans.forEach { span ->
            when (span) {
                is EffectInline.Text -> append(span.text)
                is EffectInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(span.text)
                }
                is EffectInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(span.text)
                }
                is EffectInline.Link -> withLink(
                    LinkAnnotation.Clickable(
                        tag = span.target,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            )
                        ),
                        linkInteractionListener = { onLinkClick(span.target) },
                    )
                ) {
                    append(span.label.ifBlank { span.target })
                }
            }
        }
    }
    Text(text = annotated, style = style, modifier = modifier)
}

@Composable
internal fun EffectBody(blocks: List<EffectBlock>, onLinkClick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is EffectBlock.Heading -> {
                    if (index > 0) Spacer(Modifier.height(14.dp))
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        thickness = 1.dp,
                    )
                }
                is EffectBlock.Paragraph -> {
                    EffectSpans(
                        spans = block.spans,
                        onLinkClick = onLinkClick,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp),
                    )
                }
                is EffectBlock.Bullet -> Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Box(
                        Modifier
                            .padding(top = 8.dp, end = 8.dp)
                            .size(4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                    )
                    EffectSpans(spans = block.spans, onLinkClick = onLinkClick)
                }
                is EffectBlock.LevelBox -> {
                    if (index > 0) Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
                            .padding(12.dp)
                    ) {
                        if (block.label.isNotBlank()) {
                            Text(
                                text = block.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (block.header.isNotBlank()) {
                            Text(
                                text = block.header,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        block.paragraphs.forEachIndexed { pIndex, spans ->
                            EffectSpans(
                                spans = spans,
                                onLinkClick = onLinkClick,
                                modifier = Modifier.padding(top = if (pIndex == 0) 0.dp else 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
