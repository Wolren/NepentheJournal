package app.journal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Centered "not found" state shared by the detail screens. [onBack] adds
 * the escape button where the screen has one; screens without a back
 * action pass nothing.
 */
@Composable
internal fun NotFoundBox(text: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onBack != null) {
                Spacer(Modifier.height(12.dp))
                AppButton(onClick = onBack) { Text("Go back") }
            }
        }
    }
}
