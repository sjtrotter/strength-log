package cloud.trotter.log.strength.data.prefs

/** The warm-up and cool-down switches (default on) and the run each sheet was last closed for. */
data class RoutinePrefs(
    val warmUp: Boolean = true,
    val coolDown: Boolean = true,
    val warmUpClosedRun: String? = null,
    val coolDownClosedRun: String? = null,
)
