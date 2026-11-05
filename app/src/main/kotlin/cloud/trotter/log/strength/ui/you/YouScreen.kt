package cloud.trotter.log.strength.ui.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.BodyweightInput
import cloud.trotter.log.strength.ui.components.NumberField
import cloud.trotter.log.strength.ui.theme.ReadableWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouScreen(state: YouUiState, actions: YouActions) {
    val focusManager = LocalFocusManager.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.you_title)) },
                navigationIcon = {
                    IconButton(onClick = { focusManager.clearFocus(); actions.onBack() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.setup_back_description))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = ReadableWidth).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "bodyweight") { BodyweightCard(state.config.bodyweightLb, state.unit, actions.onBodyweightChange) }
                item(key = "age") { AgeCard(state.config.age, actions.onAgeChange) }
                item(key = "level") { LevelSection(state.config.level, actions.onLevelChange) }
                item(key = "goals_note") {
                    Text(
                        stringResource(R.string.you_goals_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BodyweightCard(canonicalLb: Int, unit: WeightUnit, onChange: (Double) -> Unit) {
    NumberField(
        value = BodyweightInput.display(canonicalLb, unit),
        onValueChange = { onChange(BodyweightInput.display(BodyweightInput.canonicalLb(it, unit), unit)) },
        label = stringResource(R.string.form_bodyweight_label, unit.name.lowercase()),
        minValue = 1.0,
        format = WeightStepper::format,
        round = { WeightStepper.round(it, unit) },
        decimalInput = true,
        weightInput = true,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun AgeCard(age: Int, onChange: (Int) -> Unit) {
    NumberField(
        value = age.toDouble(),
        onValueChange = { onChange(it.toInt()) },
        label = stringResource(R.string.form_age_label),
        minValue = 1.0,
        round = { Math.round(it).toDouble() },
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun LevelSection(level: ExperienceLevel, onChange: (ExperienceLevel) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.setup_experience_level_label), modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleSmall)
        ExperienceLevel.entries.forEach { entry ->
            ListItem(
                headlineContent = { Text(levelLabel(entry)) },
                leadingContent = { RadioButton(selected = level == entry, onClick = null) },
                colors = ListItemDefaults.colors(containerColor = if (level == entry) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = level == entry, role = Role.RadioButton, onClick = { onChange(entry) }),
            )
        }
    }
}

@Composable
private fun levelLabel(level: ExperienceLevel): String = when (level) {
    ExperienceLevel.NOVICE -> stringResource(R.string.setup_level_novice)
    ExperienceLevel.INTERMEDIATE -> stringResource(R.string.setup_level_intermediate)
    ExperienceLevel.ADVANCED -> stringResource(R.string.setup_level_advanced)
}
