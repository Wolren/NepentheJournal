package app.journal.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.ui.theme.TagColors

@Composable
fun TagChip(
    tag: String,
    modifier: Modifier = Modifier
) {
    val bg = TagColors.background(tag)
    val fg = TagColors.foreground(tag)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = bg.copy(alpha = 0.85f)
    ) {
        Text(
            text = tag,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
