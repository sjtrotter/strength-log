package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.FullSnapshot
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.PlanOrigin
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Saved plans share day names, but never slots or live logs, in a v8 backup. */
class BackupV8Test {

    private val codec = BackupCodec()

    private fun snapshot() = FullSnapshot(
        answers = WizardAnswers(),
        unit = WeightUnit.LB,
        wizardComplete = true,
        suggestedDay = "A",
        selectedPlanId = 2,
        restSettings = RestSettings(),
        plans = listOf(
            PlanEntity(1, "Maintenance", PlanOrigin.GENERATED.name, 0, 1_000),
            PlanEntity(2, "Test prep", PlanOrigin.AUTHORED.name, 1, 2_000),
        ),
        customExercises = emptyList(),
        days = listOf(
            ProgramDayEntity(1, "A", 0, "Maintenance A", "Squat", null),
            ProgramDayEntity(2, "A", 0, "Test A", "Bench", null),
        ),
        exercises = listOf(
            ProgramExerciseEntity(11, 1, "A", 0, "bb_back_squat", true, 4, "5", true, null, "belt"),
            ProgramExerciseEntity(21, 2, "A", 0, "bb_bench", true, 3, "8", false, null, "pause"),
        ),
        logs = listOf(
            ExerciseLogEntity(1, "A", 11, "main", """[{"weightLb":225.0,"reps":5,"kind":"TOP","done":true}]""", 3_000),
            ExerciseLogEntity(2, "A", 21, "main", "[]", 4_000),
        ),
        sessions = listOf(
            WorkoutSessionEntity(7, "A", "Test A", 5_000, 65_000, 210, planId = 2, planName = "Test prep"),
        ),
        sessionSets = listOf(
            SessionSetEntity(71, 7, "bb_bench", "Barbell Bench Press", "main", 0, "WORK", 135.0, 8, true),
        ),
        cardioSessions = listOf(
            CardioSessionEntity(
                id = 8, dayId = "A", mode = "TREADMILL", hard = false, label = "Easy",
                startedAt = 5_000, completedAt = 65_000, seconds = 60, stepsCompleted = 1,
                planId = 2, planName = "Test prep",
            ),
        ),
    )

    // Keep the fixture at v8 even though production exports write v9.
    private fun FullSnapshot.toV8Document(): BackupDocument = toDocument().let { document ->
        document.copy(
            schemaVersion = 8,
            settings = document.settings.copy(
                emphasis = answers.config.emphasis.name,
                cardioMode = answers.cardio.mode.name,
                cardioPlacement = answers.cardio.placement.name,
                fiveKGoal = answers.cardio.fiveKGoal,
                daysPerWeek = answers.daysPerWeek,
                split = answers.split.name,
                anchorScheme = answers.anchorScheme.name,
                deadliftVariant = answers.deadliftVariant.name,
                equipment = answers.equipment.map { it.name }.sorted(),
                suggestedDay = suggestedDay,
            ),
        )
    }

    private fun document() = snapshot().toV8Document()

    private fun decode(document: BackupDocument) = codec.decode(codec.encode(document))

