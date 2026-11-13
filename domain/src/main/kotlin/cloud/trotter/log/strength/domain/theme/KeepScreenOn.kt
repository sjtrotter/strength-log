package cloud.trotter.log.strength.domain.theme

/** When the phone screen stays on. Stored by name; new constants go at the end. */
enum class KeepScreenOn {
    OFF, WHILE_TIMER, DURING_WORKOUTS;

    /** [workoutOpen]: a started workout is open. [timerRunning]: a timed set or a cardio set runs. */
    fun holds(workoutOpen: Boolean, timerRunning: Boolean): Boolean = when (this) {
        OFF -> false
        WHILE_TIMER -> timerRunning
        DURING_WORKOUTS -> workoutOpen || timerRunning
    }

    companion object {
        /** The boolean this replaced: on meant during workouts; off, or never set, meant while a timer runs. */
        fun fromLegacy(on: Boolean): KeepScreenOn = if (on) DURING_WORKOUTS else WHILE_TIMER
    }
}
