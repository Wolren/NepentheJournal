package app.journal.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import app.journal.util.isDesktopPlatform

/** Text that can be copied on desktop. Selection has no use on touch, where it adds a container per text. */
@Composable
fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val resolved = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style
    if (!isDesktopPlatform()) {
        Text(text = text, modifier = modifier, style = resolved, maxLines = maxLines)
        return
    }
    SelectionContainer(modifier = modifier) {
        Text(text = text, style = resolved, maxLines = maxLines)
    }
}
