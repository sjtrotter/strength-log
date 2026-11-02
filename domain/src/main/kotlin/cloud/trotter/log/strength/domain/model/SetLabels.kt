package cloud.trotter.log.strength.domain.model

sealed interface SetLabel {
    data class Ramp(val number: Int) : SetLabel
    data object Top : SetLabel
    data object Backoff : SetLabel
    data class Ordinal(val number: Int) : SetLabel
}

object SetLabels {
    fun of(kinds: List<SetKind>): List<SetLabel> {
        var ramp = 0
        return kinds.mapIndexed { index, kind ->
            when (kind) {
                SetKind.RAMP -> SetLabel.Ramp(++ramp)
                SetKind.TOP -> SetLabel.Top
                SetKind.BACKOFF -> SetLabel.Backoff
                SetKind.WORK, SetKind.EXTRA -> SetLabel.Ordinal(index + 1)
            }
        }
    }
}
