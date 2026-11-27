package cloud.trotter.log.strength.ui.wizard

import cloud.trotter.log.strength.domain.model.CardioActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.planner.FitnessTest
import cloud.trotter.log.strength.domain.planner.TestPrep
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestFormat
import cloud.trotter.log.strength.domain.standards.TestPick
import cloud.trotter.log.strength.domain.units.ClockText
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.NumberField

@Composable
internal fun TestStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    val draft = state.draft
    var picker by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.setup_flow_test_hint), style = MaterialTheme.typography.bodyMedium)
    if (draft.testRun) key(draft.test) {
        TestPickHeading(stringResource(testLabel(draft.test))) { actions.onTestRemove(draft.test.name) }
        TestRunFields(draft, actions)
    }
    draft.tests.forEach { test ->
        val event = TestEvents.find(test.eventId) ?: return@forEach
        val entry = ExerciseLibrary.get(event.exerciseId)
        key(entry.id) {
            TestPickHeading(entry.name) { actions.onTestRemove(event.id) }
            val formats = TestFormat.eligible(entry)
            val format = formats.singleOrNull { it.setType == event.setType } ?: return@key
            if (formats.size > 1) SetupSegments(formats.map { stringResource(testFormatLabel(it)) }, formats.indexOf(format)) {
                actions.onTestFormat(entry.id, formats[it])
            }
            val editions = TestEvents.editions(entry.id, format)
            if (editions.isNotEmpty()) ListItem(headlineContent = { Text(stringResource(R.string.setup_flow_test_army)) },
                trailingContent = { Switch(editions.any { it.id == event.id }, { on ->
                    actions.onTestEdition(entry.id, if (on) editions.first().id else null)
                }) })
            if (format == TestFormat.REPS_IN_WINDOW) {
                if (event.custom) SetupTime(test.windowSeconds, stringResource(R.string.setup_flow_test_window), minSeconds = 10,
                    maxSeconds = 3600, invalid = INVALID_GOAL_TIME) { actions.onTestWindow(event.id, it) }
                else OutlinedTextField(value = ClockText.duration(requireNotNull(event.windowSeconds)), onValueChange = {},
                    readOnly = true, label = { Text(stringResource(R.string.setup_flow_test_window)) }, modifier = Modifier.fillMaxWidth())
            }
            val limit = TestEvents.requiredLimit(event)
            val floor = if (TestPrep.easier(entry.id) != null) 0 else 1
            if (event.timed) {
                SetupTime(test.required, stringResource(R.string.setup_flow_test_required_hold), maxSeconds = limit,
                    invalid = INVALID_GOAL_TIME) { actions.onTestRequired(event.id, it) }
                SetupTime(test.lastResult, stringResource(R.string.setup_flow_test_last_hold), minSeconds = floor,
                    maxSeconds = limit, invalid = INVALID_GOAL_TIME) { actions.onTestLast(event.id, it) }
                event.ceilingSeconds?.let { Text(stringResource(R.string.workout_test_ceiling, ClockText.duration(it))) }
            } else {
                val required: (Double?) -> Unit = { actions.onTestRequired(event.id, it?.toInt()) }
                NumberField(value = test.required?.toDouble(), onValueChange = required, onDraftChange = required,
                    label = stringResource(R.string.setup_flow_test_required_reps), minValue = 1.0, maxValue = limit.toDouble())
                val last: (Double?) -> Unit = { actions.onTestLast(event.id, it?.toInt()) }
                NumberField(value = test.lastResult?.takeIf { it >= floor }?.toDouble(), onValueChange = last,
                    onDraftEdit = { text, parsed -> actions.onTestLast(event.id, when {
                        text.isBlank() -> null
                        parsed == null -> INVALID_GOAL_TIME
                        else -> parsed.toInt()
                    }) }, error = test.lastResult == INVALID_GOAL_TIME,
                    label = stringResource(R.string.setup_flow_test_last_reps), minValue = floor.toDouble(), maxValue = limit.toDouble())
            }
            if (floor == 0) TextButton(onClick = { actions.onTestLast(event.id, 0) }) {
                Text(stringResource(R.string.setup_flow_test_none_yet))
            }
            Text(event.form, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    draft.testLifts.forEach { lift -> key(lift.exerciseId) {
        TestPickHeading(ExerciseLibrary.get(lift.exerciseId).name) { actions.onTestRemove(lift.exerciseId) }
        NumberField(value = lift.reps.toDouble(), onValueChange = { actions.onLiftReps(lift.exerciseId, it.toInt()) },
            onDraftEdit = { text, _ -> actions.onLiftReps(lift.exerciseId, text.toIntOrNull() ?: 0) },
            label = stringResource(R.string.setup_flow_test_heaviest), minValue = 1.0, maxValue = 10.0)
        val unit = draft.unit
        if (ExerciseLibrary.get(lift.exerciseId).perHand) Text(stringResource(R.string.setup_flow_per_hand),
            style = MaterialTheme.typography.bodySmall)
        val change: (Double?) -> Unit = { actions.onLiftGoal(lift.exerciseId, it?.let(unit::toLb)) }
        NumberField(value = lift.goalLb?.let(unit::fromLb), onValueChange = change, onDraftChange = change,
            label = stringResource(R.string.setup_flow_test_weight, unit.name.lowercase()), minValue = unit.fromLb(0.01),
            maxValue = unit.fromLb(PlanRecipeJson.MAX_GOAL_LB), format = WeightStepper::format,
            decimalInput = true, weightInput = true)
        Text(stringResource(R.string.setup_flow_test_lift_rules), style = MaterialTheme.typography.bodySmall)
    } }
    OutlinedButton(onClick = { picker = true }) { Text(stringResource(R.string.setup_flow_test_add)) }
    if (draft.tests.isNotEmpty() || draft.testRun && draft.test != FitnessTest.COOPER &&
        (draft.test.metres != null || draft.testScore?.let { it in 1..999 } == true)) {
        SetupEventDate(draft.testDate, actions.onTestDate)
    }
    if (picker) TestPickSheet(draft, onDismiss = { picker = false }, onPick = {
        actions.onTestPick(it); picker = false
    })
}

@Composable
private fun TestPickHeading(name: String, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = remove) { Icon(Icons.Filled.Close, stringResource(R.string.setup_flow_test_remove, name)) }
    }
}

