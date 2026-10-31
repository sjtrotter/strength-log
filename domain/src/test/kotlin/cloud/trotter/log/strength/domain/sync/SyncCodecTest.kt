package cloud.trotter.log.strength.domain.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The wire codec is the SSOT both transports go through, so its round-trips and —
 * crucially — its forward-migration leniency are pinned here
 * (docs/briefs/m5-wear.md, "#20 Wearable Data Layer sync", "Serialization"
 * requirement: decode both DTOs with `ignoreUnknownKeys = true`).
 */
class SyncCodecTest {

    private val snapshot = WatchSnapshot(
        revision = 42L,
        suggestedDayId = "B",
        day = WatchDay(
            dayId = "B",
            title = "Day B — Hinge",
            accentIndex = 1,
            exercises = listOf(
                WatchExercise(
                    programExerciseId = 9L,
                    slot = "main",
                    name = "Trap-Bar Deadlift",
                    goal = 255.0,
                    perHand = false,
                    supersetPartnerName = null,
                    sets = listOf(WatchSet(190.0, 3, "RAMP", done = true), WatchSet(255.0, 5, "TOP", done = false)),
                    ssSets = emptyList(),
                ),
            ),
        ),
        unit = "kg",
    )

    private val delta = SetEditDelta(
        dayId = "B",
        programExerciseId = 9L,
        slot = "main",
        setIndex = 1,
        weightLb = 265.0,
        editedAtMillis = 1_700_000_000_000L,
    )

