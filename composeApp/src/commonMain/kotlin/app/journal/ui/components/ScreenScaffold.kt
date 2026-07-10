package app.journal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Reusable screen scaffold with TopAppBar and LazyColumn content.
 * Supports Esc key to go back on desktop.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    content: LazyListScope.() -> Unit
) {
    // Desktop Esc key handler
    val keyHandler = if (onBack != null) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.key == Key.Escape) {
                try { onBack() } catch (_: Exception) { }
                true
            } else false
        }
    } else Modifier

    Scaffold(
        modifier = modifier.then(keyHandler),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (navigationIcon != null) {
                        navigationIcon()
                    } else if (onBack != null) {
                        IconButton(onClick = { try { onBack() } catch (_: Exception) { } }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = actions
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = verticalArrangement,
            contentPadding = contentPadding
        ) {
            content()
        }
    }
}
