package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.health.ExternalSessionRow
import cloud.trotter.log.strength.ui.text.UiText

/** Immutable render model for the Log screen (PLAN.md A1, issue #14, extended by
 *  the #17 Health Connect read path and the journal sections, docs/briefs/journal.md). */
data class LogUiState(
    val sessions: List<SessionListItem> = emptyList(),
    val journal: JournalUiState = JournalUiState(),
    val health: HealthSectionUi = HealthSectionUi(),
    /** Whether there is a program to start a session from — read only by the
     *  empty state (#127), which must not offer a workout that would land on
     *  NO PROGRAM YET. Defaults true because the journal is only reachable from
     *  Today, which itself only offers it once a program has resolved: for the
     *  frame before this screen's own flows answer, "there is one" is the
     *  correct assumption, and the wrong one would accuse a lifter with a
     *  perfectly good program of not having it. */
    val hasProgram: Boolean = true,
)

/**
 * The Log screen's Health Connect section (#17 read path, honest status #158).
 * When no provider is installed the section stays hidden ([available] false);
 * otherwise it says which of the two states the app is actually in, because
 * "never connected" and "the grant went away" used to look identical — both
 * silently published nothing. [publishing] is the workout-write grant itself,
 * re-read on every visit: false shows the "Connect Health Connect" affordance,
 * true shows the quiet connected line plus, until it has run once, the
 * [backfill] offer (#159). The section also lists other apps' sessions (clearly
 * external) and, if the latest recorded bodyweight differs from the configured
 * one, offers [bodyweightPrompt].
 */
data class HealthSectionUi(
    val available: Boolean = false,
    /** The workout-write grant — the only permission that makes the publish path
     *  export anything. Deliberately not "any permission granted": a read-only
     *  grant hid the Connect card while nothing was ever published (#158). */
    val publishing: Boolean = false,
    val backfill: BackfillOfferUi? = null,
    val externalSessions: List<ExternalSessionRow> = emptyList(),
    val bodyweightPrompt: BodyweightPromptUi? = null,
)

/**
 * The one-shot offer to publish history that predates the Health Connect grant
 * (#159). Present only while there is something to publish and the backfill has
 * never completed; [enabled] goes false for the duration of the run so a second
 * tap can't start it twice.
 */
data class BackfillOfferUi(
    val label: UiText.LogBackfill,
    val enabled: Boolean,
)

/**
 * The "bodyweight changed — update your GOALs?" prompt (#17, A3). Surfaced, never
 * auto-applied (GOAL-vs-ACTUAL): the user chooses to apply, which updates the
 * configured bodyweight, or dismisses.
 */
data class BodyweightPromptUi(
    val currentDisplay: String,
    val healthConnectDisplay: String,
)

/**
 * One reverse-chronological row: a completed [cloud.trotter.log.strength
 * .data.db.entity.WorkoutSessionEntity] plus its set count, always visible.
 * [exerciseGroups] is populated only once the row is expanded — most rows stay
 * collapsed, so the Log screen doesn't pay for every session's sets up front.
 */
data class SessionListItem(
    val sessionId: Long,
    val dateDisplay: String,
    val dayLetter: String,
    val dayIndex: Int,
    val dayTitle: String,
    val setCount: Int,
    /** Null when the session recorded no bodyweight; the card shows no BW line. */
    val bodyweightDisplay: String?,
    val expanded: Boolean,
    val exerciseGroups: List<SessionExerciseGroup>? = null,
    val completedAt: Long = 0,
    val cardioId: Long? = null,
    val cardioSummary: String? = null,
    val cardioSemantics: String? = null,
    val cardioDuration: String? = null,
    val editing: Boolean = false,
    val undoPending: Boolean = false,
    val unit: WeightUnit = WeightUnit.LB,
)

/** One exercise's sets within an expanded session, in first-appearance order. */
data class SessionExerciseGroup(
    val exerciseName: String,
    val sets: List<SessionSetSummary>,
)

/** One logged set's display: its kind label (R1/TOP/B/O/plain number) and `w×r`. */
data class SessionSetSummary(
    val kindLabel: String,
    val weightRepsDisplay: String,
    val id: Long = 0,
    val weightLb: Double = 0.0,
    val reps: Int = 0,
    val seconds: Int = 0,
    val done: Boolean = false,
    val tracking: TrackingType = TrackingType.WEIGHTED,
)