@Composable
private fun TestRunFields(draft: SetupDraft, actions: SetupFlowActions) {
    if (draft.test.metres != null) SetupTime(draft.goalSeconds, stringResource(R.string.setup_flow_test_required_time),
        minSeconds = SetupFlowStateBuilder.goalTimeFloor(draft), invalid = INVALID_GOAL_TIME, onChange = actions.onGoalSeconds)
    else {
        val change: (Double?) -> Unit = { actions.onTestScore(it?.toInt()) }
        NumberField(value = draft.testScore?.toDouble(), onValueChange = change, onDraftChange = change,
            label = stringResource(if (draft.test == FitnessTest.COOPER) R.string.setup_flow_test_target_metres else R.string.plan_goal_target_shuttles),
            minValue = 1.0, maxValue = if (draft.test == FitnessTest.COOPER) 200_000.0 else 999.0)
    }
    SetupLabel(stringResource(R.string.setup_flow_recent_label))
    SetupDistance(draft.recentMetres, CardioActivity.ROAD_RUN) {
        actions.onRecent(it, draft.recentSeconds)
    }
    SetupTime(draft.recentSeconds, stringResource(R.string.setup_flow_recent_time)) { actions.onRecent(draft.recentMetres, it) }
}

private fun testFormatLabel(format: TestFormat): Int = when (format) {
    TestFormat.REPS_IN_WINDOW -> R.string.setup_flow_test_in_window
    TestFormat.REPS_CONTINUOUS -> R.string.setup_flow_test_unbroken
    TestFormat.MAX_HOLD -> R.string.setup_flow_test_hold
    TestFormat.HEAVIEST_FOR_REPS -> R.string.setup_flow_test_score_weight
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TestPickSheet(draft: SetupDraft, onDismiss: () -> Unit, onPick: (TestPick) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(TestPickFilter.ALL) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val rows = TestPickSearch.filter(query, filter) { context.getString(testLabel(it)) }
    val searchLabel: @Composable () -> Unit = { Text(stringResource(R.string.setup_flow_test_search)) }
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = { Text(stringResource(R.string.setup_flow_test_add), style = MaterialTheme.typography.titleLarge) }, body = {
            OutlinedTextField(value = query, onValueChange = { value: String -> query = value }, singleLine = true,
                label = searchLabel, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TestPickFilter.entries.forEach { item ->
                    SetupChip(item == filter, { focus.clearFocus(); filter = item }) { Text(stringResource(when (item) {
                        TestPickFilter.ALL -> R.string.setup_flow_test_all
                        TestPickFilter.STRENGTH -> R.string.setup_flow_strength
                        TestPickFilter.CARDIO -> R.string.setup_flow_test_cardio
                        TestPickFilter.HOLDS -> R.string.setup_flow_test_holds
                    })) }
                }
            }
            rows.forEach { pick ->
                val reason = TestPickSearch.disabledReason(pick, draft)
                val name = when (pick) { is TestPick.Library -> pick.entry.name; is TestPick.Run -> context.getString(testLabel(pick.test)) }
                val score = when (pick) {
                    is TestPick.Run -> R.string.setup_flow_test_score_run
                    is TestPick.Library -> when (TestFormat.eligible(pick.entry).first()) {
                        TestFormat.MAX_HOLD -> R.string.setup_flow_test_score_hold
                        TestFormat.HEAVIEST_FOR_REPS -> R.string.setup_flow_test_score_weight
                        else -> R.string.setup_flow_test_score_reps
                    }
                }
                ListItem(headlineContent = { Text(name) }, supportingContent = { Text(stringResource(reason ?: score)) },
                    trailingContent = if (reason == R.string.setup_flow_test_picked) { { Icon(Icons.Filled.Check, null) } } else null,
                    modifier = Modifier.clickable(enabled = reason == null) { focus.clearFocus(); onPick(pick) })
            }
            Text(stringResource(R.string.setup_flow_test_unsupported), style = MaterialTheme.typography.bodySmall)
        })
}
