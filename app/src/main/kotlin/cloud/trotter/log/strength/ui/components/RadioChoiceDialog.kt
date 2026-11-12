package cloud.trotter.log.strength.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import cloud.trotter.log.strength.R

@Composable
internal fun <T> RadioChoiceDialog(title: String, detail: String?, choices: List<Pair<T, String>>,
                                   selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.selectableGroup()) {
            if (detail != null) Text(detail)
            choices.forEach { (value, label) ->
                ListItem(headlineContent = { Text(label) },
                    leadingContent = { RadioButton(selected = selected == value, onClick = null) },
                    modifier = Modifier.fillMaxWidth().selectable(selected == value, role = Role.RadioButton,
                        onClick = { onPick(value); onDismiss() }))
            }
        }
    }, confirmButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.setup_cancel_button)) }
    })
}
