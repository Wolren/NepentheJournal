package app.journal.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Wraps a single [Text] in a [SelectionContainer] so the user can
 * copy text on desktop. Safe to use on individual [Text] composables
 * — do NOT wrap LazyColumn, Box, or other layout containers (crashes
 * Skia software renderer on this machine).
 */
@Composable
fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    SelectionContainer(modifier = modifier) {
        Text(
            text = text,
            style = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style,
            maxLines = maxLines
        )
    }
}
