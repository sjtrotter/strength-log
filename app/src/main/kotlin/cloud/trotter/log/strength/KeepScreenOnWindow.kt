package cloud.trotter.log.strength

import cloud.trotter.log.strength.data.OpenRun
import cloud.trotter.log.strength.domain.theme.KeepScreenOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** The window's leg of [KeepScreenOn.holds]: a started, not draft, workout is open. DayScreen's view holds the timer leg. */
internal fun keepScreenOnWindowFlow(setting: Flow<KeepScreenOn>, openRun: Flow<OpenRun?>): Flow<Boolean> =
    combine(setting, openRun) { choice, run -> choice.holds(workoutOpen = run != null && !run.draft, timerRunning = false) }
        .distinctUntilChanged()
