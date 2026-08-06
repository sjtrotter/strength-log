package cloud.trotter.log.strength.ui.today

/** Immutable render model for the Today screen (issue #121) — the ViewModel's single output. */
data class TodayUiState(
    val hasProgram: Boolean = false,
    val dayId: String = "",
    /** 0-based rotation position — the key into `dayAccent`/`onDayAccent`. */
    val dayIndex: Int = 0,
    /** "DAY B · LOWER" ([GlanceLines.dayLine][cloud.trotter.log.strength.domain.glance.GlanceLines.dayLine]). */
    val dayLine: String = "",
    /** "NEXT IN ROTATION" / "IN PROGRESS" / "READY TO FINISH". */
    val overline: String = "",
    val emphasisLine: String = "",
    /** "5 LIFTS · 21 SETS" / "4 / 21 SETS" / "DONE · 21 SETS". */
    val statLine: String = "",
    val lifts: List<TodayLift> = emptyList(),
    val cardio: TodayCardio? = null,
    /** "START DAY B" / "CONTINUE — 4 OF 18 SETS" / "FINISH DAY B". */
    val actionLabel: String = "",
    /** "Jul 30, 2026 · 18 sets · Back Squat 245"; null with no history. */
    val lastSession: String? = null,
    val rotation: List<RotationMark> = emptyList(),
)

/** One slot of the previewed day: what it is called and how many rounds it has. */
data class TodayLift(val name: String, val setCount: Int, val isMain: Boolean)

/** The day's cardio finisher, if it has one. */
data class TodayCardio(val label: String, val detail: String, val hard: Boolean)

/** One day of the rotation rail; [isNext] marks the day Today is offering. */
data class RotationMark(val dayId: String, val dayIndex: Int, val isNext: Boolean)

/** Callbacks the screen forwards — one place, so previews stay trivial. */
data class TodayActions(
    val onStart: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenLog: () -> Unit,
)
