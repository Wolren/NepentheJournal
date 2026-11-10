package app.journal.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import app.journal.util.isDesktopPlatform

@Composable
fun SelectableContent(
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled || !isDesktopPlatform()) {
        content()
        return
    }
    SelectionContainer(modifier = modifier) {
        content()
    }
}

/** Copyable text on desktop. Touch gains nothing and pays for a container. */
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