    private fun legacyJson(): String =
        checkNotNull(javaClass.getResourceAsStream("/backup/v7_backup.json")) { "missing v7 fixture" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `two plans and their days slots logs selection and history survive a full cycle`() {
        val before = snapshot()
        val restored = decode(before.toV8Document()).toSnapshot()

        assertEquals(before, restored)
        assertEquals(listOf("Maintenance", "Test prep"), restored.plans.map { it.name })
        assertEquals(listOf(0, 1), restored.plans.map { it.position })
        assertEquals(listOf(1L, 2L), restored.days.map { it.planId })
        assertEquals(listOf(11L, 21L), restored.exercises.map { it.id })
        assertEquals(listOf(1L, 2L), restored.logs.map { it.planId })
        assertEquals(2L, restored.selectedPlanId)
        assertEquals(2L, restored.sessions.single().planId)
        assertEquals("Test prep", restored.sessions.single().planName)
    }

    @Test
    fun `encoding writes v8 with an empty legacy program and is byte-identical`() {
        val before = snapshot()
        val first = codec.encode(before.toV8Document())

        assertTrue("\"schemaVersion\":8" in first)
        assertTrue("\"program\":[]" in first)
        assertEquals(first, codec.encode(before.toV8Document()))
        assertEquals(first, codec.encode(codec.decode(first).toSnapshot().toV8Document()))
    }

    @Test
    fun `export orders plans days and slots by stored position and restore uses list order`() {
        val before = snapshot()
        val firstDay = before.days.first()
        val firstExercise = before.exercises.first()
        val unordered = before.copy(
            plans = before.plans.reversed().map { it.copy(position = 0) },
            days = listOf(firstDay.copy(dayId = "B", position = 7), firstDay.copy(position = 3), before.days.last()),
            exercises = listOf(
                firstExercise.copy(id = 12, position = 7), firstExercise.copy(position = 3), before.exercises.last(),
            ),
        )
        val doc = unordered.toV8Document()
        assertEquals(listOf(1L, 2L), doc.plans.map { it.id })
        assertEquals(listOf("A", "B"), doc.plans.first().days.map { it.dayId })
        assertEquals(listOf(11L, 12L), doc.plans.first().days.first().exercises.map { it.id })

        val restored = decode(doc).toSnapshot()
        assertEquals(listOf(0, 1), restored.plans.map { it.position })
        assertEquals(listOf(0, 1), restored.days.filter { it.planId == 1L }.map { it.position })
        assertEquals(listOf(0, 1), restored.exercises.filter { it.planId == 1L }.map { it.position })
    }

    @Test
    fun `v7 still maps to legacy rows without plans or selection`() {
        val restored = codec.decode(legacyJson()).toSnapshot()

        assertTrue(restored.plans.isEmpty())
        assertNull(restored.selectedPlanId)
        assertEquals(listOf(PlanEntity.LEGACY_ID), restored.days.map { it.planId })
        assertEquals(listOf(PlanEntity.LEGACY_ID), restored.exercises.map { it.planId })
        assertEquals(listOf(PlanEntity.LEGACY_ID), restored.legacyLogs.map { it.planId })
        assertNull(restored.sessions.last().bodyweightLb)
    }

    @Test
    fun `v8 with a non-empty legacy program is malformed`() {
        val doc = document()
        val error = assertFailsWith<BackupError.Malformed> {
            decode(doc.copy(program = doc.plans.first().days))
        }
        assertTrue("program must be empty in schema v8" in error.message.orEmpty())
    }

    @Test
    fun `v7 ignores stray plans and live-log planId and restores the legacy program`() {
        val legacy = codec.decode(legacyJson())
        val decoded = decode(legacy.copy(
            plans = document().plans,
            liveLogs = legacy.liveLogs.map { it.copy(planId = 99) },
        ))
        val restored = decoded.toSnapshot()

        assertEquals(legacy.program, decoded.program)
        assertTrue(decoded.plans.isEmpty())
        assertTrue(decoded.liveLogs.all { it.planId == null })
        assertEquals(legacy.toSnapshot(), restored)
        assertTrue(restored.plans.isEmpty())
        assertNull(restored.selectedPlanId)
        assertEquals(listOf(PlanEntity.LEGACY_ID), restored.days.map { it.planId })
        assertEquals(listOf(PlanEntity.LEGACY_ID), restored.exercises.map { it.planId })
        assertEquals(listOf(PlanEntity.LEGACY_ID), restored.legacyLogs.map { it.planId })
    }

    @Test
    fun `duplicate plan ids are rejected`() {
        val doc = document()
        val duplicate = doc.plans.first().copy(days = emptyList())
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(doc.copy(
                plans = doc.plans + duplicate,
                liveLogs = doc.liveLogs.filter { it.planId != duplicate.id },
            ))
        }
        assertTrue("duplicate plan id 1" in error.message.orEmpty())
    }

    @Test
    fun `non-positive plan ids are rejected`() {
        val doc = document()
        val plan = doc.plans.first()
        for (id in listOf(0L, -1L)) {
            val error = assertFailsWith<BackupError.Inconsistent> {
                decode(doc.copy(
                    plans = listOf(plan.copy(id = id), doc.plans.last()),
                    liveLogs = doc.liveLogs.map { if (it.planId == plan.id) it.copy(planId = id) else it },
                ))
            }
            assertTrue("plan id must be positive: $id" in error.message.orEmpty())
        }
    }

