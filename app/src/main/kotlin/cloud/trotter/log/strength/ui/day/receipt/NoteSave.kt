package cloud.trotter.log.strength.ui.day.receipt

sealed interface NoteSave {
    data class Saved(val text: String) : NoteSave
    data class Failed(val text: String) : NoteSave
}
