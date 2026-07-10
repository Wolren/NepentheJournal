package app.journal.ui.safer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.ui.components.*

@Composable
fun SaferScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("Safer Use", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text("General guidelines for harm reduction",
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
                    Text(
                        "This app is not a medical device and does not diagnose, treat, cure, " +
                        "or prevent any medical condition. The information provided is for harm " +
                        "reduction and educational purposes only. Always consult a qualified " +
                        "healthcare professional for medical advice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                    )
                }
            }
        }

        item { SaferSection(title = "Start Low, Go Slow",
            icon = Icons.Default.TrendingDown,
            body = "Always start with a low dose, especially with a new substance or batch. " +
                   "You can always take more, but you can't take less. Wait for the full onset " +
                   "before redosing. This can take 30-90 minutes depending on the substance and route.") }

        item { SaferSection(title = "Test Your Substances",
            icon = Icons.Default.Science,
            body = "Reagent testing can identify substances and detect adulterants. Common reagents: " +
                   "Marquis, Mandelin, Mecke, Ehrlich, Froehde. Test a small sample before consuming. " +
                   "Fentanyl test strips are recommended for any substance that could be contaminated.") }

        item { SaferSection(title = "Set and Setting",
            icon = Icons.Default.Nightlight,
            body = "Your mindset (set) and physical/social environment (setting) heavily influence your " +
                   "experience. Choose a comfortable, safe space. Be in a positive mental state. " +
                   "Have a trusted sitter present for higher doses or unfamiliar substances.") }

        item { SaferSection(title = "Hydration and Nutrition",
            icon = Icons.Default.LocalDrink,
            body = "Stay hydrated before, during, and after sessions. For stimulants and empathogens, " +
                   "drink water regularly (about 250-500ml per hour). Avoid overhydration. " +
                   "Eat a light meal beforehand. Some substances cause nausea on an empty stomach.") }

        item { SaferSection(title = "Avoid Dangerous Combinations",
            icon = Icons.Default.Warning,
            body = "Some combinations are dangerous: MAOIs with empathogens/serotonergics (serotonin syndrome), " +
                   "stimulants with other stimulants (cardiac strain), alcohol with CNS depressants (respiratory " +
                   "depression), and certain substance-class combinations. Check interaction databases beforehand.") }

        item { SaferSection(title = "Know Your Substances",
            icon = Icons.Default.MenuBook,
            body = "Research duration, onset, peak timing, and common effects before each session. " +
                   "Understand the difference between physical and psychological addiction potential. " +
                   "Be aware of cross-tolerances between substance classes (e.g., all psychedelics with " +
                   "5-HT2A affinity share cross-tolerance).") }

        item { SaferSection(title = "Have Emergency Contacts Ready",
            icon = Icons.Default.Phone,
            body = "Save emergency contacts: local poison control, emergency services (112 in EU / 911 in US). " +
                   "If someone is having a medical emergency: call immediately. Tell responders exactly what " +
                   "was taken, how much, and when. Medical records are confidential.") }

        item { SaferSection(title = "Tolerance Breaks",
            icon = Icons.Default.Timer,
            body = "Regular use leads to tolerance buildup and increased dose requirements, which raises " +
                   "risk. Take tolerance breaks: 2+ weeks for most substances, 1-3 months for psychedelics. " +
                   "The tolerance dashboard helps track time since last dose.") }

        item { SaferSection(title = "Integration",
            icon = Icons.Default.Psychology,
            body = "Journal your experiences to process insights. Discuss significant experiences " +
                   "with trusted friends, integration circles, or a therapist. Challenging experiences " +
                   "can be learning opportunities. Reflect on what came up and why.") }

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
                    Text(
                        "If you are concerned about your substance use or health, contact a " +
                        "healthcare professional or support service. In an emergency, call " +
                        "emergency services immediately.\n\n" +
                        "EU: 112  |  US: 911  |  UK: 111 (non-emergency) / 999 (emergency)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
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
                Text(body, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
