package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NoteSheet(initialText: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
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
                onClick = { onSave(text); onDismiss() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) { Text(stringResource(R.string.note_done), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

const val NOTE_MAX_LENGTH = 120
