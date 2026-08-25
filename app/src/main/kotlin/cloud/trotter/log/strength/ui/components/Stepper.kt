package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.StepperGlyph
import cloud.trotter.log.strength.ui.theme.StepperRepsValue
import cloud.trotter.log.strength.ui.theme.StepperValue
import cloud.trotter.log.strength.ui.theme.Surface2
import cloud.trotter.log.strength.ui.theme.Surface3
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance

private val CapsuleShape = RoundedCornerShape(8.dp)

/** Long-press auto-repeat timing (A7): a deliberate pause before the first
 *  repeat so a normal tap never double-fires, then a steady interval that
 *  reads as "held down", not stuttery. */
private const val LONG_PRESS_INITIAL_DELAY_MS = 400L
private const val LONG_PRESS_REPEAT_INTERVAL_MS = 90L
private const val LONG_PRESS_FAST_INTERVAL_MS = 45L
private const val LONG_PRESS_FAST_AFTER_STEPS = 8

internal fun resolveStepperCommit(
    draft: String,
    minValue: Double,
    maxValue: Double,
    round: (Double) -> Double,
): Double? = draft.trim().toDoubleOrNull()
    ?.takeIf(Double::isFinite)
    ?.let(round)
    ?.coerceIn(minValue, maxValue)

/**
 * The ± stepper capsule used for both set weight and set reps (design-pass
 * restyle: docs/design-handoff, `.stp` in the reference — a single pill, not
 * two separate buttons). This composable only knows about layout; every
 * caller supplies [step] (how big a tap is), [round] (snap-to-grid, applied
 * to every stepped value before it is clamped to [minValue] and emitted), and
 * [format] (how the value reads). Weight callers must pass
 * [WeightStepper.increment]/[WeightStepper.round]/[WeightStepper.format] —
 * see the previews below — so unit-aware rounding stays defined once, in
 * `:domain`. Note [WeightStepper.round] never returns below one increment, so
 * for weight callers the default minValue of 0.0 is effectively unreachable —
 * the domain floor wins, by design.
 *
 * [valueTextStyle]/[valueMinWidth] default to the weight-numeral presentation
 * (`display2`/52dp, the row's hero number); reps callers pass the smaller
 * `display3`/36dp pair. Both default so every pre-restyle call site (which
 * only named `value`/`onValueChange`/`step`/`minValue`/`format`/`round`)
 * keeps compiling unchanged. [valueColor] defaults to [TextPrimary]; the day
 * screen's cascade flash animates it to the day accent and back (see
 * `SetRow`), driving the number-level half of the flash while the row
 * background drives the other half.
 *
 * [decreaseDescription]/[increaseDescription] are the TalkBack accessible
 * names for the − / + segments (A7); they default to the bare verbs but
 * callers that know what they're stepping (weight vs. reps, see `SetRow`)
 * should pass something more specific, e.g. "Decrease weight".
 *
 * Long-press auto-repeat (A7): holding either segment repeats [onValueChange]
 * — the very call a single tap makes, so min/round clamp identically — after
 * [LONG_PRESS_INITIAL_DELAY_MS], then every [LONG_PRESS_REPEAT_INTERVAL_MS],
 * accelerating to [LONG_PRESS_FAST_INTERVAL_MS] after eight repeated steps.
 * A quick tap never reaches the initial delay, so it fires exactly once, via
 * the ordinary click path; holding suppresses that path's own click at
 * release so a long press doesn't tack on one extra step (see [StepSegment]).
 * No new persisted state — the repeat is entirely transient press-driven UI
 * state, gone the moment the finger lifts.
 *
 * Material 3 has no compound stepper with this shared capsule, value field,
 * overlapping minimum touch targets, and long-press auto-repeat behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Stepper(
    value: Double,
    onValueChange: (Double) -> Unit,
    step: (Double) -> Double,
    modifier: Modifier = Modifier,
    minValue: Double = 0.0,
    format: (Double) -> String = { it.toString() },
    round: (Double) -> Double = { it },
    valueTextStyle: TextStyle = StepperValue,
    valueMinWidth: Dp = 52.dp,
    valueColor: Color = TextPrimary,
    decreaseDescription: String? = null,
    increaseDescription: String? = null,
    inputLabel: String? = null,
    inputUnit: String = "",
    maxValue: Double = Double.POSITIVE_INFINITY,
    decimalInput: Boolean = false,
    onNext: (() -> Unit)? = null,
    editorRequest: Int = 0,
) {
    val view = LocalView.current
    val resolvedDecreaseDescription = decreaseDescription ?: stringResource(R.string.stepper_decrease_action)
    val resolvedIncreaseDescription = increaseDescription ?: stringResource(R.string.stepper_increase_action)
    val resolvedInputLabel = inputLabel ?: stringResource(R.string.stepper_value_label)
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var handledEditorRequest by rememberSaveable { mutableIntStateOf(editorRequest) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val text = format(value)
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val typeDescription = stringResource(R.string.stepper_type_action, resolvedInputLabel)

    fun openEditor() {
        val text = format(value)
        draft = TextFieldValue(text, TextRange(0, text.length))
        editorOpen = true
    }

    fun commit(advance: Boolean) {
        val committed = resolveStepperCommit(draft.text, minValue, maxValue, round) ?: return
        onValueChange(committed)
        editorOpen = false
        if (advance) onNext?.invoke()
    }
    LaunchedEffect(editorRequest) {
        if (editorRequest > 0 && editorRequest != handledEditorRequest) {
            handledEditorRequest = editorRequest
            openEditor()
        }
    }
    Row(
        modifier = modifier
            // heightIn(min), not height (A7 font-scale): the numeral must grow past its 40dp floor.
            .heightIn(min = 40.dp)
            .clip(CapsuleShape)
            .background(Surface2)
            .border(1.dp, Border, CapsuleShape)
            // Typing is a custom action on the stepper, not a third click target:
            // the ± segments already claim 48dp each, and a button between them
            // would have to share pixels with both (TouchTargetTest).
            .semantics { customActions = listOf(CustomAccessibilityAction(typeDescription) { openEditor(); true }) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepSegment(symbol = "−", contentDescription = resolvedDecreaseDescription) {
            val next = round(value - step(value)).coerceIn(minValue, maxValue)
            AppHaptics.perform(view, if (next == value) AppHaptics.Cue.BOUNDARY else AppHaptics.Cue.STEP_DETENT)
            if (next != value) onValueChange(next)
        }
        Text(
            text = format(value),
            modifier = Modifier
                .widthIn(min = valueMinWidth)
                .pointerInput(Unit) { detectTapGestures { openEditor() } }
                .padding(horizontal = 2.dp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            color = valueColor,
            style = valueTextStyle,
        )
        StepSegment(symbol = "+", contentDescription = resolvedIncreaseDescription) {
            val next = round(value + step(value)).coerceIn(minValue, maxValue)
            AppHaptics.perform(view, if (next == value) AppHaptics.Cue.BOUNDARY else AppHaptics.Cue.STEP_DETENT)
            if (next != value) onValueChange(next)
        }
    }
    if (editorOpen) {
        AppModalBottomSheet(onDismissRequest = { editorOpen = false }, sheetState = sheetState) {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            val suffixContent: (@Composable () -> Unit)? = if (inputUnit.isBlank()) null else {
                { Text(inputUnit) }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    label = { Text(resolvedInputLabel) },
                    suffix = suffixContent,
                    supportingText = {
                        val minimum = format(maxOf(minValue, round(minValue)))
                        Text(
                            if (maxValue.isFinite()) {
                                stringResource(R.string.stepper_valid_range, minimum, format(maxValue))
                            } else {
                                stringResource(R.string.stepper_valid_range_unbounded, minimum)
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (decimalInput) KeyboardType.Decimal else KeyboardType.Number,
                    ),
                )
                Row(Modifier.align(Alignment.End)) {
                    if (onNext != null) {
                        TextButton(onClick = { commit(advance = true) }) {
                            Text(stringResource(R.string.stepper_next_button))
                        }
                    }
                    TextButton(onClick = { commit(advance = false) }) {
                        Text(stringResource(R.string.stepper_done_button))
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

/**
 * One 32dp-wide capsule segment (`.sb` in the reference); the clickable area
 * expands to Material's >= 48dp touch minimum via [minimumInteractiveComponentSize],
 * overlapping into the neighboring segment/value the same way the old
 * per-button implementation did. Press flashes [Surface3] for 120ms
 * (design tokens: `--dur-fast`), matching `.sb:active`.
 *
 * [contentDescription] is the accessible name (TalkBack never reads the raw
 * −/+ glyph — [clearAndSetSemantics] silences the inner [Text] and the
 * outer [Modifier.semantics] carries the real description instead).
 *
 * The [LaunchedEffect] below drives long-press auto-repeat: a press starts the
 * timing, and the release that follows cancels it (`collectLatest` tears the
 * delay/loop down), so letting go always stops the repeat immediately.
 * [repeated] latches once the first repeat fires so the release-triggered
 * `clickable` click — which still occurs, since holding-without-dragging is
 * still a valid tap in Compose's gesture detector — is skipped instead of
 * appending one extra, unrepeatable step.
 *
 * The interaction source also drives the authored [Surface3] pressed fill.
 */
