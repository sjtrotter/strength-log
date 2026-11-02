package cloud.trotter.log.strength.ui.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.components.BodyweightInput
import cloud.trotter.log.strength.ui.components.BodyweightStepper
import cloud.trotter.log.strength.ui.components.Stepper
import cloud.trotter.log.strength.ui.theme.ReadableWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouScreen(state: YouUiState, actions: YouActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.you_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack, modifier = Modifier.size(48.dp)) {
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
    BodyweightStepper(
        canonicalLb = canonicalLb,
        unit = unit,
        label = stringResource(R.string.setup_bodyweight_label, unit.name.lowercase()),
        decreaseDescription = stringResource(R.string.setup_decrease_bodyweight_description),
        increaseDescription = stringResource(R.string.setup_increase_bodyweight_description),
        onCanonicalLbChange = { onChange(BodyweightInput.display(it, unit)) },
    )
}

@Composable
private fun AgeCard(age: Int, onChange: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.setup_age_label)) },
        trailingContent = {
            Stepper(
                value = age.toDouble(),
                onValueChange = { onChange(it.toInt()) },
                step = { 1.0 },
                minValue = 1.0,
                format = { it.toInt().toString() },
                decreaseDescription = stringResource(R.string.setup_decrease_age_description),
                increaseDescription = stringResource(R.string.setup_increase_age_description),
                inputLabel = stringResource(R.string.stepper_age_label),
                inputUnit = stringResource(R.string.stepper_years_unit),
                round = { Math.round(it).toDouble() },
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelSection(level: ExperienceLevel, onChange: (ExperienceLevel) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.setup_experience_level_label)) },
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth(), space = 0.dp) {
                ExperienceLevel.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = level == entry,
                        onClick = { onChange(entry) },
                        shape = SegmentedButtonDefaults.itemShape(index, ExperienceLevel.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                        icon = {},
                    ) { Text(levelLabel(entry)) }
                }
            }
        },
    )
}

@Composable
private fun levelLabel(level: ExperienceLevel): String = when (level) {
    ExperienceLevel.NOVICE -> stringResource(R.string.setup_level_novice)
    ExperienceLevel.INTERMEDIATE -> stringResource(R.string.setup_level_intermediate)
    ExperienceLevel.ADVANCED -> stringResource(R.string.setup_level_advanced)
}
