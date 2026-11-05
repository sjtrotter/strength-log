package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import cloud.trotter.log.strength.R

internal fun resolveNumberCommit(
    draft: String,
    minValue: Double,
    maxValue: Double,
    round: (Double) -> Double,
): Double? = draft.trim().toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= minValue && it <= maxValue }
    ?.let(round)
    ?.takeIf(Double::isFinite)
    ?.coerceIn(minValue, maxValue)

/**
 * M3 fields do not define validation or persistence on focus loss. This shares
 * that policy across forms, using the same validation for Done and focus loss.
 */
@Composable
fun NumberField(
    value: Double?,
    onValueChange: (Double) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minValue: Double = 0.0,
    maxValue: Double = Double.POSITIVE_INFINITY,
    format: (Double) -> String = { it.toInt().toString() },
    round: (Double) -> Double = { it },
    decimalInput: Boolean = false,
    weightInput: Boolean = false,
    onValidityChange: (Boolean) -> Unit = {},
    onDraftChange: (Double?) -> Unit = {},
) {
    var draft by rememberSaveable(label) { mutableStateOf(value?.let(format).orEmpty()) }
    var dirty by rememberSaveable(label) { mutableStateOf(false) }
    var pendingCommit by rememberSaveable(label) { mutableStateOf<Double?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val parsed = resolveNumberCommit(draft, minValue, maxValue, round)
    val invalid = dirty && parsed == null
    SideEffect { onValidityChange(parsed != null) }
    var seenValue by remember { mutableStateOf(value) }
    // Keyed on focus too, so a value that changed while the field was focused shows once focus leaves.
    LaunchedEffect(value, label, focused) {
        // Any new persisted value answers the field's own write, even when storage normalized it
        // (80 kg comes back as 79.83). Until it answers, the draft stays as typed; a dirty draft
        // always wins over an older write that lands mid-edit.
        if (value != seenValue) {
            seenValue = value
            pendingCommit = null
        }
        if (!dirty && pendingCommit == null && !focused) draft = value?.let(format).orEmpty()
    }
    val rangeError = if (maxValue.isFinite()) {
        stringResource(if (weightInput) R.string.form_weight_range else R.string.form_number_range,
            format(minValue), format(maxValue))
    } else {
        stringResource(if (weightInput) R.string.form_weight_minimum else R.string.form_number_minimum, format(minValue))
    }
    fun commit() {
        if (!dirty) return
        val committed = resolveNumberCommit(draft, minValue, maxValue, round) ?: return
        dirty = false
        draft = format(committed)
        // A commit equal to the stored value produces no new value to wait for.
        if (committed != value) pendingCommit = committed
        onValueChange(committed)
    }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it
            dirty = true
            onDraftChange(resolveNumberCommit(it, minValue, maxValue, round))
        },
        label = { Text(label) },
        modifier = modifier.fillMaxWidth().onFocusChanged {
            if (focused && !it.isFocused) commit()
            focused = it.isFocused
        },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) { { Text(rangeError) } } else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimalInput) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
    )
}

/** Legacy sheet parser: rounds and clamps typed edits before the sheet writes. */
internal fun resolveStepperCommit(
    draft: String,
    minValue: Double,
    maxValue: Double,
    round: (Double) -> Double,
): Double? = draft.trim().toDoubleOrNull()
    ?.takeIf(Double::isFinite)
    ?.let(round)
    ?.coerceIn(minValue, maxValue)
