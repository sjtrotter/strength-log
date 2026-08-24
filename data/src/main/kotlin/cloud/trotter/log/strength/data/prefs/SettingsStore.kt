package cloud.trotter.log.strength.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import cloud.trotter.log.strength.domain.generator.AnchorScheme
import cloud.trotter.log.strength.domain.generator.DeadliftVariant
import cloud.trotter.log.strength.domain.generator.SplitTemplate
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.CardioMode
import cloud.trotter.log.strength.domain.model.CardioPlacement
import cloud.trotter.log.strength.domain.model.CardioPrefs
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ExperienceLevel
import cloud.trotter.log.strength.domain.model.GoalEmphasis
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * All app preferences (spec §7 DataStore list, plus the kg/lb unit from A5 and the
 * wizard answers needed to regenerate a single day). Every fact is stored as an
 * individual typed key so the reconstructed [LifterConfig] / [CardioPrefs] /
 * [WizardAnswers] share one source of truth — e.g. changing bodyweight through
 * [setConfig] is immediately visible to [wizardAnswersFlow].
 *
 * Unknown or missing values fall back to the domain defaults, so a partially
 * written store (or a value from a newer build) never crashes a read.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val BODYWEIGHT = intPreferencesKey("bodyweight")
        val AGE = intPreferencesKey("age")
        val LEVEL = stringPreferencesKey("level")
        val EMPHASIS = stringPreferencesKey("emphasis")

        val CARDIO_MODE = stringPreferencesKey("cardio_mode")
        val CARDIO_PLACEMENT = stringPreferencesKey("cardio_placement")
        val FIVE_K = booleanPreferencesKey("five_k")

        val DAYS_PER_WEEK = intPreferencesKey("days_per_week")
        val SPLIT = stringPreferencesKey("split")
        val ANCHOR_SCHEME = stringPreferencesKey("anchor_scheme")
        val DEADLIFT_VARIANT = stringPreferencesKey("deadlift_variant")
        val EQUIPMENT = stringSetPreferencesKey("equipment")

        val SUGGESTED_DAY = stringPreferencesKey("suggested_day")
        val WIZARD_COMPLETE = booleanPreferencesKey("wizard_complete")
        val WEIGHT_UNIT = stringPreferencesKey("weight_unit")
        val SESSION_STARTED_AT = longPreferencesKey("session_started_at")
        val SESSION_STARTED_DATE = stringPreferencesKey("session_started_date")

        /** Set once the one-shot reps→seconds fixup for entries reclassified TIMED
         *  has run (tracking-types P3, Decision 5). Its presence is what makes the
         *  fixup one-shot — it never runs a second time. */
        val LEGACY_TIMED_FIXUP_DONE = booleanPreferencesKey("legacy_timed_fixup_done")

        /** Rest-timer prefs (W2a). The master gate defaults ON when absent; each
         *  per-category override is absent-means-default (RestPolicy owns the
         *  numbers, so we never pre-write a default and can't drift from it).
         *  Carried by the backup since schema v3, absences and all (see
         *  [restore]). */
        val REST_TIMER_ENABLED = booleanPreferencesKey("rest_timer_enabled")
        val REST_RAMP_SECONDS = intPreferencesKey("rest_ramp_seconds")
        val REST_TOP_SECONDS = intPreferencesKey("rest_top_seconds")
        val REST_BACKOFF_SECONDS = intPreferencesKey("rest_backoff_seconds")
        val REST_WORK_SECONDS = intPreferencesKey("rest_work_seconds")
        val REST_LIGHT_SECONDS = intPreferencesKey("rest_light_seconds")
        val PHONE_REST_TIMER_ENABLED = booleanPreferencesKey("phone_rest_timer_enabled")
        val PHONE_REST_DEADLINE = longPreferencesKey("phone_rest_deadline")
        val PHONE_REST_TOTAL = intPreferencesKey("phone_rest_total")
        val PHONE_REST_NEXT = stringPreferencesKey("phone_rest_next")
        val PHONE_REST_NOTIFICATION_ASKED = booleanPreferencesKey("phone_rest_notification_asked")

        /** Set once the one-shot Health Connect backfill (#159) has published the
         *  history that predates the grant. Its presence is what makes the offer
         *  one-shot — it never shows again. */
        val HEALTH_BACKFILL_DONE = booleanPreferencesKey("health_backfill_done")

        /** Whether the phone screen stays awake while the app is in front (#125).
         *  Absent means off: a screen that never sleeps is a battery decision, so
         *  the user has to ask for it, and having asked once they keep it. */
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")

        val TOP_SET_HELPER_SEEN = booleanPreferencesKey("top_set_helper_seen")
        val SUPERSET_HELPER_SEEN = booleanPreferencesKey("superset_helper_seen")

        // TODO(schema): Move these values onto workout_session once the next
        // coordinated Room schema version is available.
        val SESSION_NOTES = stringPreferencesKey("session_notes")

        // Device-local SAF automation state. These keys are deliberately not
        // part of the versioned backup document: a persisted grant belongs to
        // this Android install and cannot be restored on another device.
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val AUTO_BACKUP_TREE_URI = stringPreferencesKey("auto_backup_tree_uri")
        val AUTO_BACKUP_FOLDER_NAME = stringPreferencesKey("auto_backup_folder_name")
        val AUTO_BACKUP_CADENCE_HOURS = intPreferencesKey("auto_backup_cadence_hours")
        val AUTO_BACKUP_LAST_SUCCESS_AT = longPreferencesKey("auto_backup_last_success_at")
        val AUTO_BACKUP_LAST_ATTEMPT_FAILED = booleanPreferencesKey("auto_backup_last_attempt_failed")
        val AUTO_BACKUP_PERMISSION_LOST = booleanPreferencesKey("auto_backup_permission_lost")

        val THEME = stringPreferencesKey("theme")
    }

    /** Maps each overridable rest category to its DataStore key (SSOT for the
     *  key↔category pairing used by both the read flow and the setter). */
    private val restOverrideKeys: Map<RestCategory, Preferences.Key<Int>> = mapOf(
        RestCategory.RAMP to Keys.REST_RAMP_SECONDS,
        RestCategory.TOP to Keys.REST_TOP_SECONDS,
        RestCategory.BACKOFF to Keys.REST_BACKOFF_SECONDS,
        RestCategory.WORK to Keys.REST_WORK_SECONDS,
        RestCategory.LIGHT to Keys.REST_LIGHT_SECONDS,
    )

    // --- reads ---------------------------------------------------------------

    val configFlow: Flow<LifterConfig> = dataStore.data.map { it.readConfig() }

    val cardioPrefsFlow: Flow<CardioPrefs> = dataStore.data.map { it.readCardio() }

    val wizardAnswersFlow: Flow<WizardAnswers> = dataStore.data.map { prefs ->
        WizardAnswers(
            daysPerWeek = prefs[Keys.DAYS_PER_WEEK] ?: DEFAULT_ANSWERS.daysPerWeek,
            split = prefs.enum(Keys.SPLIT, DEFAULT_ANSWERS.split),
            anchorScheme = prefs.enum(Keys.ANCHOR_SCHEME, DEFAULT_ANSWERS.anchorScheme),
            deadliftVariant = prefs.enum(Keys.DEADLIFT_VARIANT, DEFAULT_ANSWERS.deadliftVariant),
            cardio = prefs.readCardio(),
            config = prefs.readConfig(),
            equipment = prefs.readEquipment(),
        )
    }

    val suggestedDayFlow: Flow<String?> = dataStore.data.map { it[Keys.SUGGESTED_DAY] }

    val wizardCompleteFlow: Flow<Boolean> =
        dataStore.data.map { it[Keys.WIZARD_COMPLETE] ?: false }

    val unitFlow: Flow<WeightUnit> =
        dataStore.data.map { it.enum(Keys.WEIGHT_UNIT, WeightUnit.LB) }

    /** The in-progress session's start stamp with the calendar date it was
     *  recorded on (session-start capture), or `null` when no set has been
     *  ticked since the last advance/clear. One global slot: only one day is
     *  worked at a time, so it needs no day key. The stored date lets the reader
     *  drop a stamp that outlived its calendar day — the same staleness rule
     *  `CheckmarkReset` applies to checkmarks — so the date is compared, not the
     *  millis, and the caller owns the "today" it compares against (SSOT). */
    val sessionStartRawFlow: Flow<SessionStartStamp?> = dataStore.data.map { prefs ->
        val startedAt = prefs[Keys.SESSION_STARTED_AT] ?: return@map null
        val date = prefs[Keys.SESSION_STARTED_DATE] ?: return@map null
        SessionStartStamp(startedAt, date)
    }

    /** Whether the one-shot reps→seconds fixup for reclassified-TIMED live logs
     *  has already run (tracking-types P3, Decision 5). */
    val legacyTimedFixupDoneFlow: Flow<Boolean> =
        dataStore.data.map { it[Keys.LEGACY_TIMED_FIXUP_DONE] ?: false }

    /** The rest-timer prefs: the master gate (default ON) plus only the override
     *  keys the user has actually set. An absent override key is omitted from the
     *  map — [RestPolicy] supplies its default — so a fresh install with no keys
     *  yields `RestSettings(enabled = true, overrides = emptyMap())`. */
    val restSettingsFlow: Flow<RestSettings> = dataStore.data.map { prefs ->
        val overrides = restOverrideKeys.mapNotNull { (category, key) ->
            prefs[key]?.let { category to it }
        }.toMap()
        RestSettings(
            enabled = prefs[Keys.REST_TIMER_ENABLED] ?: true,
            overrides = overrides,
        )
    }

    val phoneRestTimerEnabledFlow: Flow<Boolean> = dataStore.data.map { it[Keys.PHONE_REST_TIMER_ENABLED] ?: true }
    val phoneRestFlow: Flow<PhoneRest?> = dataStore.data.map { prefs ->
        val deadline = prefs[Keys.PHONE_REST_DEADLINE] ?: return@map null
        PhoneRest(deadline, prefs[Keys.PHONE_REST_TOTAL] ?: 0, prefs[Keys.PHONE_REST_NEXT].orEmpty())
    }
    val phoneRestNotificationAskedFlow: Flow<Boolean> =
        dataStore.data.map { it[Keys.PHONE_REST_NOTIFICATION_ASKED] ?: false }

    /** Whether the screen is held awake while the app is in front (#125).
     *  Defaults off — the wake costs battery, so it is opt-in. */
    val keepScreenOnFlow: Flow<Boolean> =
        dataStore.data.map { it[Keys.KEEP_SCREEN_ON] ?: false }

    val topSetHelperSeenFlow: Flow<Boolean> =
        dataStore.data.map { it[Keys.TOP_SET_HELPER_SEEN] ?: false }

    val supersetHelperSeenFlow: Flow<Boolean> =
        dataStore.data.map { it[Keys.SUPERSET_HELPER_SEEN] ?: false }

    val sessionNotesFlow: Flow<Map<Long, String>> =
        dataStore.data.map { decodeSessionNotes(it[Keys.SESSION_NOTES]) }

    fun sessionNoteFlow(sessionId: Long): Flow<String> =
        sessionNotesFlow.map { it[sessionId].orEmpty() }

    val themePreferenceFlow: Flow<ThemePreference> =
        dataStore.data.map { it.enum(Keys.THEME, ThemePreference.SYSTEM) }

    /** Whether the one-shot Health Connect backfill has already run (#159). Not
     *  carried by the backup: [restore] clears it, so restored history — which
     *  this device has never published — gets offered again. */
    val healthBackfillDoneFlow: Flow<Boolean> =
        dataStore.data.map { it[Keys.HEALTH_BACKFILL_DONE] ?: false }

    val autoBackupSettingsFlow: Flow<AutoBackupSettings> = dataStore.data.map { prefs ->
        AutoBackupSettings(
            enabled = prefs[Keys.AUTO_BACKUP_ENABLED] ?: false,
            treeUri = prefs[Keys.AUTO_BACKUP_TREE_URI],
            folderName = prefs[Keys.AUTO_BACKUP_FOLDER_NAME],
            cadenceHours = prefs[Keys.AUTO_BACKUP_CADENCE_HOURS] ?: AUTO_BACKUP_DAILY_HOURS,
            lastSuccessAtMillis = prefs[Keys.AUTO_BACKUP_LAST_SUCCESS_AT],
            lastAttemptFailed = prefs[Keys.AUTO_BACKUP_LAST_ATTEMPT_FAILED] ?: false,
            permissionLost = prefs[Keys.AUTO_BACKUP_PERMISSION_LOST] ?: false,
        )
    }

    // --- writes --------------------------------------------------------------

    suspend fun setConfig(config: LifterConfig) = dataStore.edit { it.writeConfig(config) }

    /** Marks the one-shot legacy-TIMED fixup as done so it never runs again. */
    suspend fun setLegacyTimedFixupDone() =
        dataStore.edit { it[Keys.LEGACY_TIMED_FIXUP_DONE] = true }

    /** Marks the one-shot Health Connect backfill as done so the offer never
     *  returns. Written only after a backfill that published every session. */
    suspend fun setHealthBackfillDone() =
        dataStore.edit { it[Keys.HEALTH_BACKFILL_DONE] = true }

    suspend fun enableAutoBackup(treeUri: String, folderName: String) = dataStore.edit { prefs ->
        prefs[Keys.AUTO_BACKUP_TREE_URI] = treeUri
        prefs[Keys.AUTO_BACKUP_FOLDER_NAME] = folderName
        prefs[Keys.AUTO_BACKUP_CADENCE_HOURS] = AUTO_BACKUP_DAILY_HOURS
        prefs[Keys.AUTO_BACKUP_ENABLED] = true
        prefs[Keys.AUTO_BACKUP_LAST_ATTEMPT_FAILED] = false
        prefs[Keys.AUTO_BACKUP_PERMISSION_LOST] = false
    }

    suspend fun disableAutoBackup() = dataStore.edit { prefs ->
        prefs.remove(Keys.AUTO_BACKUP_ENABLED)
        prefs.remove(Keys.AUTO_BACKUP_TREE_URI)
        prefs.remove(Keys.AUTO_BACKUP_FOLDER_NAME)
        prefs.remove(Keys.AUTO_BACKUP_CADENCE_HOURS)
        prefs.remove(Keys.AUTO_BACKUP_LAST_SUCCESS_AT)
        prefs.remove(Keys.AUTO_BACKUP_LAST_ATTEMPT_FAILED)
        prefs.remove(Keys.AUTO_BACKUP_PERMISSION_LOST)
    }

    suspend fun recordAutoBackupSuccess(atMillis: Long) = dataStore.edit { prefs ->
        prefs[Keys.AUTO_BACKUP_LAST_SUCCESS_AT] = atMillis
        prefs[Keys.AUTO_BACKUP_LAST_ATTEMPT_FAILED] = false
    }

    suspend fun recordAutoBackupFailure() = dataStore.edit { prefs ->
        prefs[Keys.AUTO_BACKUP_LAST_ATTEMPT_FAILED] = true
    }

    suspend fun markAutoBackupPermissionLost() = dataStore.edit { prefs ->
        prefs.remove(Keys.AUTO_BACKUP_ENABLED)
        prefs.remove(Keys.AUTO_BACKUP_TREE_URI)
        prefs.remove(Keys.AUTO_BACKUP_CADENCE_HOURS)
        prefs[Keys.AUTO_BACKUP_LAST_ATTEMPT_FAILED] = true
        prefs[Keys.AUTO_BACKUP_PERMISSION_LOST] = true
    }

    /** Flips the master rest-timer gate. */
    suspend fun setRestTimerEnabled(enabled: Boolean) =
        dataStore.edit { it[Keys.REST_TIMER_ENABLED] = enabled }

    suspend fun setPhoneRestTimerEnabled(enabled: Boolean) = dataStore.edit {
        it[Keys.PHONE_REST_TIMER_ENABLED] = enabled
        if (!enabled) clearPhoneRest(it)
    }

    suspend fun setPhoneRest(rest: PhoneRest?) = dataStore.edit { prefs ->
        if (rest == null) clearPhoneRest(prefs) else {
            prefs[Keys.PHONE_REST_DEADLINE] = rest.deadlineEpochMillis
            prefs[Keys.PHONE_REST_TOTAL] = rest.totalSeconds
            prefs[Keys.PHONE_REST_NEXT] = rest.nextSetLabel
        }
    }

    suspend fun markPhoneRestNotificationAsked() =
        dataStore.edit { it[Keys.PHONE_REST_NOTIFICATION_ASKED] = true }

    private fun clearPhoneRest(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs.remove(Keys.PHONE_REST_DEADLINE)
        prefs.remove(Keys.PHONE_REST_TOTAL)
        prefs.remove(Keys.PHONE_REST_NEXT)
    }

    /** Writes one per-category rest override, clamped to [RestPolicy]'s bounds so
     *  a stored value can never exceed what the resolver accepts. Writing a value
     *  equal to the default is fine — it just freezes that bucket at that number,
     *  which is what "I set it" means. */
    suspend fun setRestOverride(category: RestCategory, seconds: Int) =
        dataStore.edit { it[restOverrideKeys.getValue(category)] = seconds.coerceIn(0, RestPolicy.MAX_REST_SECONDS) }

    /** The RESET affordance: removes the five override keys so every bucket reverts
     *  to its [RestPolicy] default. Leaves the master gate untouched. */
    suspend fun clearRestOverrides() =
        dataStore.edit { prefs -> restOverrideKeys.values.forEach { prefs.remove(it) } }

    suspend fun setCardioPrefs(prefs: CardioPrefs) = dataStore.edit { it.writeCardio(prefs) }

    suspend fun setWizardAnswers(answers: WizardAnswers) = dataStore.edit { prefs ->
        prefs.writeConfig(answers.config)
        prefs.writeCardio(answers.cardio)
        prefs[Keys.DAYS_PER_WEEK] = answers.daysPerWeek
        prefs[Keys.SPLIT] = answers.split.name
        prefs[Keys.ANCHOR_SCHEME] = answers.anchorScheme.name
        prefs[Keys.DEADLIFT_VARIANT] = answers.deadliftVariant.name
        prefs[Keys.EQUIPMENT] = answers.equipment.map { it.name }.toSet()
    }

    suspend fun setSuggestedDay(dayId: String) =
        dataStore.edit { it[Keys.SUGGESTED_DAY] = dayId }

    suspend fun setWizardComplete(complete: Boolean) =
        dataStore.edit { it[Keys.WIZARD_COMPLETE] = complete }

    suspend fun setUnit(unit: WeightUnit) =
        dataStore.edit { it[Keys.WEIGHT_UNIT] = unit.name }

    /** Flips the keep-screen-on preference (the day screen's bottom-bar switch). */
    suspend fun setKeepScreenOn(on: Boolean) =
        dataStore.edit { it[Keys.KEEP_SCREEN_ON] = on }

    suspend fun markTopSetHelperSeen() =
        dataStore.edit { it[Keys.TOP_SET_HELPER_SEEN] = true }

    suspend fun markSupersetHelperSeen() =
        dataStore.edit { it[Keys.SUPERSET_HELPER_SEEN] = true }

    suspend fun setSessionNote(sessionId: Long, text: String) = dataStore.edit { prefs ->
        val notes = decodeSessionNotes(prefs[Keys.SESSION_NOTES]).toMutableMap()
        val clean = text.trim().take(120)
        if (clean.isEmpty()) notes.remove(sessionId) else notes[sessionId] = clean
        if (notes.isEmpty()) prefs.remove(Keys.SESSION_NOTES)
        else prefs[Keys.SESSION_NOTES] = SessionNotesJson.encodeToString(SessionNotesSerializer, notes)
    }

    suspend fun setThemePreference(theme: ThemePreference) =
        dataStore.edit { it[Keys.THEME] = theme.name }

    /** Stamps [nowMillis]/[today] as the in-progress session's start, unless a
     *  stamp for [today] already exists. A stamp carrying any other date is
     *  treated as absent and overwritten: it belonged to an abandoned session
     *  from a previous calendar day (ticked, never advanced), so the first tick
     *  of a new day starts a fresh clock instead of inheriting a stale start
     *  (session-start capture). */
    suspend fun stampSessionStartIfUnset(nowMillis: Long, today: String) = dataStore.edit { prefs ->
        val current = prefs[Keys.SESSION_STARTED_DATE]
        if (prefs[Keys.SESSION_STARTED_AT] == null || current != today) {
            prefs[Keys.SESSION_STARTED_AT] = nowMillis
            prefs[Keys.SESSION_STARTED_DATE] = today
        }
    }

    /** Clears the session-start stamp (both millis and date): called when
     *  checkmarks are cleared (restart semantics — the next tick starts a new
     *  session) and once `advanceDay` has consumed the stamp into
     *  `workout_session.startedAt`. */
    suspend fun clearSessionStartedAt() = dataStore.edit {
        it.remove(Keys.SESSION_STARTED_AT)
        it.remove(Keys.SESSION_STARTED_DATE)
    }

    /**
     * Replaces every preference in one atomic [edit] (backup restore, A2). The
     * leading [clear] drops any key not overwritten below, so a restore can't
     * leave a stale value behind; because these inputs together own every
     * key this store defines, nothing is orphaned. A single edit means a crash
     * mid-restore leaves either the whole old preference set or the whole new one
     * — never a mix. The session-start stamp keys ([Keys.SESSION_STARTED_AT]
     * and [Keys.SESSION_STARTED_DATE]) are deliberately left cleared: a restore
     * can't be "mid-workout".
     *
     * Device-local automatic-backup keys are captured and restored around the
     * clear: their SAF grant cannot be transferred in a backup document, but a
     * data restore on this device must not silently turn its schedule off.
     *
     * [restSettings] only writes the override keys the backup actually carries
     * (schema v3): a bucket the user never pinned stays absent here too, so a
     * restored device keeps following [RestPolicy]'s defaults rather than being
     * frozen at whatever they were the day the backup was written.
     */
    suspend fun restore(
        answers: WizardAnswers,
        unit: WeightUnit,
        wizardComplete: Boolean,
        suggestedDay: String?,
        restSettings: RestSettings,
        keepScreenOn: Boolean,
        topSetHelperSeen: Boolean = false,
        supersetHelperSeen: Boolean = false,
        sessionNotes: Map<Long, String> = emptyMap(),
        themePreference: ThemePreference = ThemePreference.SYSTEM,
    ) = dataStore.edit { prefs ->
        // SAF grants and their schedule are properties of this installation,
        // not user data contained in the imported document. Keep them across a
        // full restore while still clearing all restorable preferences.
        val autoEnabled = prefs[Keys.AUTO_BACKUP_ENABLED]
        val autoTreeUri = prefs[Keys.AUTO_BACKUP_TREE_URI]
        val autoFolderName = prefs[Keys.AUTO_BACKUP_FOLDER_NAME]
        val autoCadence = prefs[Keys.AUTO_BACKUP_CADENCE_HOURS]
        val autoLastSuccess = prefs[Keys.AUTO_BACKUP_LAST_SUCCESS_AT]
        val autoFailed = prefs[Keys.AUTO_BACKUP_LAST_ATTEMPT_FAILED]
        val autoPermissionLost = prefs[Keys.AUTO_BACKUP_PERMISSION_LOST]
        prefs.clear()
        prefs.writeConfig(answers.config)
        prefs.writeCardio(answers.cardio)
        prefs[Keys.DAYS_PER_WEEK] = answers.daysPerWeek
        prefs[Keys.SPLIT] = answers.split.name
        prefs[Keys.ANCHOR_SCHEME] = answers.anchorScheme.name
        prefs[Keys.DEADLIFT_VARIANT] = answers.deadliftVariant.name
        prefs[Keys.EQUIPMENT] = answers.equipment.map { it.name }.toSet()
        prefs[Keys.WEIGHT_UNIT] = unit.name
        prefs[Keys.WIZARD_COMPLETE] = wizardComplete
        if (suggestedDay != null) prefs[Keys.SUGGESTED_DAY] = suggestedDay
        prefs[Keys.REST_TIMER_ENABLED] = restSettings.enabled
        restSettings.overrides.forEach { (category, seconds) ->
            prefs[restOverrideKeys.getValue(category)] = seconds.coerceIn(0, RestPolicy.MAX_REST_SECONDS)
        }
        prefs[Keys.KEEP_SCREEN_ON] = keepScreenOn
        prefs[Keys.TOP_SET_HELPER_SEEN] = topSetHelperSeen
        prefs[Keys.SUPERSET_HELPER_SEEN] = supersetHelperSeen
        if (sessionNotes.isNotEmpty()) {
            prefs[Keys.SESSION_NOTES] = SessionNotesJson.encodeToString(SessionNotesSerializer, sessionNotes)
        }
        autoEnabled?.let { prefs[Keys.AUTO_BACKUP_ENABLED] = it }
        autoTreeUri?.let { prefs[Keys.AUTO_BACKUP_TREE_URI] = it }
        autoFolderName?.let { prefs[Keys.AUTO_BACKUP_FOLDER_NAME] = it }
        autoCadence?.let { prefs[Keys.AUTO_BACKUP_CADENCE_HOURS] = it }
        autoLastSuccess?.let { prefs[Keys.AUTO_BACKUP_LAST_SUCCESS_AT] = it }
        autoFailed?.let { prefs[Keys.AUTO_BACKUP_LAST_ATTEMPT_FAILED] = it }
        autoPermissionLost?.let { prefs[Keys.AUTO_BACKUP_PERMISSION_LOST] = it }
        prefs[Keys.THEME] = themePreference.name
    }

    private fun decodeSessionNotes(value: String?): Map<Long, String> =
        value?.let { runCatching { SessionNotesJson.decodeFromString(SessionNotesSerializer, it) }.getOrDefault(emptyMap()) }
            ?: emptyMap()

    // --- read/write helpers --------------------------------------------------

    private fun Preferences.readConfig(): LifterConfig = LifterConfig(
        bodyweightLb = this[Keys.BODYWEIGHT] ?: DEFAULT_CONFIG.bodyweightLb,
        age = this[Keys.AGE] ?: DEFAULT_CONFIG.age,
        level = enum(Keys.LEVEL, DEFAULT_CONFIG.level),
        emphasis = enum(Keys.EMPHASIS, DEFAULT_CONFIG.emphasis),
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.writeConfig(config: LifterConfig) {
        this[Keys.BODYWEIGHT] = config.bodyweightLb
        this[Keys.AGE] = config.age
        this[Keys.LEVEL] = config.level.name
        this[Keys.EMPHASIS] = config.emphasis.name
    }

    private fun Preferences.readCardio(): CardioPrefs = CardioPrefs(
        mode = enum(Keys.CARDIO_MODE, DEFAULT_CARDIO.mode),
        placement = enum(Keys.CARDIO_PLACEMENT, DEFAULT_CARDIO.placement),
        fiveKGoal = this[Keys.FIVE_K] ?: DEFAULT_CARDIO.fiveKGoal,
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.writeCardio(prefs: CardioPrefs) {
        this[Keys.CARDIO_MODE] = prefs.mode.name
        this[Keys.CARDIO_PLACEMENT] = prefs.placement.name
        this[Keys.FIVE_K] = prefs.fiveKGoal
    }

    private fun Preferences.readEquipment(): Set<Equipment> =
        this[Keys.EQUIPMENT]
            ?.mapNotNull { name -> Equipment.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: DEFAULT_ANSWERS.equipment

    private inline fun <reified E : Enum<E>> Preferences.enum(
        key: Preferences.Key<String>,
        default: E,
    ): E {
        val name = this[key] ?: return default
        return enumValues<E>().firstOrNull { it.name == name } ?: default
    }

    private companion object {
        val DEFAULT_CONFIG = LifterConfig()
        val DEFAULT_CARDIO = CardioPrefs()
        val DEFAULT_ANSWERS = WizardAnswers()
    }
}

private val SessionNotesJson = Json { ignoreUnknownKeys = true }
private val SessionNotesSerializer = MapSerializer(Long.serializer(), String.serializer())

/** A session-start stamp paired with the calendar [date] it was recorded on, so
 *  a reader can drop one that outlived its day (session-start capture). [date]
 *  is a `yyyy-MM-dd` string from the same clock basis `CheckmarkReset` uses. */
data class SessionStartStamp(val startedAtMillis: Long, val date: String)

const val AUTO_BACKUP_DAILY_HOURS = 24

data class AutoBackupSettings(
    val enabled: Boolean = false,
    val treeUri: String? = null,
    val folderName: String? = null,
    val cadenceHours: Int = AUTO_BACKUP_DAILY_HOURS,
    val lastSuccessAtMillis: Long? = null,
    val lastAttemptFailed: Boolean = false,
    val permissionLost: Boolean = false,
)