    @Test
    fun `snapshot round-trips through the wire bytes`() {
        assertEquals(snapshot, SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(snapshot)))
    }

    @Test
    fun snapshot_with_alternates_round_trips_through_the_wire_bytes() {
        val withAlternates = snapshot.copy(
            day = snapshot.day.copy(
                exercises = listOf(
                    snapshot.day.exercises.single().copy(
                        alternates = listOf(
                            WatchAlternate("conv_dl", "Deadlift"),
                            WatchAlternate("rdl", "Romanian Deadlift"),
                        ),
                    ),
                ),
            ),
        )

        val decoded = SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(withAlternates))

        assertEquals(withAlternates, decoded)
        assertEquals(listOf("conv_dl", "rdl"), decoded.day.exercises.single().alternates.map { it.exerciseId })
        assertEquals(listOf("Deadlift", "Romanian Deadlift"), decoded.day.exercises.single().alternates.map { it.name })
    }

    @Test
    fun snapshot_without_alternates_key_decodes_to_an_empty_list() {
        val oldFormat = """
            {"revision":1,"suggestedDayId":"A","unit":"lb",
             "day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[
               {"programExerciseId":1,"slot":"main","name":"Squat","goal":100.0,
                "perHand":false,"supersetPartnerName":null,"sets":[],"ssSets":[]}
             ]}}
        """.trimIndent()

        val decoded = SyncCodec.decodeSnapshot(oldFormat.encodeToByteArray())

        assertEquals(emptyList(), decoded.day.exercises.single().alternates)
    }

    @Test
    fun snapshot_exercise_with_an_unknown_key_still_decodes() {
        val futureFormat = """
            {"revision":1,"suggestedDayId":"A","unit":"lb",
             "day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[
               {"programExerciseId":1,"slot":"main","name":"Squat","goal":100.0,
                "perHand":false,"supersetPartnerName":null,"sets":[],"ssSets":[],"somethingNewer":7}
             ]}}
        """.trimIndent()

        assertEquals("Squat", SyncCodec.decodeSnapshot(futureFormat.encodeToByteArray()).day.exercises.single().name)
    }

    @Test
    fun `snapshot round-trips a set's restAfterSeconds through the wire bytes`() {
        val withRest = snapshot.copy(
            day = snapshot.day.copy(
                exercises = listOf(
                    snapshot.day.exercises.single().copy(
                        sets = listOf(WatchSet(255.0, 5, "TOP", done = false, restAfterSeconds = 180)),
                    ),
                ),
            ),
        )
        val decoded = SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(withRest))
        assertEquals(withRest, decoded)
        assertEquals(180, decoded.day.exercises.single().sets.single().restAfterSeconds)
    }

    @Test
    fun `snapshot round-trips the program's cycle through the wire bytes`() {
        val withCycle = snapshot.copy(
            cycle = listOf(
                WatchCycleDay("A", "Day A — Squat", listOf(WatchCycleExercise("Squat", 6))),
                WatchCycleDay("B", "Day B — Hinge", listOf(WatchCycleExercise("Deadlift", 4))),
            ),
        )
        val decoded = SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(withCycle))
        assertEquals(withCycle, decoded)
        assertEquals(listOf("A", "B"), decoded.cycle.map { it.dayId })
    }

    @Test
    fun `delta round-trips through the wire bytes`() {
        assertEquals(delta, SyncCodec.decodeDelta(SyncCodec.encodeDelta(delta)))
    }

    @Test
    fun `snapshot decode tolerates an unknown future field`() {
        val withExtra = """
            {"schemaVersion":2,"revision":1,"suggestedDayId":"A","unit":"lb","futureFlag":true,
             "day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[]}}
        """.trimIndent()
        assertEquals("A", SyncCodec.decodeSnapshot(withExtra.encodeToByteArray()).suggestedDayId)
    }

    @Test
    fun `delta decode tolerates an unknown future field`() {
        val withExtra = """
            {"schemaVersion":2,"dayId":"A","programExerciseId":1,"slot":"main","setIndex":0,
             "editedAtMillis":5,"rpe":8}
        """.trimIndent()
        val decoded = SyncCodec.decodeDelta(withExtra.encodeToByteArray())
        assertEquals(0, decoded.setIndex)
        assertEquals(5L, decoded.editedAtMillis)
    }

    // --- per-set timing ------------------------------------------------------

    @Test
    fun `a delta carrying the set timing round-trips through the wire bytes`() {
        val tick = delta.copy(
            weightLb = null,
            done = true,
            startedAtMillis = 1_700_000_000_000L,
            completedAtMillis = 1_700_000_045_000L,
        )
        val decoded = SyncCodec.decodeDelta(SyncCodec.encodeDelta(tick))
        assertEquals(tick, decoded)
        assertEquals(1_700_000_000_000L, decoded.startedAtMillis)
        assertEquals(1_700_000_045_000L, decoded.completedAtMillis)
        // The LWW/dedupe stamp keeps its own job — the facts never overwrite it.
        assertEquals(1_700_000_000_000L, decoded.editedAtMillis)
    }

    @Test
    fun `an old-format delta without the timing keys decodes with nulls`() {
        val oldFormat = """
            {"schemaVersion":1,"dayId":"B","programExerciseId":9,"slot":"main","setIndex":1,
             "done":true,"editedAtMillis":7}
        """.trimIndent()
        val decoded = SyncCodec.decodeDelta(oldFormat.encodeToByteArray())
        assertNull(decoded.startedAtMillis)
        assertNull(decoded.completedAtMillis)
        assertEquals(true, decoded.done)
        assertEquals(7L, decoded.editedAtMillis)
    }

    @Test
    fun `a new-format delta still decodes everything an old reader expects`() {
        // The other direction: a schemaVersion-1 payload written by a *new* watch
        // carries the extra keys, and every field an older phone reads is untouched.
        val newFormat = """
            {"schemaVersion":1,"dayId":"B","programExerciseId":9,"slot":"main","setIndex":1,
             "reps":5,"done":true,"editedAtMillis":7,
             "startedAtMillis":1700000000000,"completedAtMillis":1700000045000}
        """.trimIndent()
        val decoded = SyncCodec.decodeDelta(newFormat.encodeToByteArray())
        assertEquals(1, decoded.schemaVersion)
        assertEquals("B", decoded.dayId)
        assertEquals(9L, decoded.programExerciseId)
        assertEquals(1, decoded.setIndex)
        assertEquals(5, decoded.reps)
        assertEquals(true, decoded.done)
        assertEquals(7L, decoded.editedAtMillis)
        assertEquals(1_700_000_045_000L, decoded.completedAtMillis)
    }

    @Test
    fun `a queue mixing old and new format deltas round-trips`() {
        val queue = listOf(
            delta,
            delta.copy(
                setIndex = 0,
                editedAtMillis = 2L,
                done = true,
                startedAtMillis = 10L,
                completedAtMillis = 20L,
            ),
        )
        assertEquals(queue, SyncCodec.decodeDeltaQueue(SyncCodec.encodeDeltaQueue(queue)))
    }

    @Test
    fun `delta queue round-trips and an empty string decodes to empty`() {
        val queue = listOf(delta, delta.copy(setIndex = 0, editedAtMillis = 2L))
        assertEquals(queue, SyncCodec.decodeDeltaQueue(SyncCodec.encodeDeltaQueue(queue)))
        assertEquals(emptyList(), SyncCodec.decodeDeltaQueue(""))
    }

    @Test
    fun exercise_swap_round_trips_through_the_wire_bytes() {
        val swap = ExerciseSwapDelta(
            dayId = "A",
            programExerciseId = 12L,
            exerciseId = "hack_squat",
            exerciseName = "Hack Squat",
            editedAtMillis = 99L,
        )

        val decoded = SyncCodec.decodeSwap(SyncCodec.encodeSwap(swap))

        assertEquals(swap, decoded)
        assertEquals("A", decoded.dayId)
        assertEquals(12L, decoded.programExerciseId)
        assertEquals("hack_squat", decoded.exerciseId)
        assertEquals("Hack Squat", decoded.exerciseName)
        assertEquals(99L, decoded.editedAtMillis)
    }

    @Test
    fun exercise_swap_with_an_unknown_key_and_no_schema_version_decodes() {
        val futureFormat = """
            {"dayId":"A","programExerciseId":12,"exerciseId":"hack_squat",
             "exerciseName":"Hack Squat","editedAtMillis":99,"somethingNewer":7}
        """.trimIndent()

        val decoded = SyncCodec.decodeSwap(futureFormat.encodeToByteArray())

        assertEquals(1, decoded.schemaVersion)
        assertEquals("hack_squat", decoded.exerciseId)
    }

    @Test
    fun exercise_swap_queue_round_trips_and_blank_strings_decode_to_empty() {
        val first = ExerciseSwapDelta(1, "A", 12L, "hack_squat", "Hack Squat", 99L)
        val queue = listOf(first, first.copy(exerciseId = "leg_press", exerciseName = "Leg Press", editedAtMillis = 100L))

        assertEquals(queue, SyncCodec.decodeSwapQueue(SyncCodec.encodeSwapQueue(queue)))
        assertEquals(emptyList(), SyncCodec.decodeSwapQueue(""))
        assertEquals(emptyList(), SyncCodec.decodeSwapQueue("   "))
    }

    @Test
    fun cardio_delta_round_trips_and_ignores_future_fields() {
        val delta = CardioDelta(1, "A", "OUTDOOR_RUN", true, "Intervals", 100L, 70_100L, 70, 2, 91L)
        assertEquals(delta, SyncCodec.decodeCardio(SyncCodec.encodeCardio(delta)))

        val future = """{"dayId":"A","mode":"OUTDOOR_RUN","hard":false,"label":"Easy Zone 2","startedAt":100,"completedAt":60100,"seconds":60,"stepsCompleted":1,"stamp":92,"future":true}"""
        val decoded = SyncCodec.decodeCardio(future.encodeToByteArray())
        assertEquals(1, decoded.schemaVersion)
        assertEquals(92L, decoded.stamp)
    }

    @Test
    fun old_cardio_suggestion_without_execution_facts_decodes_null_mode() {
        val stripped = SyncCodec.encodeSnapshot(snapshot).decodeToString()
            .replace(Regex(",?\"mode\":(?:null|\"[A-Z_]+\")"), "")
            .replace(Regex(",?\"fiveK\":(?:null|true|false)"), "")
        val decoded = SyncCodec.decodeSnapshot(stripped.encodeToByteArray())
        assertEquals(null, decoded.cardio?.mode)
        assertEquals(null, decoded.cardio?.fiveK)
    }

    @Test
    fun snapshot_cardio_is_additive_and_defaulted() {
        val oldJson = SyncCodec.encodeSnapshot(snapshot).decodeToString()
            .replace(Regex(",?\\\"cardio\\\":(?:null|\\{.*?\\})(?=,?\\\"|})"), "")
        val decoded = SyncCodec.decodeSnapshot(oldJson.encodeToByteArray())
        assertEquals(null, decoded.cardio)
        assertEquals(0L, decoded.cardioAckStamp)
    }

    @Test
    fun `plan context round-trips on every wire type`() {
        val planned = snapshot.copy(planId = 17L, planName = "Strength")
        assertEquals(planned, SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(planned)))
        val edit = delta.copy(planId = 17L)
        assertEquals(edit, SyncCodec.decodeDelta(SyncCodec.encodeDelta(edit)))
        val swap = ExerciseSwapDelta(1, "A", 12L, "hack_squat", "Hack Squat", 99L, planId = 17L)
        assertEquals(swap, SyncCodec.decodeSwap(SyncCodec.encodeSwap(swap)))
        val cardio = CardioDelta(1, "A", "OUTDOOR_RUN", false, "Easy", 100L, 70_100L, 70, 0, 91L, planId = 17L)
        assertEquals(cardio, SyncCodec.decodeCardio(SyncCodec.encodeCardio(cardio)))

        // The durable queues preserve the context through a process restart too.
        assertEquals(listOf(edit), SyncCodec.decodeDeltaQueue(SyncCodec.encodeDeltaQueue(listOf(edit))))
        assertEquals(listOf(swap), SyncCodec.decodeSwapQueue(SyncCodec.encodeSwapQueue(listOf(swap))))
        assertEquals(listOf(cardio), SyncCodec.decodeCardioQueue(SyncCodec.encodeCardioQueue(listOf(cardio))))
    }

    @Test
    fun `old payloads without plan keys retain legacy defaults`() {
        val oldSnapshot = """
            {"revision":1,"suggestedDayId":"A","unit":"lb",
             "day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[]}}
        """.trimIndent()
        val decodedSnapshot = SyncCodec.decodeSnapshot(oldSnapshot.encodeToByteArray())
        assertNull(decodedSnapshot.planId)
        assertNull(decodedSnapshot.planName)
        assertEquals(1, decodedSnapshot.schemaVersion)

        val oldEdit = """{"dayId":"A","programExerciseId":12,"slot":"main","setIndex":0,"done":true,"editedAtMillis":99}"""
        val edit = SyncCodec.decodeDelta(oldEdit.encodeToByteArray())
        assertNull(edit.planId)
        val oldSwap = """{"dayId":"A","programExerciseId":12,"exerciseId":"hack_squat","exerciseName":"Hack Squat","editedAtMillis":99}"""
        val swap = SyncCodec.decodeSwap(oldSwap.encodeToByteArray())
        assertNull(swap.planId)
        val oldCardio = """{"dayId":"A","mode":"OUTDOOR_RUN","hard":false,"label":"Easy","startedAt":100,"completedAt":70100,"seconds":70,"stepsCompleted":0,"stamp":91}"""
        val cardio = SyncCodec.decodeCardio(oldCardio.encodeToByteArray())
        assertNull(cardio.planId)
    }

    @Test
    fun `real old delta decodes with null run and exercise`() {
        val old = """{"dayId":"A","programExerciseId":7,"slot":"main","setIndex":0,"done":true,"editedAtMillis":10,"planId":1}"""
        val decoded = SyncCodec.decodeDelta(old.encodeToByteArray())
        assertNull(decoded.runId)
        assertNull(decoded.exerciseId)
    }

    @Test
    fun `real old snapshot decodes with null run fields`() {
        val old = """
            {"schemaVersion":1,"revision":2,"suggestedDayId":"A","unit":"lb",
             "day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[]}}
        """.trimIndent()
        val decoded = SyncCodec.decodeSnapshot(old.encodeToByteArray())
        assertNull(decoded.runId)
        assertNull(decoded.runStartedAt)
        assertNull(decoded.retiredRoundsDone)
    }

    /** [F] */
    @Test
    fun `null run fields encode to the exact old delta bytes`() {
        val expected = """{"dayId":"A","programExerciseId":7,"slot":"main","setIndex":0,"done":true,"editedAtMillis":10,"planId":1}"""
        val legacy = SetEditDelta(
            dayId = "A", programExerciseId = 7, slot = "main", setIndex = 0,
            done = true, editedAtMillis = 10, planId = 1, runId = null, exerciseId = null,
        )
        assertEquals(expected, SyncCodec.encodeDelta(legacy).decodeToString())
    }

    /** [F] */
    @Test
    fun `old reader ignores run identity and preserves every shared delta field`() {
        val updated = delta.copy(
            schemaVersion = 1, reps = 8, done = true, seconds = 30,
            startedAtMillis = 5, completedAtMillis = 35, planId = 1,
            runId = "r-1", exerciseId = "trap_bar_dl",
        )
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(
            OldSetEditDelta.serializer(), SyncCodec.encodeDelta(updated).decodeToString(),
        )
        assertEquals(updated.schemaVersion, decoded.schemaVersion)
        assertEquals(updated.dayId, decoded.dayId)
        assertEquals(updated.programExerciseId, decoded.programExerciseId)
        assertEquals(updated.slot, decoded.slot)
        assertEquals(updated.setIndex, decoded.setIndex)
        assertEquals(updated.weightLb, decoded.weightLb)
        assertEquals(updated.reps, decoded.reps)
        assertEquals(updated.done, decoded.done)
        assertEquals(updated.seconds, decoded.seconds)
        assertEquals(updated.editedAtMillis, decoded.editedAtMillis)
        assertEquals(updated.startedAtMillis, decoded.startedAtMillis)
        assertEquals(updated.completedAtMillis, decoded.completedAtMillis)
        assertEquals(updated.planId, decoded.planId)
    }

    /** [F] */
    @Test
    fun `snapshot run identity is strictly additive`() {
        val json = Json
        val withRun = snapshot.copy(runId = "r-1", runStartedAt = 5)
        val wire = json.parseToJsonElement(SyncCodec.encodeSnapshot(withRun).decodeToString()).jsonObject
        val old = json.parseToJsonElement(SyncCodec.encodeSnapshot(snapshot).decodeToString()).jsonObject
        assertEquals(old, wire.filterKeys { it != "runId" && it != "runStartedAt" })
        assertEquals(withRun, SyncCodec.decodeSnapshot(SyncCodec.encodeSnapshot(withRun)))
        val legacy = WatchSnapshot(revision = 2, day = WatchDay("A", "A", 0, emptyList()), suggestedDayId = "A", unit = "lb")
        assertEquals(
            """{"revision":2,"suggestedDayId":"A","day":{"dayId":"A","title":"A","accentIndex":0,"exercises":[]},"unit":"lb"}""",
            SyncCodec.encodeSnapshot(legacy).decodeToString(),
        )
    }

    /** [F] */
    @Test
    fun `offline finish shape round trips on its reserved path`() {
        val finish = RunFinishDelta(
            runId = "r-1", planId = 1, dayId = "A", startedAtMillis = 5,
            finishedAtMillis = 50, editedAtMillis = 51,
            tracks = listOf(RunFinishTrack(7, "main", "bb_back_squat", listOf(
                RunFinishSet(235.0, 5, done = true, startedAtMillis = 10, completedAtMillis = 20, watchEditedAt = 21),
                RunFinishSet(0.0, 0, seconds = 30, done = false),
            ))),
        )
        assertEquals(finish, SyncCodec.decodeRunFinish(SyncCodec.encodeRunFinish(finish)))
        assertEquals(1, finish.schemaVersion)
        assertEquals("/strengthlog/run-finish", WearSyncPaths.RUN_FINISH)
    }

    @Serializable
    private data class OldSetEditDelta(
        val schemaVersion: Int = 1,
        val dayId: String,
        val programExerciseId: Long,
        val slot: String,
        val setIndex: Int,
        val weightLb: Double? = null,
        val reps: Int? = null,
        val done: Boolean? = null,
        val seconds: Int? = null,
        val editedAtMillis: Long,
        val startedAtMillis: Long? = null,
        val completedAtMillis: Long? = null,
        val planId: Long? = null,
    )
}