    @Test
    fun `duplicate day ids within a plan are rejected`() {
        val doc = document()
        val plan = doc.plans.first()
        val duplicate = plan.days.single().copy(exercises = emptyList())
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(doc.copy(
                plans = listOf(plan.copy(days = plan.days + duplicate), doc.plans.last()),
                liveLogs = doc.liveLogs.filter { it.planId != plan.id },
            ))
        }
        assertTrue("duplicate day id 'A'" in error.message.orEmpty())
    }

    @Test
    fun `the same day id in different plans is accepted`() {
        assertEquals(listOf("A", "A"), decode(document()).plans.flatMap { it.days }.map { it.dayId })
    }

    @Test
    fun `duplicate program exercise ids across plans are rejected`() {
        val doc = document()
        val plan = doc.plans.last()
        val day = plan.days.single()
        val duplicate = day.exercises.single().copy(id = 11)
        val duplicatePlan = plan.copy(days = listOf(day.copy(exercises = listOf(duplicate))))
        // The second plan's log follows its slot, leaving only global slot-id uniqueness to reject this.
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(doc.copy(
                plans = listOf(doc.plans.first(), duplicatePlan),
                liveLogs = doc.liveLogs.map { if (it.planId == plan.id) it.copy(programExerciseId = duplicate.id) else it },
            ))
        }
        assertTrue("duplicate program exercise id 11" in error.message.orEmpty())
    }

    @Test
    fun `a live log cannot name another plan's day`() {
        val doc = document()
        val plan = doc.plans.last()
        val otherDay = plan.days.single().copy(dayId = "B")
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(doc.copy(
                settings = doc.settings.copy(suggestedDay = null),
                plans = listOf(doc.plans.first(), plan.copy(days = listOf(otherDay))),
                liveLogs = listOf(doc.liveLogs.first().copy(dayId = "B")),
            ))
        }
        assertTrue("live log references unknown day 'B'" in error.message.orEmpty())
    }

    @Test
    fun `a live log cannot name another plan's slot on a shared day id`() {
        val doc = document()
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(doc.copy(liveLogs = listOf(doc.liveLogs.last().copy(planId = 1))))
        }
        assertTrue("live log references unknown slot 21 in day 'A'" in error.message.orEmpty())
    }

    @Test
    fun `v8 live logs require planId`() {
        val doc = document()
        val text = codec.encode(doc.copy(liveLogs = listOf(doc.liveLogs.first())))
            .replace("\"planId\":1,", "")
        val error = assertFailsWith<BackupError.Inconsistent> {
            codec.decode(text)
        }
        assertTrue("live log planId is required in schema v8" in error.message.orEmpty())
    }

    @Test
    fun `live logs naming a missing plan are rejected`() {
        val doc = document()
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(doc.copy(liveLogs = listOf(doc.liveLogs.first().copy(planId = 99))))
        }
        assertTrue("live log references unknown plan 99" in error.message.orEmpty())
    }

    @Test
    fun `duplicate live logs within a plan are rejected`() {
        val doc = document()
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(doc.copy(liveLogs = doc.liveLogs + doc.liveLogs.first()))
        }
        assertTrue("duplicate live log for (A, 11, main)" in error.message.orEmpty())
    }

    @Test
    fun `selectedPlanId naming a missing plan is rejected`() {
        val doc = document()
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(doc.copy(settings = doc.settings.copy(selectedPlanId = 99)))
        }
        assertTrue("selectedPlanId references unknown plan 99" in error.message.orEmpty())
    }

    @Test
    fun `selectedPlanId naming an archived plan is rejected`() {
        val doc = document()
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(doc.copy(plans = listOf(doc.plans.first(), doc.plans.last().copy(archived = true))))
        }
        assertTrue("selectedPlanId references archived plan 2" in error.message.orEmpty())
    }

    @Test
    fun `suggestedDay must belong to the selected plan`() {
        val doc = document()
        val selected = doc.plans.last()
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(doc.copy(
                plans = listOf(doc.plans.first(), selected.copy(days = listOf(selected.days.single().copy(dayId = "B")))),
                liveLogs = emptyList(),
            ))
        }
        assertTrue("suggestedDay 'A' is not a day in this backup" in error.message.orEmpty())
    }

    @Test
    fun `suggestedDay falls back to the first non-archived plan in list order`() {
        val doc = document()
        val first = doc.plans.first().copy(days = listOf(doc.plans.first().days.single().copy(dayId = "B")))
        val fallback = doc.copy(
            settings = doc.settings.copy(selectedPlanId = null),
            plans = listOf(first.copy(archived = true), doc.plans.last()),
            liveLogs = emptyList(),
        )
        assertEquals("A", decode(fallback).settings.suggestedDay)
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(fallback.copy(plans = listOf(first, doc.plans.last())))
        }
        assertTrue("suggestedDay 'A' is not a day in this backup" in error.message.orEmpty())
    }

    @Test
    fun `archived and empty plans survive the mapping`() {
        val before = snapshot().copy(plans = snapshot().plans + PlanEntity(3, "Retired", "AUTHORED", 2, 500, archived = true))
        assertEquals(before, decode(before.toV8Document()).toSnapshot())
    }

    @Test
    fun `unknown plan origins are rejected`() {
        val doc = document()
        val error = assertFailsWith<BackupError.Inconsistent> {
            decode(doc.copy(plans = listOf(doc.plans.first().copy(origin = "UNKNOWN"), doc.plans.last())))
        }
        assertTrue("unknown plan origin 'UNKNOWN'" in error.message.orEmpty())
    }

    @Test
    fun `a future schema is rejected as unsupported`() {
        val error = assertFailsWith<BackupError.UnsupportedSchemaVersion> { decode(document().copy(schemaVersion = CURRENT_SCHEMA_VERSION + 1)) }
        assertEquals(CURRENT_SCHEMA_VERSION + 1, error.found)
        assertEquals(CURRENT_SCHEMA_VERSION, error.supported)
        assertTrue("Unsupported backup schema version ${CURRENT_SCHEMA_VERSION + 1}" in error.message.orEmpty())
    }

    @Test
    fun `history may reference a deleted plan`() {
        val doc = document()
        val history = doc.copy(
            sessions = doc.sessions.map { it.copy(planId = 99, planName = "Deleted plan") },
            cardioSessions = doc.cardioSessions.map { it.copy(planId = 99, planName = "Deleted plan") },
        )
        val restored = decode(history).toSnapshot()
        assertEquals(99L, restored.sessions.single().planId)
        assertEquals("Deleted plan", restored.sessions.single().planName)
        assertEquals(99L, restored.cardioSessions.single().planId)
        assertEquals("Deleted plan", restored.cardioSessions.single().planName)
    }

    @Test
    fun `empty v8 plans do not create a legacy program`() {
        val empty = document().copy(
            settings = document().settings.copy(selectedPlanId = null, suggestedDay = null),
            plans = emptyList(),
            liveLogs = emptyList(),
        )
        val restored = decode(empty).toSnapshot()
        assertTrue(restored.plans.isEmpty())
        assertTrue(restored.days.isEmpty())
        assertTrue(restored.exercises.isEmpty())
        assertNull(restored.selectedPlanId)
    }

    @Test
    fun `legacy recipe fields remain required and non-null in every version through v8`() {
        val fields = listOf(
            "emphasis", "cardioMode", "cardioPlacement", "fiveKGoal", "daysPerWeek",
            "split", "anchorScheme", "deadliftVariant", "equipment",
        )
        for (version in 1..8) {
            val document = document().copy(
                schemaVersion = version,
                settings = document().settings.copy(suggestedDay = null),
                liveLogs = emptyList(),
            )
            decode(document)
            val root = Json.parseToJsonElement(codec.encode(document)).jsonObject
            val settings = root.getValue("settings").jsonObject
            fields.forEach { field ->
                for (broken in listOf(JsonObject(settings - field), JsonObject(settings + (field to JsonNull)))) {
                    val error = assertFailsWith<BackupError.Malformed> {
                        codec.decode(JsonObject(root + ("settings" to broken)).toString())
                    }
                    assertTrue("settings $field is required before schema v9" in error.message.orEmpty())
                }
            }
        }
    }

    @Test
    fun `v8 ignores stray per-plan recipe and rotation fields`() {
        val document = document()
        val root = Json.parseToJsonElement(codec.encode(document)).jsonObject
        val plans = JsonArray(root.getValue("plans").jsonArray.map { plan ->
            JsonObject(plan.jsonObject + mapOf(
                "recipeJson" to JsonObject(emptyMap()),
                "suggestedDay" to JsonObject(emptyMap()),
            ))
        })
        val text = JsonObject(root + ("plans" to plans)).toString()

        assertEquals(snapshot(), codec.decode(text).toSnapshot())
    }
}
