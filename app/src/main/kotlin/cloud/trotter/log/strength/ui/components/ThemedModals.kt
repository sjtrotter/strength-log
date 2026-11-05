package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** M3 has no app-level hook that pins dialog role defaults for every call site. */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}

/** M3 has no app-level hook for the flat sheet surface, scrim, and drag handle policy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    title: (@Composable () -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // Explicit-but-identical to today's rendering: ExpandedShape rounds only
        // the top corners (full extraLarge would round the bottom edge), and the
        // default scrim is the scrim role AT 32% — the raw role is opaque.
        shape = BottomSheetDefaults.ExpandedShape,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = BottomSheetDefaults.ScrimColor,
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        // The sheet consumes its default system insets; these modifiers add only
        // the unconsumed navigation/IME space around the common layout.
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            if (body != null) {
                AppSheetLayout(title = title, footer = footer, body = body)
            } else {
                title?.invoke()
                content()
                footer?.let { Column(Modifier.fillMaxWidth().padding(16.dp)) { it() } }
            }
        }
    }
}

/** M3 has no shared pinned footer and scrolling body layout for sheet pages. */
@Composable
internal fun AppSheetLayout(
    title: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        title?.let { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { it() } }
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = body,
        )
        footer?.let { Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { it() } }
    }
}

/** M3 has no shared sheet-footer policy for Cancel beside a full-width commit. */
@Composable
internal fun SheetCommitButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    modifier: Modifier = Modifier,
    cancelLabel: String? = null,
    onCancel: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cancelLabel != null) TextButton(onClick = onCancel) { Text(cancelLabel) }
        Button(onClick = onClick, enabled = enabled, colors = colors,
            modifier = modifier.weight(1f).heightIn(min = 56.dp)) { Text(label) }
    }
}
