package app.journal.ui.substances

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One readable piece of an effect article. The bundled body carries `## `
 * heading lines and `- ` bullet lines, because the source prose is flat text
 * with no other structure left.
 */
sealed interface EffectBlock {
    data class Heading(val text: String) : EffectBlock
    data class Paragraph(val text: String) : EffectBlock
    data class Bullet(val text: String) : EffectBlock
}

internal fun parseEffectBlocks(body: String): List<EffectBlock> {
    val blocks = mutableListOf<EffectBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += EffectBlock.Paragraph(text)
        paragraph.clear()
    }

    body.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> flushParagraph()
            line.startsWith("## ") -> {
                flushParagraph()
                val heading = line.removePrefix("## ").trim()
                if (heading.isNotEmpty()) blocks += EffectBlock.Heading(heading)
            }
            line.startsWith("- ") -> {
                flushParagraph()
                val item = line.removePrefix("- ").trim()
                if (item.isNotEmpty()) blocks += EffectBlock.Bullet(item)
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
        }
    }
    flushParagraph()
    return blocks
}

@Composable
internal fun EffectBody(blocks: List<EffectBlock>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is EffectBlock.Heading -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 14.dp, bottom = 4.dp),
                )
                is EffectBlock.Paragraph -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = if (index == blocks.lastIndex) 0.dp else 10.dp),
                )
                is EffectBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .padding(top = 8.dp, end = 8.dp)
                            .size(4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                    )
                    Text(text = block.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
