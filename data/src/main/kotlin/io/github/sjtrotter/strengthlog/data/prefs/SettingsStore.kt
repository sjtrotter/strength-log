package io.github.sjtrotter.strengthlog.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.sjtrotter.strengthlog.domain.generator.AnchorScheme
import io.github.sjtrotter.strengthlog.domain.generator.DeadliftVariant
import io.github.sjtrotter.strengthlog.domain.generator.SplitTemplate
import io.github.sjtrotter.strengthlog.domain.generator.WizardAnswers
import io.github.sjtrotter.strengthlog.domain.model.CardioMode
import io.github.sjtrotter.strengthlog.domain.model.CardioPlacement
import io.github.sjtrotter.strengthlog.domain.model.CardioPrefs
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.ExperienceLevel
import io.github.sjtrotter.strengthlog.domain.model.GoalEmphasis
import io.github.sjtrotter.strengthlog.domain.model.LifterConfig
import io.github.sjtrotter.strengthlog.domain.standards.RestCategory
import io.github.sjtrotter.strengthlog.domain.standards.RestPolicy
import io.github.sjtrotter.strengthlog.domain.standards.RestSettings
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
         *  Device-local: deliberately excluded from the backup payload (see
         *  [restore]). */
        val REST_TIMER_ENABLED = booleanPreferencesKey("rest_timer_enabled")
        val REST_RAMP_SECONDS = intPreferencesKey("rest_ramp_seconds")
        val REST_TOP_SECONDS = intPreferencesKey("rest_top_seconds")
        val REST_BACKOFF_SECONDS = intPreferencesKey("rest_backoff_seconds")
        val REST_WORK_SECONDS = intPreferencesKey("rest_work_seconds")
        val REST_LIGHT_SECONDS = intPreferencesKey("rest_light_seconds")
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

    // --- writes --------------------------------------------------------------

    suspend fun setConfig(config: LifterConfig) = dataStore.edit { it.writeConfig(config) }

    /** Marks the one-shot legacy-TIMED fixup as done so it never runs again. */
    suspend fun setLegacyTimedFixupDone() =
        dataStore.edit { it[Keys.LEGACY_TIMED_FIXUP_DONE] = true }

    /** Flips the master rest-timer gate. */
    suspend fun setRestTimerEnabled(enabled: Boolean) =
        dataStore.edit { it[Keys.REST_TIMER_ENABLED] = enabled }

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
     * leave a stale value behind; because these four inputs together own every
     * key this store defines, nothing is orphaned. A single edit means a crash
     * mid-restore leaves either the whole old preference set or the whole new one
     * — never a mix. The session-start stamp keys ([Keys.SESSION_STARTED_AT]
     * and [Keys.SESSION_STARTED_DATE]) are deliberately left cleared: a restore
     * can't be "mid-workout". The rest-timer keys ([Keys.REST_TIMER_ENABLED] and
     * the five `rest_*_seconds` overrides) are likewise not in the backup payload
     * (v2 schema untouched — rest prefs are device-local settings, not workout
     * data), so a restore reverts them to defaults; folding them into a later
     * backup version is additive.
     */
    suspend fun restore(
        answers: WizardAnswers,
        unit: WeightUnit,
        wizardComplete: Boolean,
        suggestedDay: String?,
    ) = dataStore.edit { prefs ->
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
    }

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

/** A session-start stamp paired with the calendar [date] it was recorded on, so
 *  a reader can drop one that outlived its day (session-start capture). [date]
 *  is a `yyyy-MM-dd` string from the same clock basis `CheckmarkReset` uses. */
data class SessionStartStamp(val startedAtMillis: Long, val date: String)
