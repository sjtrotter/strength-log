package cloud.trotter.log.strength.ui.day

object WorkoutSelection {
    data class Group(val key: String, val complete: Boolean)

    sealed interface Shown {
        data class Group(val key: String, val complete: Boolean) : Shown
        data object AllLogged : Shown
        data object Empty : Shown
    }

    fun shown(groups: List<Group>, selectedKey: String?): Shown {
        val group = groups.firstOrNull { it.key == selectedKey }
            ?: groups.firstOrNull { !it.complete }
        return when {
            group != null -> Shown.Group(group.key, group.complete)
            groups.isEmpty() -> Shown.Empty
            else -> Shown.AllLogged
        }
    }

    fun afterActionLog(groups: List<Group>, loggedKey: String): String? =
        groups.firstOrNull { it.key == loggedKey && !it.complete }?.key ?: next(groups, loggedKey)

    fun next(groups: List<Group>, fromKey: String?): String? {
        val from = groups.indexOfFirst { it.key == fromKey }
        return (1..groups.size).asSequence()
            .map { groups[(from + it) % groups.size] }
            .firstOrNull { it.key != fromKey && !it.complete }?.key
    }
}
