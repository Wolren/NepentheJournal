package app.journal.ui.substances

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Substance browser. Pull-to-refresh → PsychonautWikiIngestor.fetchAndStore(query).
 * Tap → SubstanceDetailScreen: dosages, duration, interactions, effects.
 *
 * SQL++ search:
 *   SELECT * FROM substances
 *   WHERE docType = "substance" AND (name LIKE $q OR aliases LIKE $q)
 */
@Composable
fun SubstanceScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Substances", style = MaterialTheme.typography.headlineMedium)
        // TODO: SearchBar, LazyColumn of SubstanceCard, detail nav
    }
}
