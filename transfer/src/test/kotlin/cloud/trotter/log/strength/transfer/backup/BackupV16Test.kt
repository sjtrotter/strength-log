package cloud.trotter.log.strength.transfer.backup

import cloud.trotter.log.strength.data.FullSnapshot
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetStructure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BackupV16Test {
    private val codec = BackupCodec()
    private val prescription = PrescriptionJson.encode(Prescription(PrescribedTarget.LoadReps(225.0, 5)))

    private fun document(slots: List<ProgramExerciseBackup>) = BackupDocument(
        settings = SettingsBackup(200, 30, "INTERMEDIATE", weightUnit = "LB", wizardComplete = true, selectedPlanId = 1),
        plans = listOf(PlanBackup(1, "Plan", "GENERATED", 1000, onToday = true, days = listOf(
            ProgramDayBackup("A", "Day A", "", exercises = slots),
        ))),
    )

    @Test fun `v15 reads main flags independently of prescriptions and ignores future structures`() {
        val slots = listOf(
            ProgramExerciseBackup(1, "bb_back_squat", targetSets = 6, note = "main", isMain = true),
            ProgramExerciseBackup(2, "bb_bench", targetSets = 2, supersetExerciseId = "ez_curl", note = "written main",
                prescriptionJson = prescription, keepToPlan = false, isMain = true),
            ProgramExerciseBackup(3, "ez_curl", targetSets = 4, note = "written straight",
                prescriptionJson = prescription, keepToPlan = true, isMain = false),
            ProgramExerciseBackup(4, "plank", targetSets = 3, note = ""),
        )
        val legacy = document(slots).copy(schemaVersion = 15)
        val encoded = codec.encode(legacy).replace("\"structure\":null,", "")
        val poisoned = encoded.replace("\"targetSets\":6", "\"structure\":{},\"repSchemeLabel\":[],\"hasWarmupHint\":{},\"targetSets\":6")
        for (text in listOf(encoded, poisoned)) {
            val restored = codec.decode(text).toSnapshot().exercises
            assertEquals(listOf("RAMP_TOP_BACKOFF", "RAMP_TOP_BACKOFF", "STRAIGHT", "STRAIGHT"), restored.map { it.structure })
            assertEquals(listOf(1L, 2L, 3L, 4L), restored.map { it.id })
            assertEquals(listOf(0, 1, 2, 3), restored.map { it.position })
            assertEquals(slots.map { it.exerciseId }, restored.map { it.exerciseId })
            assertEquals(slots.map { it.targetSets }, restored.map { it.targetSets })
            assertEquals(slots.map { it.supersetExerciseId }, restored.map { it.supersetExerciseId })
            assertEquals(slots.map { it.note }, restored.map { it.note })
            assertEquals(slots.map { it.prescriptionJson }, restored.map { it.prescriptionJson })
            assertEquals(listOf(false, false, true, false), restored.map { it.keepToPlan })
        }
    }

    @Test fun `v16 round trip preserves every structure including ramp top and writes only structure`() {
        val slots = SetStructure.entries.mapIndexed { index, structure ->
            ProgramExerciseBackup(index + 1L, "bb_back_squat", structure.name, 3, note = structure.name,
                prescriptionJson = prescription, keepToPlan = false)
        }
        val snapshot = codec.decode(codec.encode(document(slots))).toSnapshot()
        val encoded = codec.encode(snapshot.toDocument())
        val restored = codec.decode(encoded)
        assertEquals(CURRENT_SCHEMA_VERSION, restored.schemaVersion)
        assertEquals(SetStructure.entries.map { it.name }, restored.plans.single().days.single().exercises.map { it.structure })
        assertEquals(snapshot, restored.toSnapshot())
        assertEquals(SetStructure.entries, restored.toSnapshot().exercises.map { it.toDomain().structure })
        for (key in listOf("isMain", "repSchemeLabel", "hasWarmupHint")) assertFalse("\"$key\"" in encoded)
    }

    @Test fun `unknown or missing v16 structure restores as straight and ignores legacy main flags`() {
        for (structure in listOf("FUTURE_STRUCTURE", null)) {
            val slot = ProgramExerciseBackup(1, "bb_back_squat", structure, 3, note = "")
            val encoded = codec.encode(document(listOf(slot))).replace("\"structure\":null,", "")
            for (legacyFlag in listOf("true", "{}")) {
                val text = encoded.replace("\"targetSets\":3", "\"isMain\":$legacyFlag,\"targetSets\":3")
                val restored = codec.decode(text).toSnapshot().exercises.single()
                assertEquals("STRAIGHT", restored.structure)
                assertEquals(SetStructure.STRAIGHT, restored.toDomain().structure)
            }
        }
    }

    @Test fun `all fixture programs equal their pre-v16 domain programs and survive v16 export`() {
        val main = ProgramExercise("bb_back_squat", SetStructure.fromLegacy(true), targetSets = 4)
        val straightPlank = ProgramExercise("plank", SetStructure.fromLegacy(false))
        val straightCustom = ProgramExercise("custom_abc", SetStructure.fromLegacy(false))
        val before = mapOf(
            "v1_backup.json" to listOf(main, straightPlank),
            "v2_backup.json" to listOf(main, straightCustom),
            "v3_backup.json" to listOf(main, straightCustom),
            "v4_backup.json" to listOf(main),
            "v7_backup.json" to listOf(main),
        )
        for ((fixture, slots) in before) {
            val text = checkNotNull(javaClass.getResource("/backup/$fixture")).readText()
            val snapshot = codec.decode(text).toSnapshot()
            val expected = listOf(ProgramDay("A", "Day A", "Squat-focused", slots))
            fun programs(restored: FullSnapshot): List<ProgramDay> = restored.days.map { day ->
                day.toDomain(restored.exercises.filter { it.planId == day.planId && it.dayId == day.dayId })
            }
            assertEquals(expected, programs(snapshot), fixture)
            val current = snapshot.copy(plans = listOf(
                PlanEntity(PlanEntity.LEGACY_ID, "Plan", "GENERATED", 0, 1000),
            )).toDocument()
            assertEquals(expected, programs(codec.decode(codec.encode(current)).toSnapshot()), fixture)
        }
    }
}
