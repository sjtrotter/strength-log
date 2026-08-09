package cloud.trotter.log.strength.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import cloud.trotter.log.strength.data.FullSnapshot
import kotlinx.coroutines.flow.first

/**
 * The write-ahead journal for an A2 full restore (#172).
 *
 * A restore writes two stores that share no transaction: Room (program, live
 * logs, history, custom exercises) and the settings DataStore. An interruption
 * between them leaves the restored training data paired with the *old* device's
 * bodyweight/age/level — and since every GOAL derives live from that config,
 * the result is a screen full of plausible, wrong numbers. This journal is what
 * makes that state repairable: it holds the settings half until it lands, and
 * [reconcile] finishes the job at the next launch.
 *
 * It lives in its own DataStore file, deliberately not in the settings store it
 * feeds: [SettingsStore.restore] opens with `clear()`, so a marker kept there
 * would be wiped by the very write it exists to guarantee, and its survival
 * would hang on an implementation detail of an unrelated method.
 *
 * The staged payload is written and read back through a [SettingsStore] over the
 * journal's own file, so the journal and its target share one key mapping and
 * can never drift apart (SSOT). Staging is a single atomic edit, so the file is
 * either empty or a complete payload — there is no half-staged state to detect.
 *
 * [arm] is what makes replay *safe* rather than merely eager. The payload is
 * staged before the destructive Room transaction so it is durable wherever the
 * process dies, but replaying it is only correct once that transaction has
 * committed: replaying onto data that was never replaced would pair the
 * backup's settings with the old device's training data — the same bug
 * mirrored. So [reconcile] acts only on an armed journal and discards a staged
 * one that was never armed. The residual window is the single small edit
 * between the Room commit and [arm]; closing it exactly would need a marker
 * written inside the Room transaction itself, i.e. a schema change.
 */
class RestoreJournal(
    private val dataStore: DataStore<Preferences>,
    private val settings: SettingsStore,
) {

    /** The staged payload, in the same keys it will be replayed into. */
    private val staged = SettingsStore(dataStore)

    private object Keys {
        /** Set once the destructive Room half has committed. Its absence means
         *  "nothing was replaced", which is why [reconcile] can discard rather
         *  than replay. */
        val ARMED = booleanPreferencesKey("restore_armed")
    }

    /** Stages [snapshot]'s settings half. [SettingsStore.restore]'s leading
     *  `clear()` also drops [Keys.ARMED], so staging always disarms. */
    suspend fun stage(snapshot: FullSnapshot) {
        staged.restore(
            answers = snapshot.answers,
            unit = snapshot.unit,
            wizardComplete = snapshot.wizardComplete,
            suggestedDay = snapshot.suggestedDay,
            restSettings = snapshot.restSettings,
            keepScreenOn = snapshot.keepScreenOn,
        )
    }

    /** Declares the Room half committed: from here the staged payload must land,
     *  now or at the next launch. */
    suspend fun arm() {
        dataStore.edit { it[Keys.ARMED] = true }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    /**
     * Finishes an interrupted restore, returning whether one was outstanding.
     * Run at startup; the caller must keep it from overlapping a live restore,
     * which is writing the very payload this reads.
     *
     * Idempotent by construction: it writes exactly the values a completed
     * restore would have written, so a death between the settings edit landing
     * and [clear] costs one harmless repeat next launch.
     */
    suspend fun reconcile(): Boolean {
        val prefs = dataStore.data.first()
        // A payload is required, not just the flag: [stage] always precedes [arm],
        // and replaying an empty journal would write SettingsStore's *defaults*
        // over live preferences — the one way this class could destroy data.
        val hasPayload = prefs.asMap().keys.any { it != Keys.ARMED }
        if (prefs[Keys.ARMED] != true || !hasPayload) {
            if (prefs.asMap().isNotEmpty()) clear()
            return false
        }
        settings.restore(
            answers = staged.wizardAnswersFlow.first(),
            unit = staged.unitFlow.first(),
            wizardComplete = staged.wizardCompleteFlow.first(),
            suggestedDay = staged.suggestedDayFlow.first(),
            restSettings = staged.restSettingsFlow.first(),
            keepScreenOn = staged.keepScreenOnFlow.first(),
        )
        clear()
        return true
    }
}

/**
 * A restore whose Room half committed but whose settings half did not. The
 * journal is armed when this is thrown, so the next launch finishes the job
 * ([RestoreJournal.reconcile]); it exists so the UI can say that instead of
 * blaming the file the user picked (#172).
 */
class RestoreIncompleteException(cause: Throwable) :
    Exception("Restore committed its data but not its settings", cause)
