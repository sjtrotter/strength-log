package cloud.trotter.log.strength.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.log.strength.data.FullSnapshot
import kotlinx.coroutines.flow.first

/**
 * The write-ahead journal for an A2 full restore (#172).
 *
 * A restore writes two stores that share no transaction: Room (program, live
 * logs, history, custom exercises) and the settings DataStore. An interruption
 * between them leaves the restored training data paired with the *old* device's
 * bodyweight/age/level — and since every GOAL derives live from that config, the
 * result is a screen full of plausible, wrong numbers. This journal is what
 * makes that state repairable: it holds the settings half until it lands, and
 * [reconcile] finishes the job at the next launch.
 *
 * It lives in its own DataStore file, deliberately not in the settings store it
 * feeds: [SettingsStore.restore] opens with `clear()`, so a payload kept there
 * would be wiped by the very write it exists to guarantee, and its survival
 * would hang on an implementation detail of an unrelated method.
 *
 * The payload is written and read back through a [SettingsStore] over the
 * journal's own file, so the journal and its target share one key mapping and
 * can never drift apart (SSOT). Writing it is one atomic edit, so the payload is
 * either wholly there or not there at all.
 *
 * **Whether to replay is not the journal's call alone.** Replaying onto data
 * that was never replaced would pair the backup's settings with the old device's
 * training data — the same bug mirrored — so [reconcile] takes the nonce that
 * the *Room transaction itself* committed and replays only on a match. That
 * marker is the only fact the two stores can agree on after the fact, which is
 * why it is a Room row rather than a second flag here: a flag written after the
 * transaction can be lost in the gap between two durable commits, and losing it
 * would discard the only copy of the settings half.
 */
class RestoreJournal(
    private val dataStore: DataStore<Preferences>,
    private val settings: SettingsStore,
) {

    /** The staged payload, in the same keys it will be replayed into. */
    private val staged = SettingsStore(dataStore)

    private object Keys {
        /** Ties this payload to the Room transaction that carries the same value.
         *  Written after the payload (a [SettingsStore.restore] clears the file
         *  first, so it cannot ride along in that edit) — which is safe, because
         *  a torn stage happens strictly before anything destructive runs and is
         *  discarded by [reconcile] like any other unmatched payload. */
        val NONCE = stringPreferencesKey("restore_nonce")
    }

    /** Stages [snapshot]'s settings half under [nonce], replacing anything the
     *  journal held. Must complete before the destructive Room transaction. */
    suspend fun stage(snapshot: FullSnapshot, nonce: String) {
        staged.restore(
            answers = snapshot.answers,
            unit = snapshot.unit,
            wizardComplete = snapshot.wizardComplete,
            suggestedDay = snapshot.suggestedDay,
            restSettings = snapshot.restSettings,
            keepScreenOn = snapshot.keepScreenOn,
            topSetHelperSeen = snapshot.topSetHelperSeen,
            supersetHelperSeen = snapshot.supersetHelperSeen,
            themePreference = snapshot.themePreference,
        )
        dataStore.edit { it[Keys.NONCE] = nonce }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    /**
     * Finishes an interrupted restore, returning whether it replayed one.
     *
     * [committedNonce] is what the restore's Room transaction durably recorded,
     * or null if no such transaction ever committed. The staged payload is
     * replayed only when the two nonces match; on any other combination the
     * journal is dropped, because the payload either belongs to a transaction
     * that never happened or to a restore that already finished.
     *
     * Idempotent: it writes exactly the values a completed restore would have
     * written, so a death between the settings edit landing and [clear] costs
     * one harmless repeat next launch.
     *
     * Run at startup; the caller must keep it from overlapping a live restore,
     * which is writing the very payload this reads.
     */
    suspend fun reconcile(committedNonce: String?): Boolean {
        val prefs = dataStore.data.first()
        val stagedNonce = prefs[Keys.NONCE]
        if (stagedNonce == null || stagedNonce != committedNonce) {
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
            topSetHelperSeen = staged.topSetHelperSeenFlow.first(),
            supersetHelperSeen = staged.supersetHelperSeenFlow.first(),
            themePreference = staged.themePreferenceFlow.first(),
        )
        clear()
        return true
    }
}

/**
 * A full restore that did not finish cleanly, by phase. Each case says exactly
 * how far it got, so the UI can stop reporting every one of them as a problem
 * with the file the user picked (#172). Matched exhaustively where it is turned
 * into copy, so a new phase is a compile error rather than a generic message.
 */
sealed class RestoreInterruption(message: String, cause: Throwable?) : Exception(message, cause) {

    /** The journal couldn't be staged, so nothing was destroyed. The device is
     *  exactly as it was and the restore can simply be retried. */
    class NotStarted(cause: Throwable) :
        RestoreInterruption("Restore could not be staged; nothing was written", cause)

    /** Room committed the new data but the settings write failed. The journal is
     *  staged and the marker committed, so the next launch finishes it. */
    class SettingsPending(cause: Throwable) :
        RestoreInterruption("Restore committed its data but not its settings", cause)

    /** Everything the user owns landed; only clearing the journal/marker failed.
     *  Harmless — the leftover pair replays the same values idempotently and
     *  clears itself at the next launch. */
    class CleanupPending(cause: Throwable) :
        RestoreInterruption("Restore completed; its bookkeeping did not clear", cause)
}
