package app.journal.ui.safer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import app.journal.ui.components.DesktopScrollbar
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.ui.components.*

@Composable
fun SaferScreen() {
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            state = scrollState
        ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("Safer Use", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text("Harm reduction principles and external resources",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp))
                        Text("Important", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Spacer(Modifier.height(6.dp))
                    SelectableText(
                        text = "This app is not a medical device and does not diagnose, treat, cure, " +
                        "or prevent any medical condition. The information provided is for harm " +
                        "reduction and educational purposes only. Always consult a qualified " +
                        "healthcare professional for medical advice.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                        )
                    )
                }
            }
        }

        item {
            SaferSection(
                title = "Test Your Substances",
                icon = Icons.Default.Science,
                body = "Reagent testing helps identify substances and detect adulterants. " +
                       "Common reagents: Marquis, Mandelin, Mecke, Ehrlich, Froehde. " +
                       "Use multiple reagents to cross-check results, as no single reagent is definitive. " +
                       "Test the full amount you are about to consume, not just a sample from the batch. " +
                       "Fentanyl test strips are recommended wherever opioids are involved. " +
                       "Dancesafe provides testing resources and harm reduction education."
            )
        }

        item {
            SaferSection(
                title = "Dosing Protocol",
                icon = Icons.Default.Speed,
                body = "Every substance is dose-response: more is not better, it is riskier. " +
                       "Start with a low dose and wait for the full onset before deciding whether to take more. " +
                       "Onset can take longer than expected, especially orally, so waiting is part of the protocol. " +
                       "Redosing too early or too often is a common cause of overdose. " +
                       "Check the Dosage section of each substance in this app for typical ranges, " +
                       "and never mix substances when trying something for the first time."
            )
        }

        item {
            SaferSection(
                title = "Set and Setting",
                icon = Icons.Default.Nightlight,
                body = "Your mindset (set) and physical/social environment (setting) strongly shape your " +
                       "experience. Choose a comfortable, familiar space. Be in a stable mental state. " +
                       "For higher doses or unfamiliar substances, having a sober sitter is advised."
            )
        }

        item {
            SaferSection(
                title = "Hydration and Nutrition",
                icon = Icons.Default.LocalDrink,
                body = "Stay hydrated before, during, and after sessions. Drink water regularly, " +
                       "but avoid overhydration. Balance electrolytes appropriately. " +
                       "Some substances affect body temperature and fluid regulation."
            )
        }

        item {
            SaferSection(
                title = "Avoid Dangerous Combinations",
                icon = Icons.Default.Warning,
                body = "Combining substances can increase risks. Research potential interactions " +
                       "before combining any substances. When in doubt, avoid mixing."
            )
        }

        item {
            SaferSection(
                title = "Don't Drive or Operate Machinery",
                icon = Icons.Default.Block,
                body = "Many substances impair coordination, reaction time, and judgment, even " +
                       "after the acute effects fade. Do not drive, operate heavy machinery, or " +
                       "perform safety-critical tasks while under the influence. Impairment can " +
                       "persist into the next day depending on the substance and dosage."
            )
        }

        item {
            SaferSection(
                title = "Know Your Substances",
                icon = Icons.Default.MenuBook,
                body = "Research each substance before use: typical duration, onset timing, " +
                       "common effects, and potential risks. Understand tolerance and cross-tolerance " +
                       "patterns between related substances."
            )
        }

        item {
            SaferSection(
                title = "Recovery Position",
                icon = Icons.Default.Accessibility,
                body = "If someone is unconscious but breathing, place them in the recovery position: " +
                       "on their side with the top leg bent, head tilted back slightly to keep the " +
                       "airway clear. This prevents choking if they vomit. Monitor their breathing " +
                       "until help arrives. If they are not breathing, start CPR immediately."
            )
        }

        // External resources section
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp))
                        Text("Harm Reduction Resources",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.height(8.dp))
                    SelectableText(
                        text = "The following external sites provide reliable harm reduction information:",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    resources.forEach { res ->
                        Spacer(Modifier.height(8.dp))
                        ResourceLink(res.name, res.url, res.description) {
                            uriHandler.openUri(res.fullUrl)
                        }
                    }
                }
            }
        }

        // Need help section
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp))
                        Text("Need help?", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.height(6.dp))
                    SelectableText(
                        text = "If you are concerned about your substance use or health, contact a " +
                        "healthcare professional or support service. In an emergency, call " +
                        "emergency services immediately.\n\n" +
                        "EU: 112  |  US: 911  |  UK: 111 (non-emergency) / 999 (emergency)",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                        )
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
        }
        DesktopScrollbar(scrollState)
    }
}

@Composable
private fun SaferSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    body: String
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp).padding(top = 2.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

private data class Resource(
    val name: String,
    val url: String,
    val description: String,
    val fullUrl: String
)

private val resources = listOf(
    Resource("DoseWiki", "dosewiki-admin.vercel.app", "In-app dosage and duration reference data", "https://dosewiki-admin.vercel.app/"),
    Resource("PsychonautWiki", "psychonautwiki.org", "Comprehensive substance information, effects, and interaction database", "https://psychonautwiki.org"),
    Resource("Erowid", "erowid.org", "Extensive library of substance experience reports and reference materials", "https://erowid.org"),
    Resource("TripSit", "tripsit.me", "Real-time harm reduction chat, combination charts, and substance fact sheets", "https://tripsit.me"),
    Resource("Dancesafe", "dancesafe.org", "Reagent testing kits, fentanyl test strips, and harm reduction education", "https://dancesafe.org"),
    Resource("RollSafe", "rollsafe.org", "MDMA-specific harm reduction and supplementation guidelines", "https://rollsafe.org"),
)

@Composable
private fun ResourceLink(
    name: String,
    url: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.OpenInNew, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp))
        }
    }
}
