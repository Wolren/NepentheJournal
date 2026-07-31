package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.ui.components.CollapsibleSettingsCard

@Composable
fun SessionDemographicsSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    age: String,
    onAgeChange: (String) -> Unit,
    gender: String,
    onGenderChange: (String) -> Unit,
    heightCm: String,
    onHeightCmChange: (String) -> Unit,
    weightKg: String,
    onWeightKgChange: (String) -> Unit
) {
    CollapsibleSettingsCard(
        expanded = expanded,
        onToggle = onToggle,
        icon = Icons.Default.Person,
        title = "Demographics"
    ) {
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = age,
                onValueChange = onAgeChange,
                label = { Text("Age") },
                placeholder = { Text("25") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = gender,
                onValueChange = onGenderChange,
                label = { Text("Gender") },
                placeholder = { Text("e.g. Male, Female") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = heightCm,
                onValueChange = onHeightCmChange,
                label = { Text("Height (cm)") },
                placeholder = { Text("175") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = weightKg,
                onValueChange = onWeightKgChange,
                label = { Text("Weight (kg)") },
                placeholder = { Text("70") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
