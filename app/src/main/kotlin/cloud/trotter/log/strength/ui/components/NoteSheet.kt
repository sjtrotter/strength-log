package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.day.receipt.NoteSave

/** Closes on Save. */
@Composable
fun NoteSheet(initialText: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    NoteSheetContent(initialText, onSave, onDismiss, save = null, dismissOnSave = true)
}

/** Stays open until [save] reports the write: null while it is pending, [NoteSave.Saved] closes,
 *  [NoteSave.Failed] offers one Try again action inside the sheet. */
@Composable
fun NoteSheet(initialText: String, onSave: (String) -> Unit, save: NoteSave?, onDismiss: () -> Unit) {
    NoteSheetContent(initialText, onSave, onDismiss, save, dismissOnSave = false)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NoteSheetContent(initialText: String, onSave: (String) -> Unit, onDismiss: () -> Unit, save: NoteSave?, dismissOnSave: Boolean) {
    var text by rememberSaveable(initialText) { mutableStateOf((save as? NoteSave.Failed)?.text ?: initialText) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(save) {
        if (save is NoteSave.Saved) currentOnDismiss()
    }
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        footer = {
            SheetCommitButton(
                label = stringResource(if (save is NoteSave.Failed) R.string.receipt_try_again else R.string.sheet_save_note),
                onClick = { onSave(text); if (dismissOnSave) onDismiss() },
            )
        },
        body = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(NOTE_MAX_LENGTH) },
                label = { Text(stringResource(R.string.sheet_note_label)) },
                minLines = 1,
                maxLines = 3,
                singleLine = false,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (save is NoteSave.Failed) {
                Text(stringResource(R.string.sheet_note_failed), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

const val NOTE_MAX_LENGTH = 120
