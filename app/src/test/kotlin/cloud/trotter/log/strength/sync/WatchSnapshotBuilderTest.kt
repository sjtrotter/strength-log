package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.domain.model.CardioProgress
import cloud.trotter.log.strength.data.DayTracks
import cloud.trotter.log.strength.data.PerformedDay
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.data.LoggedSlot
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.ExerciseEntry
import cloud.trotter.log.strength.domain.library.GoalSource
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.NextTimeMode
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.RunCap
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.sync.toWatch
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.widget.TodayWidgetState
import cloud.trotter.log.strength.widget.WidgetStatLine
import cloud.trotter.log.strength.widget.todayWidgetContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * The phone->watch projection (pure). It must present exactly what the day screen
 * shows — canonical-lb weights, phone-computed goals, aligned superset rows — and
 * publish only the suggested day.
 */
class WatchSnapshotBuilderTest {

    @Test fun `cardio Progress sends working values and Plan sends authored values`() {
        val opening = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1200))))
        val working = opening.copy(sets = listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1260))))
        val day = ProgramDay("A", "Run", "", emptyList(), cardioBlock = opening,
            cardioProgress = CardioProgress(working = working))
        fun snapshot(day: ProgramDay) = WatchSnapshotBuilder.build(
            performedDay(Program(listOf(day)), "A", emptyList(), emptyList()), WeightUnit.LB, revision = 1)!!
        assertEquals(working, snapshot(day).cardioBlock?.toDomain())
        assertEquals(opening, snapshot(day.copy(cardioProgress = day.cardioProgress.copy(keepToPlan = true))).cardioBlock?.toDomain())
    }

    @Test fun theCardioOpeningRidesTheWireInPlaceOfTheStoredBlock() {
        val stored = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(3600))))
        val opening = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(3180))))
        val day = ProgramDay("A", "Run", "", emptyList(), cardioBlock = stored)
        val performed = performedDay(Program(listOf(day)), "A", emptyList(), emptyList())
        val snapshot = WatchSnapshotBuilder.build(performed, WeightUnit.LB, revision = 1, cardioOpening = opening)!!
        assertEquals(opening.toWatch(), snapshot.cardioBlock)
        assertEquals(stored.toWatch(), WatchSnapshotBuilder.build(performed, WeightUnit.LB, revision = 1)!!.cardioBlock)
    }

    @Test fun aCappedOpeningDropsTheStoredBlocksOnlyHardSet() {
        val easy = CardioSetSpec(CardioRole.WORK, Measure.Time(1200))
        val stored = CardioBlock(CardioActivity.ROAD_RUN, listOf(easy, easy.copy(hard = true)))
        val opening = RunCap.capped(stored, RecentMaxima(null, 1200), null)
        assertEquals(stored.copy(sets = listOf(easy)), opening)
        val day = ProgramDay("A", "Run", "", emptyList(), cardioBlock = stored)
        val performed = performedDay(Program(listOf(day)), "A", emptyList(), emptyList())

        val snapshot = WatchSnapshotBuilder.build(performed, WeightUnit.LB, revision = 1, cardioOpening = opening)!!

        assertEquals(opening.toWatch(), snapshot.cardioBlock)
        assertEquals(false, snapshot.cardio?.hard)
        assertEquals(true, WatchSnapshotBuilder.build(performed, WeightUnit.LB, revision = 1)!!.cardio?.hard)
    }

    private val catalog = ExerciseCatalog.CODE_ONLY
    private val cfg = LifterConfig() // the pinned §11 lifter (235/40/INTERMEDIATE/BALANCED)

    private val program = Program(
        listOf(
            ProgramDay("A", "Day A — Squat", "quads", listOf(ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF))),
            ProgramDay("B", "Day B — Bench", "chest", listOf(ProgramExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF))),
        ),
    )

    @Test
    fun `a gap in day letters keeps the third accent on the watch`() {
        val withGap = Program(listOf(program.days[0], program.days[1].copy(id = "C")))
        val performed = performedDay(withGap, "C", emptyList(), emptyList(), planId = 42L)
        val snapshot = WatchSnapshotBuilder.build(performed, WeightUnit.LB, revision = 1L)!!

        assertEquals("C", snapshot.day.dayId)
        assertEquals(2, snapshot.day.accentIndex)
        assertEquals(listOf("A", "C"), snapshot.cycle.map { it.dayId })

        val withoutGap = Program(listOf(withGap.days[0], program.days[1], withGap.days[1]))
        val fullCycle = WatchSnapshotBuilder.build(
            performedDay(withoutGap, "C", emptyList(), emptyList(), planId = 42L),
            WeightUnit.LB, revision = 1L,
        )!!
        assertEquals(fullCycle.copy(cycle = fullCycle.cycle.filterNot { it.dayId == "B" }), snapshot)
    }

    @Test
    fun `snapshot carries cardio processed stamps alongside the legacy max`() {
        val snapshot = WatchSnapshotBuilder.build(
            performedDay(program, "A", emptyList(), emptyList()),
            unit = WeightUnit.LB, revision = 1L, cardioAckStamp = 101L, cardioAckedStamps = listOf(101L, 100L),
        )!!
        assertEquals(listOf(101L, 100L), snapshot.cardioAckedStamps)
        assertEquals(101L, snapshot.cardioAckStamp)
        val legacy = WatchSnapshotBuilder.build(
            performedDay(program, "A", emptyList(), emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!
        assertEquals(emptyList(), legacy.cardioAckedStamps)
    }

    @Test
    fun `cardio-only authored day retains its identity and effective block`() {
        val block = CardioBlock(
            CardioActivity.TRACK_RUN,
            listOf(CardioSetSpec(
                CardioRole.WORK,
                Measure.Distance(400),
                Measure.Time(105), hard = true,
            )),
        )
        val running = Program(listOf(ProgramDay("R", "Track day", "", emptyList(),
            kind = ProgramDayKind.CARDIO, cardioBlock = block)))
        val snapshot = WatchSnapshotBuilder.build(
            performedDay(running, "R", emptyList(), emptyList(), planId = 42L),
            unit = WeightUnit.LB, revision = 1L, planName = "Running",
        )!!
        assertEquals("R", snapshot.suggestedDayId)
        assertEquals("Track day", snapshot.day.title)
        assertEquals(emptyList(), snapshot.day.exercises)
        assertEquals(block, snapshot.cardioBlock?.toDomain())
        assertEquals(42L, snapshot.planId)
        assertEquals(listOf("R"), snapshot.cycle.map { it.dayId })
    }

    @Test
    fun `typed finisher publishes sets and logged facts without a legacy timer offer`() {
        val block = CardioBlocks.fromLegacy(
            CardioSuggestion("Intervals", "", true), true, CardioActivity.ROAD_RUN,
        )
        val generated = program.copy(days = listOf(program.days.first().copy(cardioBlock = block)))
        val logged = CardioSessionEntity(
            dayId = "A", mode = "OUTDOOR_RUN", hard = true, label = "Intervals",
            startedAt = 1_000, completedAt = 91_000, seconds = 90, stepsCompleted = 1,
        )
        val snapshot = WatchSnapshotBuilder.build(
            performedDay(generated, "A", emptyList(), emptyList()),
            unit = WeightUnit.LB, revision = 1L, loggedCardio = logged,
        )!!
        assertEquals(CardioSuggestion("Road run", "", true, loggedStartedAt = 1_000, loggedSeconds = 90), snapshot.cardio)
        assertEquals(null, snapshot.cardio?.mode)
        assertEquals(null, snapshot.cardio?.fiveK)
        assertEquals(11, snapshot.cardioBlock?.sets?.size)
        assertEquals("RECOVERY", snapshot.cardioBlock?.sets?.last()?.role)
        val otherDay = WatchSnapshotBuilder.build(
            performedDay(generated, "A", emptyList(), emptyList()),
            unit = WeightUnit.LB, revision = 1L, loggedCardio = logged.copy(dayId = "B"),
        )!!
        assertEquals(null, otherDay.cardio?.loggedStartedAt)
        assertEquals(null, otherDay.cardio?.loggedSeconds)
    }

    @Test
    fun `day without a block has no cardio carrier even with logged history`() {
        val snapshot = WatchSnapshotBuilder.build(
            performedDay(program, "A", emptyList(), emptyList()),
            unit = WeightUnit.LB, revision = 1L, loggedCardio = CardioSessionEntity(
                dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Easy",
                startedAt = 1_000, completedAt = 91_000, seconds = 90, stepsCompleted = 1,
            ),
        )!!
        assertEquals(null, snapshot.cardio)
        assertEquals(null, snapshot.cardioBlock)
    }

    @Test
    fun `cardio day with core and no block stays in the cycle and builds the target snapshot`() {
        val core = ProgramExercise("plank", targetSets = 2)
        val day = ProgramDay("C1", "Cardio + Core", "", listOf(core), kind = ProgramDayKind.CARDIO)
        val withCoreDay = program.copy(days = program.days + day)
        val fromStrength = WatchSnapshotBuilder.build(
            performedDay(withCoreDay, "A", emptyList(), emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!
        assertEquals(listOf("A", "B", "C1"), fromStrength.cycle.map { it.dayId })
        assertEquals(2, fromStrength.cycle.last().exercises.single().setCount)

        val snapshot = WatchSnapshotBuilder.build(
            performedDay(withCoreDay, "C1", listOf(ProgramSlot(10L, 0, core)), emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!
        assertEquals("C1", snapshot.suggestedDayId)
        assertEquals("Cardio + Core", snapshot.day.title)
        assertEquals(2, snapshot.day.accentIndex)
        assertEquals(10L, snapshot.day.exercises.single().programExerciseId)
        assertNull(snapshot.cardio)
        assertNull(snapshot.cardioBlock)

        val widget = todayWidgetContent(snapshot)
        assertEquals("Day C1, Cardio + Core", widget.dayLine)
        assertEquals(TodayWidgetState.BEFORE, widget.state)
        assertEquals(WidgetStatLine.Data("1 lift, 2 sets"), widget.statLine)
    }

    private fun performedDay(
        program: Program,
        dayId: String,
        slots: List<ProgramSlot>,
        logs: List<LoggedSlot>,
        dropped: Set<Long> = emptySet(),
        skipped: Set<Long> = emptySet(),
        catalog: ExerciseCatalog = this.catalog,
        planId: Long = 1L,
        seeds: Map<Pair<Long, String>, List<LoggedSet>> = emptyMap(),
        config: LifterConfig = cfg,
        openings: Map<Pair<Long, String>, List<LoggedSet>> = emptyMap(),
    ): PerformedDay = PerformedDay.of(
        planId, dayId, program, slots,
        DayTracks(null, false, logs, droppedPartners = dropped, skippedLifts = skipped),
        WizardAnswers(config = config), catalog, seeds, openings,
    )

    private fun loggedSlot(id: Long, slot: String, sets: List<LoggedSet>) =
        LoggedSlot(id, slot, sets)

    @Test fun `prescribed labels keep their count and follow the working values`() {
        val cases = listOf(
            Triple(PrescribedTarget.LoadReps(185.0, 5), LoggedSet(190.0, 7, SetKind.WORK), "3 × 7 · 190 lb"),
            Triple(PrescribedTarget.Reps(8), LoggedSet(0.0, 9, SetKind.WORK), "3 × 9"),
            Triple(PrescribedTarget.Hold(45, 25.0), LoggedSet(30.0, 0, SetKind.WORK, seconds = 50), "3 × 0:50 · 30 lb"),
            Triple(PrescribedTarget.RepsInTime(120, 40), LoggedSet(0.0, 45, SetKind.WORK, seconds = 130), "2:00 · aim 40"),
        )
        for ((target, row, label) in cases) {
            val exercise = ProgramExercise("bb_bench", targetSets = 3, prescription = Prescription(target))
            val program = Program(listOf(ProgramDay("A", "Test", "", listOf(exercise))))
            val snapshot = WatchSnapshotBuilder.build(performedDay(program, "A", listOf(ProgramSlot(1, 0, exercise)),
                listOf(LoggedSlot(1, Slot.MAIN, List(3) { row }))), WeightUnit.LB, revision = 1)!!
            assertEquals(label, snapshot.day.exercises.single().goalLabel)
        }
    }

    @Test fun `ramped prescribed labels keep the authored count and use the top values in either unit`() {
        val exercise = ProgramExercise("bb_bench", SetStructure.RAMP_TOP_BACKOFF, targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5)))
        val program = Program(listOf(ProgramDay("A", "Test", "", listOf(exercise))))
        val rows = listOf(LoggedSet(100.0, 8, SetKind.RAMP), LoggedSet(190.0, 7, SetKind.TOP),
            LoggedSet(140.0, 8, SetKind.BACKOFF))
        val day = performedDay(program, "A", listOf(ProgramSlot(1, 0, exercise)), listOf(LoggedSlot(1, Slot.MAIN, rows)))
        for ((unit, label) in listOf(WeightUnit.LB to "3 × 7 · 190 lb", WeightUnit.KG to "3 × 7 · 86.18 kg")) {
            val snapshot = WatchSnapshotBuilder.build(day, unit, revision = 1)!!
            assertEquals(label, snapshot.day.exercises.single().goalLabel)
        }
    }

    @Test fun `automatic prescribed push-ups follow the opening working reps`() {
        val rule = SlotRule.defaults(SlotRole.ACCESSORY, LiftGoal.STRENGTH, SetStructure.STRAIGHT, 8,
            MovementPattern.H_PUSH).copy(nextTime = NextTimeMode.AUTO)
        val exercise = ProgramExercise("pushup", targetSets = 3,
            prescription = Prescription(PrescribedTarget.Reps(8)), rule = rule)
        val program = Program(listOf(ProgramDay("A", "Test", "", listOf(exercise))))
        val day = performedDay(program, "A", listOf(ProgramSlot(1, 0, exercise)),
            listOf(LoggedSlot(1, Slot.MAIN, List(3) { LoggedSet(0.0, 8, SetKind.WORK) })),
            openings = mapOf((1L to Slot.MAIN) to List(3) { LoggedSet(0.0, 9, SetKind.WORK) }))

        val snapshot = WatchSnapshotBuilder.build(day, WeightUnit.LB, revision = 1)!!

        assertEquals("3 × 9", snapshot.day.exercises.single().goalLabel)
    }

    @Test fun `range load reps labels follow the working reps and load`() {
        val rule = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH, SetStructure.STRAIGHT, 5,
            MovementPattern.H_PUSH).copy(target = RepTarget.Range(5, 8, 6))
        val exercise = ProgramExercise("bb_bench", targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(180.0, 5)), rule = rule)
        val program = Program(listOf(ProgramDay("A", "Test", "", listOf(exercise))))
        val day = performedDay(program, "A", listOf(ProgramSlot(1, 0, exercise)),
            listOf(LoggedSlot(1, Slot.MAIN, List(3) { LoggedSet(185.0, 6, SetKind.WORK) })))

        val snapshot = WatchSnapshotBuilder.build(day, WeightUnit.LB, revision = 1)!!

        assertEquals("3 × 6 · 185 lb", snapshot.day.exercises.single().goalLabel)
    }

    @Test fun `load reps labels keep the prescribed load when the working row has none`() {
        val exercise = ProgramExercise("bb_bench", targetSets = 3,
            prescription = Prescription(PrescribedTarget.LoadReps(185.0, 5)))
        val program = Program(listOf(ProgramDay("A", "Test", "", listOf(exercise))))
        val day = performedDay(program, "A", listOf(ProgramSlot(1, 0, exercise)),
            listOf(LoggedSlot(1, Slot.MAIN, List(3) { LoggedSet(0.0, 6, SetKind.WORK) })))

        val snapshot = WatchSnapshotBuilder.build(day, WeightUnit.LB, revision = 1)!!

        assertEquals("3 × 6 · 185 lb", snapshot.day.exercises.single().goalLabel)
    }

    private fun prescribed(target: PrescribedTarget, unit: WeightUnit = WeightUnit.LB) =
        WatchSnapshotBuilder.build(
            performedDay(program, "A", listOf(ProgramSlot(10L, 0, ProgramExercise(
                "bb_back_squat", targetSets = 3,
                prescription = Prescription(target, eachSide = true, resistance = "Red band"),
            ))), emptyList()),
            unit = unit, revision = 1L,
        )!!.day.exercises.single()

    @Test
    fun `authored load target and supporting detail override catalog goal in either unit`() {
        val lb = prescribed(PrescribedTarget.LoadReps(95.0, 10))
        assertEquals(95.0, lb.goal)
        assertEquals("3 × 10 · 95 lb", lb.goalLabel)
        assertEquals("Each side · Red band", lb.targetDetail)
        assertEquals(true, lb.prescribed)
        assertEquals(0, lb.repWindowSeconds)
        assertNull(lb.repAim)
        val kg = prescribed(PrescribedTarget.LoadReps(95.0, 10), WeightUnit.KG)
        assertEquals(95.0, kg.goal)
        assertEquals("3 × 10 · 43.09 kg", kg.goalLabel)
    }

    @Test
    fun `timed rep target publishes a plain reps track plus its execution facts`() {
        val window = prescribed(PrescribedTarget.RepsInTime(120, 40))
        assertEquals(0.0, window.goal)
        assertEquals("2:00 · aim 40", window.goalLabel)
        assertEquals("reps", window.tracking)
        assertEquals(120, window.repWindowSeconds)
        assertEquals(40, window.repAim)
        assertEquals("2:00", prescribed(PrescribedTarget.RepsInTime(120)).goalLabel)
        assertEquals(0.0, prescribed(PrescribedTarget.Reps(10)).goal)
    }

    @Test
    fun `an empty next day retains plan identity title and its index in the rotation`() {
        val authored = program.copy(days = program.days + ProgramDay("C", "Daily rehab", "", emptyList()))
        val snapshot = WatchSnapshotBuilder.build(
            performedDay(authored, "C", emptyList(), emptyList(), planId = 42L),
            unit = WeightUnit.LB, revision = 1L, planName = "Rehab",
        )!!
        assertEquals(42L, snapshot.planId)
        assertEquals("Rehab", snapshot.planName)
        assertEquals("C", snapshot.suggestedDayId)
        assertEquals("C", snapshot.day.dayId)
        assertEquals("Daily rehab", snapshot.day.title)
        assertEquals(2, snapshot.day.accentIndex)
        assertEquals(emptyList(), snapshot.day.exercises)
        assertEquals(listOf("A", "B", "C"), snapshot.cycle.map { it.dayId })
    }

    @Test
    fun `snapshot tracking and unopened rows follow the prescription over the catalog`() {
        val exercise = ProgramExercise("bb_back_squat", targetSets = 2, prescription = Prescription(PrescribedTarget.RepsInTime(120, 50)))
        val authored = Program(listOf(ProgramDay("A", "Test", "", listOf(exercise))))
        val projected = WatchSnapshotBuilder.build(
            performedDay(authored, "A", listOf(ProgramSlot(10L, 0, exercise)), emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        assertEquals("reps", projected.tracking)
        assertEquals(listOf(0, 0), projected.sets.map { it.reps })
        assertEquals(listOf(0, 0), projected.sets.map { it.seconds })
        assertEquals(listOf("WORK", "WORK"), projected.sets.map { it.kind })
    }

    @Test
    fun `a prescribed loaded hold sends its added load over the catalog goal`() {
        val exercise = ProgramExercise("plank", prescription = Prescription(PrescribedTarget.Hold(45, 25.0)))
        val authored = Program(listOf(ProgramDay("A", "Test", "", listOf(exercise))))
        val projected = WatchSnapshotBuilder.build(
            performedDay(authored, "A", listOf(ProgramSlot(10L, 0, exercise)), emptyList(),
                seeds = mapOf((10L to Slot.MAIN) to List(3) { LoggedSet(25.0, 0, SetKind.WORK, seconds = 45) })),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals("timed", projected.tracking)
        assertEquals(25.0, projected.goal)
        assertEquals("3 × 0:45 · 25 lb", projected.goalLabel)
        assertEquals(List(3) { 25.0 }, projected.sets.map { it.weightLb })
        assertEquals(List(3) { 45 }, projected.sets.map { it.seconds })
    }

    @Test
    fun `an unseeded day previews the domain-seeded template with every set undone`() {
        val exercise = ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)
        val slots = listOf(ProgramSlot(10L, 0, exercise))
        val expected = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, 235.0, 5, 3,
            WeightUnit.LB, listOf(Equipment.BARBELL, Equipment.RACK))

        val sets = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList(), seeds = mapOf((10L to Slot.MAIN) to expected)),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single().sets

        assertEquals(expected.size, sets.size)
        assertEquals(expected.map { it.weightLb }, sets.map { it.weightLb })
        assertEquals(expected.map { it.kind.name }, sets.map { it.kind })
        assertEquals(true, sets.all { !it.done })
    }

    @Test
    fun `unresolved catalog id has the same Today and snapshot preview count`() {
        val exercise = ProgramExercise("deleted_custom_id", targetSets = 3)
        val slot = ProgramSlot(99L, 0, exercise)
        val unresolvedProgram = Program(
            listOf(ProgramDay("A", "Custom", "", listOf(exercise))),
        )

        val sets = WatchSnapshotBuilder.build(
            performedDay(unresolvedProgram, "A", listOf(slot), emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single().sets

        assertEquals(3, sets.size)
        assertEquals(true, sets.all { it.weightLb == 0.0 && it.reps == 0 && it.kind == "WORK" && !it.done })
    }

    @Test
    fun `a partially logged day keeps its real rows unchanged`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val realSets = listOf(
            LoggedSet(145.0, 8, SetKind.EXTRA, done = true),
            LoggedSet(155.0, 6, SetKind.WORK, done = false),
        )

        val sets = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, listOf(loggedSlot(10L, Slot.MAIN, realSets))),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single().sets

        assertEquals(realSets.map { it.weightLb }, sets.map { it.weightLb })
        assertEquals(realSets.map { it.reps }, sets.map { it.reps })
        assertEquals(realSets.map { it.kind.name }, sets.map { it.kind })
        assertEquals(realSets.map { it.done }, sets.map { it.done })
    }

    @Test
    fun `projects the suggested day with canonical weights, goal and accent index`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val logs = listOf(
            loggedSlot(10L, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP, done = true))),
        )

        val snapshot = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, logs, config = cfg.copy(goalsLb = mapOf("bb_back_squat" to 235.0))),
            unit = WeightUnit.KG, revision = 3L,
        )!!

        assertEquals("A", snapshot.suggestedDayId)
        assertEquals(0, snapshot.day.accentIndex)
        assertEquals("kg", snapshot.unit) // unit label only; weights stay canonical lb
        assertEquals(3L, snapshot.revision)
        assertEquals("quads", snapshot.day.emphasisLine) // carries the day's real focus, not filler text
        val ex = snapshot.day.exercises.single()
        assertEquals(10L, ex.programExerciseId)
        assertEquals(235.0, ex.goal) // the plan's goal
        assertEquals("106.59", ex.goalLabel) // additive label in the phone's unit (235 lb -> kg), the same number the watch derives
        assertEquals(235.0, ex.sets.single().weightLb) // canonical lb, not converted
        assertEquals(true, ex.sets.single().done)
    }

    // Synthetic REPS/TIMED entries, independent of the live catalog.
    private val trackingCatalog = ExerciseCatalog(
        listOf(
            ExerciseEntry("custom_pullup", "Pull-up", MovementPattern.V_PULL, listOf(Equipment.BODYWEIGHT), perHand = false, goal = GoalSource.Reps(6), subRank = ExerciseCatalog.CUSTOM_SUBRANK),
            ExerciseEntry("custom_plank", "Plank", MovementPattern.CORE_ANTI_EXT, listOf(Equipment.BODYWEIGHT), perHand = false, goal = GoalSource.Time(45, 25.0), subRank = ExerciseCatalog.CUSTOM_SUBRANK),
        ),
    )

    @Test fun `an unprescribed lift carries only its user goal`() {
        val slots = listOf(ProgramSlot(10L, 0,
            ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val seeds = mapOf((10L to Slot.MAIN) to List(3) { LoggedSet(185.0, 5, SetKind.WORK) })
        val blank = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList(), seeds = seeds), WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        assertEquals(0.0, blank.goal)
        assertEquals("", blank.goalLabel)
        assertFalse(blank.prescribed)

        val goal = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList(), seeds = seeds,
                config = cfg.copy(goalsLb = mapOf("bb_back_squat" to 225.0))), WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        assertEquals(225.0, goal.goal)
        assertEquals("225", goal.goalLabel)
        assertFalse(goal.prescribed)
    }

    @Test
    fun `weighted goalLabel equals the number the watch already shows today`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val ex = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList(), config = cfg.copy(goalsLb = mapOf("bb_back_squat" to 235.0))),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        // Watch UI renders WeightStepper.format(unit.fromLb(goal)) == "235"; goalLabel must match.
        assertEquals(235.0, ex.goal)
        assertEquals("235", ex.goalLabel)
        assertEquals("weighted", ex.tracking)
        assertEquals(false, ex.prescribed)
        assertEquals("", ex.targetDetail)
        assertEquals(0, ex.repWindowSeconds)
        assertNull(ex.repAim)
    }

    @Test
    fun `REPS and TIMED slots project without hitting goalFor's error branch`() {
        val program = Program(
            listOf(
                ProgramDay(
                    "A", "Core", "",
                    listOf(ProgramExercise("custom_pullup"), ProgramExercise("custom_plank")),
                ),
            ),
        )
        val slots = listOf(
            ProgramSlot(1L, 0, ProgramExercise("custom_pullup")),
            ProgramSlot(2L, 1, ProgramExercise("custom_plank")),
        )
        val exercises = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList(), catalog = trackingCatalog,
                seeds = mapOf((2L to Slot.MAIN) to List(3) { LoggedSet(25.0, 0, SetKind.WORK, seconds = 45) })),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises

        val reps = exercises.first { it.programExerciseId == 1L }
        assertEquals("", reps.goalLabel)
        assertEquals(0.0, reps.goal) // rep targets carry no weight — never "0 lb × 60"
        assertEquals("reps", reps.tracking) // enum name, lowercased — the watch picks a reps-only control

        val timed = exercises.first { it.programExerciseId == 2L }
        assertEquals("", timed.goalLabel)
        assertEquals(25.0, timed.goal) // the timed added-load rides the numeric goal
        assertEquals("timed", timed.tracking)
    }

    @Test
    fun `a weighted exercise projects tracking=weighted and carries each set's seconds`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val ex = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        assertEquals("weighted", ex.tracking)
    }

    @Test
    fun `a TIMED slot's logged seconds ride the wire`() {
        val program = Program(listOf(ProgramDay("A", "Core", "", listOf(ProgramExercise("custom_plank")))))
        val slots = listOf(ProgramSlot(2L, 0, ProgramExercise("custom_plank")))
        val logs = listOf(loggedSlot(2L, Slot.MAIN, listOf(LoggedSet(25.0, 0, SetKind.WORK, seconds = 45))))
        val timed = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, logs, catalog = trackingCatalog),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        assertEquals("timed", timed.tracking)
        assertEquals(45, timed.sets.single().seconds)
    }

    @Test
    fun `uses day B's accent index when it is the suggested day`() {
        val slots = listOf(ProgramSlot(20L, 0, ProgramExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val snapshot = WatchSnapshotBuilder.build(
            performedDay(program, "B", slots, emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!
        assertEquals(1, snapshot.day.accentIndex)
        assertEquals(0.0, snapshot.day.exercises.single().goal)
    }

    // --- the cycle the watch's outer ring draws (dial v3 §1) ---------------

    @Test
    fun `stamps every day of the program, in order, with the names the watch says`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val snapshot = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!

        assertEquals(listOf("A", "B"), snapshot.cycle.map { it.dayId })
        assertEquals(listOf("Day A — Squat", "Day B — Bench"), snapshot.cycle.map { it.title })
        val squat = snapshot.cycle.first().exercises.single()
        assertEquals("Squat", squat.name) // the colloquial name, as the bands use
        assertEquals(3, squat.setCount) // the day's target, not a logged count
    }

    @Test
    fun `the cycle is the program, not the suggested day — it doesn't move with it`() {
        val slots = listOf(ProgramSlot(20L, 0, ProgramExercise("bb_bench", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val fromB = WatchSnapshotBuilder.build(
            performedDay(program, "B", slots, emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!
        assertEquals(listOf("A", "B"), fromB.cycle.map { it.dayId })
    }

    @Test
    fun `carries a superset partner's aligned rows and name`() {
        val pe = ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown"))
        val slots = listOf(ProgramSlot(30L, 0, pe))
        val logs = listOf(
            loggedSlot(30L, Slot.MAIN, listOf(LoggedSet(60.0, 12, SetKind.WORK), LoggedSet(60.0, 11, SetKind.WORK))),
            loggedSlot(30L, Slot.SS, listOf(LoggedSet(50.0, 15, SetKind.WORK), LoggedSet(50.0, 14, SetKind.WORK))),
        )
        val program = Program(listOf(ProgramDay("A", "Arms", "", listOf(pe))))

        val ex = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, logs,
                seeds = mapOf((30L to Slot.SS) to logs.single { it.slot == Slot.SS }.sets)),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals("Rope Pushdown", ex.supersetPartnerName)
        assertEquals(ex.sets.size, ex.ssSets.size)
        assertEquals(50.0, ex.ssSets.first().weightLb)
    }

    // --- the watch gets the name a lifter says (curved-bands brief §2) ------

    @Test
    fun `an entry with a colloquial name sends it, and the phone keeps the full one`() {
        val pe = ProgramExercise("conv_dl", structure = SetStructure.RAMP_TOP_BACKOFF)
        val slots = listOf(ProgramSlot(40L, 0, pe))
        val program = Program(listOf(ProgramDay("A", "Pull", "", listOf(pe))))

        val ex = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals("Deadlift", ex.name)
        assertEquals("Conventional Deadlift", catalog.get("conv_dl").name)
    }

    @Test
    fun `an entry without one keeps its full name, custom exercises included`() {
        val pe = ProgramExercise("hack_squat", structure = SetStructure.RAMP_TOP_BACKOFF)
        val custom = ProgramExercise("custom_pullup", structure = SetStructure.RAMP_TOP_BACKOFF)
        val slots = listOf(ProgramSlot(41L, 0, pe), ProgramSlot(42L, 1, custom))
        val program = Program(listOf(ProgramDay("A", "Legs", "", listOf(pe, custom))))

        val exercises = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList(), catalog = trackingCatalog),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises

        assertEquals("Hack Squat", exercises[0].name)
        assertEquals("Pull-up", exercises[1].name)
    }

    @Test
    fun `the superset partner is shortened at the same stamp point`() {
        val pe = ProgramExercise("ez_curl", superset = SupersetPartner("oh_tri_ext"))
        val slots = listOf(ProgramSlot(43L, 0, pe))
        val program = Program(listOf(ProgramDay("A", "Arms", "", listOf(pe))))

        val ex = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals("EZ-Bar Curl", ex.name) // no short form; unchanged
        assertEquals("Overhead Ext", ex.supersetPartnerName)
    }

    @Test
    fun `stamps ssTracking from the superset partner's own entry`() {
        val pe = ProgramExercise("ez_curl", superset = SupersetPartner("custom_pullup"))
        val slots = listOf(ProgramSlot(30L, 0, pe))
        val program = Program(listOf(ProgramDay("A", "Arms", "", listOf(pe))))

        val ex = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList(), catalog = trackingCatalog),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals("weighted", ex.tracking) // main track unaffected
        assertEquals("reps", ex.ssTracking) // the partner's own tracking, not the main's
    }

    @Test
    fun `ssTracking defaults to weighted when there is no superset partner`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val ex = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        assertEquals("weighted", ex.ssTracking)
    }

    @Test
    fun `stamps each main set's rest from RestPolicy when the master toggle is on`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val logs = listOf(
            loggedSlot(10L, Slot.MAIN, listOf(
                LoggedSet(130.0, 5, SetKind.RAMP),
                LoggedSet(235.0, 5, SetKind.TOP),
                LoggedSet(210.0, 5, SetKind.BACKOFF),
            )),
        )
        val sets = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, logs),
            unit = WeightUnit.LB, revision = 1L, restSettings = RestSettings(enabled = true),
        )!!.day.exercises.single().sets
        assertEquals(90, sets[0].restAfterSeconds)  // RAMP default
        assertEquals(180, sets[1].restAfterSeconds) // TOP default
        assertEquals(120, sets[2].restAfterSeconds) // BACKOFF default
    }

    @Test
    fun `a per-category override reaches the stamped rest`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val logs = listOf(loggedSlot(10L, Slot.MAIN, listOf(LoggedSet(235.0, 5, SetKind.TOP))))
        val sets = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, logs),
            unit = WeightUnit.LB, revision = 1L, restSettings = RestSettings(enabled = true, overrides = mapOf(RestCategory.TOP to 210)),
        )!!.day.exercises.single().sets
        assertEquals(210, sets.single().restAfterSeconds)
    }

    @Test
    fun `the master toggle off zeroes every stamped rest`() {
        val slots = listOf(ProgramSlot(10L, 0, ProgramExercise("bb_back_squat", structure = SetStructure.RAMP_TOP_BACKOFF)))
        val logs = listOf(
            loggedSlot(10L, Slot.MAIN, listOf(LoggedSet(130.0, 5, SetKind.RAMP), LoggedSet(235.0, 5, SetKind.TOP))),
        )
        val sets = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, logs),
            unit = WeightUnit.LB, revision = 1L, restSettings = RestSettings(enabled = false),
        )!!.day.exercises.single().sets
        assertEquals(0, sets[0].restAfterSeconds)
        assertEquals(0, sets[1].restAfterSeconds)
    }

    @Test
    fun `a superset partner's rows are stamped 0 rest so the round has one rest`() {
        val pe = ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown"))
        val slots = listOf(ProgramSlot(30L, 0, pe))
        val logs = listOf(
            loggedSlot(30L, Slot.MAIN, listOf(LoggedSet(60.0, 12, SetKind.WORK))),
            loggedSlot(30L, Slot.SS, listOf(LoggedSet(50.0, 15, SetKind.WORK))),
        )
        val program = Program(listOf(ProgramDay("A", "Arms", "", listOf(pe))))
        val ex = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, logs,
                seeds = mapOf((30L to Slot.SS) to logs.single { it.slot == Slot.SS }.sets)),
            unit = WeightUnit.LB, revision = 1L, restSettings = RestSettings(enabled = true),
        )!!.day.exercises.single()
        assertEquals(90, ex.sets.single().restAfterSeconds) // weighted WORK on the main track
        assertEquals(0, ex.ssSets.single().restAfterSeconds) // partner carries none
    }

    @Test
    fun alternates_follow_substitution_rank_and_exclude_the_current_exercise() {
        val expected = catalog.substitutionsFor("bb_back_squat").take(WatchSnapshotBuilder.MAX_ALTERNATES)

        val alternates = WatchSnapshotBuilder.alternatesFor(
            "bb_back_squat", catalog, Equipment.entries.toSet(),
        )

        assertEquals(expected.map { it.id }, alternates.map { it.exerciseId })
        assertEquals(expected.map { it.subRank }, alternates.map { catalog.get(it.exerciseId).subRank })
        assertEquals(false, alternates.any { it.exerciseId == "bb_back_squat" })
    }

    @Test
    fun alternates_are_capped_at_the_watch_limit() {
        val candidates = catalog.substitutionsFor("bb_back_squat")
        assertEquals(true, candidates.size > WatchSnapshotBuilder.MAX_ALTERNATES)

        val alternates = WatchSnapshotBuilder.alternatesFor(
            "bb_back_squat", catalog, Equipment.entries.toSet(),
        )

        assertEquals(WatchSnapshotBuilder.MAX_ALTERNATES, alternates.size)
        assertEquals(candidates.take(WatchSnapshotBuilder.MAX_ALTERNATES).map { it.id }, alternates.map { it.exerciseId })
    }

    @Test
    fun restricted_equipment_drops_alternates_that_need_other_gear() {
        val alternates = WatchSnapshotBuilder.alternatesFor(
            "bb_back_squat", catalog, setOf(Equipment.BODYWEIGHT),
        )

        assertEquals(listOf("wall_sit"), alternates.map { it.exerciseId })
        assertEquals(true, alternates.all { alternate ->
            catalog.get(alternate.exerciseId).equipment.all { it == Equipment.BODYWEIGHT }
        })
    }

    @Test
    fun an_unknown_exercise_has_no_alternates_and_does_not_throw() {
        val pe = ProgramExercise("custom_nonexistent")
        val customProgram = Program(listOf(ProgramDay("A", "Custom", "", listOf(pe))))
        val slots = listOf(ProgramSlot(50L, 0, pe))

        val exercise = WatchSnapshotBuilder.build(
            performedDay(customProgram, "A", slots, emptyList()),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals(emptyList(), exercise.alternates)
    }

    @Test
    fun superset_partner_rows_do_not_contribute_alternates() {
        val pe = ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown"))
        val armsProgram = Program(listOf(ProgramDay("A", "Arms", "", listOf(pe))))
        val slots = listOf(ProgramSlot(30L, 0, pe))

        val exercise = WatchSnapshotBuilder.build(
            performedDay(armsProgram, "A", slots, emptyList(),
                seeds = mapOf((30L to Slot.SS) to List(3) { LoggedSet(50.0, 10, SetKind.WORK) })),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()

        assertEquals(
            WatchSnapshotBuilder.alternatesFor("ez_curl", catalog, Equipment.entries.toSet()),
            exercise.alternates,
        )
        assertEquals(false, exercise.alternates.any { alternate ->
            catalog.get(alternate.exerciseId).pattern == catalog.get("rope_pushdown").pattern
        })
    }

    @Test
    fun `returns null when there is no suggested day or it is not in the program`() {
        assertNull(WatchSnapshotBuilder.build(program, null, emptyList(), emptyList(), cfg, catalog, WeightUnit.LB, 1L))
        assertNull(WatchSnapshotBuilder.build(program, "Z", emptyList(), emptyList(), cfg, catalog, WeightUnit.LB, 1L))
    }

    @Test fun `substitute snapshot uses performed identity tracking goal and alternates without a prescription`() {
        val pe = ProgramExercise("bb_back_squat", targetSets = 2,
            prescription = Prescription(PrescribedTarget.RepsInTime(90, 30)))
        val rows = listOf(LoggedSet(75.0, 8, SetKind.WORK))
        val snapshot = WatchSnapshotBuilder.build(
            performedDay(program, "A", listOf(ProgramSlot(10L, 0, pe)), listOf(LoggedSlot(10L, Slot.MAIN, rows, exerciseId = "hack_squat"))),
            unit = WeightUnit.LB, revision = 1L,
        )!!
        val exercise = snapshot.day.exercises.single()
        val entry = catalog.get("hack_squat")
        assertEquals(entry.shortName ?: entry.name, exercise.name)
        assertEquals("hack_squat", exercise.exerciseId)
        assertEquals("weighted", exercise.tracking)
        assertFalse(exercise.prescribed)
        assertEquals(0, exercise.repWindowSeconds)
        assertNull(exercise.repAim)
        assertEquals("", exercise.goalLabel)
        assertTrue(exercise.alternates.any { it.exerciseId == "bb_back_squat" })
        assertEquals(rows.size, exercise.sets.size)
    }
    @Test fun `a dropped pair is sent as a plain lift even with stale SS logs`() {
        val pair = ProgramExercise("ez_curl", superset = SupersetPartner("rope_pushdown"))
        val slots = listOf(ProgramSlot(30L, 0, pair))
        val program = Program(listOf(ProgramDay("A", "Arms", "", listOf(pair))))
        val rows = listOf(LoggedSet(60.0, 10, SetKind.WORK))
        val logs = listOf(loggedSlot(30L, Slot.MAIN, rows), loggedSlot(30L, Slot.SS, rows))
        val exercise = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, logs, dropped = setOf(30L)),
            unit = WeightUnit.LB, revision = 1L,
        )!!.day.exercises.single()
        assertNull(exercise.supersetPartnerName)
        assertTrue(exercise.ssSets.isEmpty())
        assertEquals(1, exercise.sets.size)
        assertTrue(exercise.sets.single().restAfterSeconds > 0)
    }


    @Test fun `skipped lift is absent from the workout snapshot`() {
        val lift = ProgramExercise("ez_curl")
        val slots = listOf(ProgramSlot(30L, 0, lift), ProgramSlot(31L, 4, lift))
        val program = Program(listOf(ProgramDay("A", "Arms", "", listOf(lift, lift))))
        val snapshot = WatchSnapshotBuilder.build(
            performedDay(program, "A", slots, emptyList(), skipped = setOf(30L)),
            unit = WeightUnit.LB, revision = 1L,
        )!!
        assertEquals(listOf(31L), snapshot.day.exercises.map { it.programExerciseId })
    }
}