@Composable
private fun StepSegment(symbol: String, contentDescription: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // The effect below outlives any one value of [onClick] (it is keyed on the
    // source, which never changes), and the lambda it must call is the *current*
    // one — a repeat that stepped from the value the row had when it first
    // composed would walk the number backwards.
    val currentOnClick by rememberUpdatedState(onClick)
    val repeated = remember { booleanArrayOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.filterIsInstance<PressInteraction>().collectLatest { interaction ->
            if (interaction !is PressInteraction.Press) return@collectLatest
            repeated[0] = false
            delay(LONG_PRESS_INITIAL_DELAY_MS)
            var repeatCount = 0
            while (true) {
                repeated[0] = true
                currentOnClick()
                repeatCount++
                delay(if (repeatCount >= LONG_PRESS_FAST_AFTER_STEPS) LONG_PRESS_FAST_INTERVAL_MS else LONG_PRESS_REPEAT_INTERVAL_MS)
            }
        }
    }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .pressable(
                interactionSource = interactionSource,
                onClickLabel = contentDescription,
                onClick = { if (!repeated[0]) onClick() },
            )
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.width(32.dp).fillMaxHeight().background(if (pressed) Surface3 else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = symbol, color = TextSecondary, style = StepperGlyph, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WeightStepperPreview() {
    AppTheme {
        var weightLb by remember { mutableDoubleStateOf(135.0) }
        Stepper(
            value = weightLb,
            onValueChange = { weightLb = it },
            step = { WeightStepper.increment(it, WeightUnit.LB) },
            format = WeightStepper::format,
            round = { WeightStepper.round(it, WeightUnit.LB) },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RepsStepperPreview() {
    AppTheme {
        var reps by remember { mutableDoubleStateOf(8.0) }
        Stepper(
            value = reps,
            onValueChange = { reps = it },
            step = { 1.0 },
            minValue = 1.0,
            format = { it.toInt().toString() },
            valueTextStyle = StepperRepsValue,
            valueMinWidth = 36.dp,
        )
    }
}
