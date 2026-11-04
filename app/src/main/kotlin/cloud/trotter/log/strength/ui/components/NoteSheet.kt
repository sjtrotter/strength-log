package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.day.receipt.NoteSave

/** Closes on Save. */
@Composable
fun NoteSheet(initialText: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    NoteSheetContent(initialText, onSave, onDismiss, save = null, dismissOnSave = true)
}

/** Stays open until [save] reports the write: null while it is pending, [NoteSave.Saved] closes,
 *  [NoteSave.Failed] offers Try again in a snackbar inside the sheet (a modal sheet is its own
 *  window, so a host behind it could not be tapped). */
@Composable
fun NoteSheet(initialText: String, onSave: (String) -> Unit, save: NoteSave?, onDismiss: () -> Unit) {
    NoteSheetContent(initialText, onSave, onDismiss, save, dismissOnSave = false)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NoteSheetContent(initialText: String, onSave: (String) -> Unit, onDismiss: () -> Unit, save: NoteSave?, dismissOnSave: Boolean) {
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    val snackbarHost = remember { SnackbarHostState() }
    val currentOnSave by rememberUpdatedState(onSave)
    val currentSave by rememberUpdatedState(save)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val failure = stringResource(R.string.receipt_note_not_saved)
    val retry = stringResource(R.string.receipt_try_again)
    LaunchedEffect(save) {
        when (save) {
            is NoteSave.Saved -> currentOnDismiss()
            is NoteSave.Failed -> {
                do {
                    val result = snackbarHost.showSnackbar(failure, actionLabel = retry, duration = SnackbarDuration.Indefinite)
                    if (result != SnackbarResult.ActionPerformed) break
                    currentOnSave(text)
                } while (currentSave is NoteSave.Failed)
            }
            null -> Unit
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            SnackbarHost(snackbarHost)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(NOTE_MAX_LENGTH) },
                label = { Text(stringResource(R.string.note_label)) },
                minLines = 1,
                maxLines = 3,
                singleLine = false,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { onSave(text); if (dismissOnSave) onDismiss() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) { Text(stringResource(R.string.note_done), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

const val NOTE_MAX_LENGTH = 120
