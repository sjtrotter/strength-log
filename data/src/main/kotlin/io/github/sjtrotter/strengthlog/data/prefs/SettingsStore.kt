package io.github.sjtrotter.strengthlog.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    }

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

    // --- writes --------------------------------------------------------------

    suspend fun setConfig(config: LifterConfig) = dataStore.edit { it.writeConfig(config) }

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

    /**
     * Replaces every preference in one atomic [edit] (backup restore, A2). The
     * leading [clear] drops any key not overwritten below, so a restore can't
     * leave a stale value behind; because these four inputs together own every
     * key this store defines, nothing is orphaned. A single edit means a crash
     * mid-restore leaves either the whole old preference set or the whole new one
     * — never a mix.
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
