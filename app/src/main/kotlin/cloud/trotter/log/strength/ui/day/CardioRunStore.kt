package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.data.prefs.CardioRunPrefs
import cloud.trotter.log.strength.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A day's cardio run as the Day screen last held it (#627): the run and the siblings logged or amended with it. */
@Serializable
data class StoredCardioRun(
    val savedAt: Long,
    val run: CardioRun,
    val gpsSets: List<CardioGpsSet> = emptyList(),
    val sprintTimes: List<CardioSprintTime> = emptyList(),
    val readingInputs: List<CardioReadingInput> = emptyList(),
    val runDistance: CardioRunDistance? = null,
)

/**
 * The durable copy of each day's cardio run in progress (#627). Back destroys the Day screen's
 * SavedStateHandle; this copy lets the next Day screen for the same plan and day start where it stopped.
 * [save] and [delete] return at once and write on the process scope, so a write outlives the screen
 * that made it. [load] writes anything still pending first, so it never returns an older copy.
 */
interface CardioRunStore {
    fun save(stored: StoredCardioRun)
    fun delete(planId: Long, dayId: String)
    suspend fun load(planId: Long, dayId: String): StoredCardioRun?

    /** No durable copy, for ViewModel tests that never leave the Day screen. */
    object None : CardioRunStore {
        override fun save(stored: StoredCardioRun) = Unit
        override fun delete(planId: Long, dayId: String) = Unit
        override suspend fun load(planId: Long, dayId: String): StoredCardioRun? = null
    }
}

@Singleton
class PrefsCardioRunStore @Inject constructor(
    private val prefs: CardioRunPrefs,
    @ApplicationScope private val scope: CoroutineScope,
) : CardioRunStore {
    override fun save(stored: StoredCardioRun) {
        val json = CardioRunJson.encodeToString(StoredCardioRun.serializer(), stored)
        enqueue(stored.run.planId to stored.run.dayId, json)
    }

    override fun delete(planId: Long, dayId: String) {
        enqueue(planId to dayId, null)
    }

    private fun enqueue(key: Pair<Long, String>, json: String?) {
        prefs.enqueue(key.first, key.second, json)
        scope.launch { prefs.flush() }
    }

    override suspend fun load(planId: Long, dayId: String): StoredCardioRun? {
        val json = prefs.get(planId, dayId) ?: return null
        return runCatching { CardioRunJson.decodeFromString(StoredCardioRun.serializer(), json) }.getOrNull()
    }
}

private val CardioRunJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
