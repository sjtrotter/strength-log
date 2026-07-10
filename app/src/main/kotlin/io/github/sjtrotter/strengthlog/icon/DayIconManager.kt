package io.github.sjtrotter.strengthlog.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The `Launcher_Day*` activity-alias names declared in the manifest, in day order. */
private val ALIAS_NAMES = ('A'..'G').map { "Launcher_Day$it" }

/** Day A (index 0) is the one alias shipped `enabled="true"`, so its default
 *  component state already means "enabled". */
private const val DEFAULT_ENABLED_INDEX = 0

/** Whether a given alias should be enabled for the incoming day index. */
data class AliasState(val alias: String, val enabled: Boolean)

/**
 * Whether [applyDayIcon] actually needs to rewrite component states, given the
 * target alias's current [PackageManager] setting. Rewriting states that are
 * already correct still triggers a launcher re-scan — the brief "app re-appears
 * in the launcher" flicker the icon handoff warns about — and the suggested-day
 * flow re-emits the current day on every app launch, so without this guard the
 * flicker would fire on every open. Skip when the target alias is already the
 * enabled one; the manifest ships Day A enabled-by-default (state DEFAULT, not
 * explicit ENABLED), so DEFAULT on Day A also counts as already-correct.
 */
fun shouldReapplyIcon(targetIndex: Int, currentTargetState: Int): Boolean {
    val alreadyEnabled =
        currentTargetState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (currentTargetState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT &&
                targetIndex == DEFAULT_ENABLED_INDEX)
    return !alreadyEnabled
}

/**
 * Maps a rotation-day id (the single letter [ProgramDay.id] uses, e.g. `"C"`) to the
 * 0-based index the launcher-icon aliases are keyed by, or null if it isn't one of the
 * 7 shipped day icons (A-G) — the wizard caps programs at 6 days, so this only guards
 * against unexpected input, not a real program.
 */
fun dayIndexForIcon(dayId: String?): Int? {
    val letter = dayId?.singleOrNull() ?: return null
    val index = letter - 'A'
    return index.takeIf { it in ALIAS_NAMES.indices }
}

/**
 * Pure decision: given the incoming day index, which of the 7 launcher aliases should
 * be enabled vs. disabled. Exactly one is enabled at a time.
 */
fun aliasStatesFor(dayIndex: Int): List<AliasState> =
    ALIAS_NAMES.mapIndexed { i, alias -> AliasState(alias, enabled = i == dayIndex) }

/**
 * The order [applyDayIcon] must issue its `setComponentEnabledSetting` calls in: the
 * target alias (enabled) first, then the rest (disabled). Each call commits individually,
 * so a process death between calls is possible; enabling the target before disabling
 * anything else means that window can only ever be observed as "two aliases enabled"
 * (harmless — backward transitions already tolerate it, and the next apply's disables
 * resolve it), never as zero enabled aliases, which would leave the app with no launcher
 * entry and no way to reach the self-heal in [shouldReapplyIcon].
 */
fun applicationOrderFor(dayIndex: Int): List<AliasState> =
    aliasStatesFor(dayIndex).sortedByDescending { it.enabled }

/**
 * Swaps the home-screen launcher icon to match the current rotation day (#22) by
 * enabling exactly one `Launcher_Day*` activity-alias and disabling the rest.
 * `DONT_KILL_APP` keeps this invisible to the running process.
 */
class DayIconManager @Inject constructor(@ApplicationContext private val context: Context) {

    fun applyDayIcon(dayId: String?) {
        val dayIndex = dayIndexForIcon(dayId) ?: return
        val pm = context.packageManager
        val pkg = context.packageName

        // No-op when the target alias is already the enabled one, so a launch
        // (which re-emits the current day) doesn't needlessly re-scan the launcher.
        val currentState = pm.getComponentEnabledSetting(
            ComponentName(pkg, "$pkg.${ALIAS_NAMES[dayIndex]}"),
        )
        if (!shouldReapplyIcon(dayIndex, currentState)) return

        // Enable-first: see applicationOrderFor for why this ordering matters.
        for ((alias, enabled) in applicationOrderFor(dayIndex)) {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(
                ComponentName(pkg, "$pkg.$alias"),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
