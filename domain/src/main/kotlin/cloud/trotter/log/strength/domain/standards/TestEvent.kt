package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.library.ExerciseLibrary

/** Stored by name; new constants go at the end. */
enum class TestSetType { REPS_IN_WINDOW, REPS_CONTINUOUS, REPS_CADENCE, MAX_HOLD }

/** Stored by name; new constants go at the end. */
enum class TestStop { TIME_UP, STOPPED, LIMIT }

/**
 * [version] rises whenever a rule changes, so a result keeps the rules it was scored under and never compares
 * with a later edition. A null [windowSeconds] with [custom] means the user types the window.
 * Rule text is shown and stored verbatim.
 */
data class TestEvent(
    val id: String,
    val version: Int,
    val name: String,
    val exerciseId: String,
    val setType: TestSetType,
    val windowSeconds: Int? = null,
    val cadenceSeconds: Int? = null,
    val ceilingSeconds: Int? = null,
    val repCap: Int? = null,
    val form: String,
    val faults: String,
    val rest: String,
    val scoring: String,
    val sourceUrl: String,
    val sourceDate: String,
    val custom: Boolean = false,
) {
    init {
        require(id.length in 1..40 && version >= 1 && name.isNotBlank() && exerciseId.isNotBlank())
        require(sourceUrl.startsWith("https://") && Regex("""\d{4}-\d{2}-\d{2}""").matches(sourceDate))
        when (setType) {
            TestSetType.REPS_IN_WINDOW -> require((windowSeconds != null) != custom && cadenceSeconds == null && ceilingSeconds == null)
            TestSetType.REPS_CONTINUOUS -> require(windowSeconds == null && cadenceSeconds == null && ceilingSeconds == null)
            TestSetType.REPS_CADENCE -> require(cadenceSeconds != null && windowSeconds == null && ceilingSeconds == null)
            TestSetType.MAX_HOLD -> require(windowSeconds == null && cadenceSeconds == null && repCap == null)
        }
        windowSeconds?.let { require(it in 10..3600) }
        ceilingSeconds?.let { require(it in 10..3600) }
    }

    val timed: Boolean get() = setType == TestSetType.MAX_HOLD
}

/** Results compare only within one identity: event, edition and window (null for a hold). */
data class TestRef(val eventId: String, val version: Int, val windowSeconds: Int? = null) {
    init {
        require(eventId.length in 1..40 && version >= 1)
        windowSeconds?.let { require(it in 10..3600) }
    }
}

object TestEvents {
    val all: List<TestEvent> = listOf(
        TestEvent(
            id = "aft_hrp", version = 1, name = "AFT hand-release push-up", exerciseId = "hand_release_pushup",
            setType = TestSetType.REPS_IN_WINDOW, windowSeconds = 120,
            form = "From the front leaning rest, lower until the chest touches the ground, lift both hands clear of the ground and extend the arms out to the sides, return the hands and push up to full arm extension. The body stays in a straight line from head to ankles.",
            faults = "A rep without the hands lifted and arms extended, a rep without full elbow extension at the top, or a body that bends or sags does not count.",
            rest = "The front leaning rest is the only authorized rest position; time keeps running.",
            scoring = "AFT Scoring Scales effective 2025-06-01: points from correct reps in 2:00 by age, and by sex outside the 21 combat MOSs; those MOSs use a sex-neutral, age-normed standard from 2026-01-01 for active duty; point table not reproduced.",
            sourceUrl = "https://www.army.mil/aft/", sourceDate = "2025-06-01",
        ),
        TestEvent(
            id = "aft_plank", version = 1, name = "AFT plank", exerciseId = "plank",
            setType = TestSetType.MAX_HOLD, ceilingSeconds = 220,
            form = "Forearms flat, elbows under the shoulders, hands as fists or palms no more than a fist apart, fingers not interlocked, feet up to a boot width apart, body in a straight line from head to ankles.",
            faults = "The event ends when any body part other than the feet, forearms or hands touches the ground, a hand or foot lifts, or the straight line breaks. One verbal warning for a position fault; an uncorrected fault ends the attempt. Shaking is allowed.",
            rest = "None; the clock runs until a fault or the soldier stops.",
            scoring = "AFT Scoring Scales effective 2025-06-01: 3:40 is the 100-point time for ages 17 to 21; other ages and the 60-point times are not reproduced.",
            sourceUrl = "https://www.army.mil/aft/", sourceDate = "2025-06-01",
        ),
        TestEvent(
            id = "custom_pushup_window", version = 1, name = "Timed push-ups", exerciseId = "pushup",
            setType = TestSetType.REPS_IN_WINDOW, custom = true,
            form = "Your own push-up standard, counted the way your test counts it.",
            faults = "Your own.", rest = "Your own.",
            scoring = "None: a typed required count.",
            sourceUrl = "https://github.com/sjtrotter/strength.log", sourceDate = "2026-11-23",
        ),
        TestEvent(
            id = "custom_situp_window", version = 1, name = "Timed sit-ups", exerciseId = "situp",
            setType = TestSetType.REPS_IN_WINDOW, custom = true,
            form = "Your own sit-up standard, counted the way your test counts it.",
            faults = "Your own.", rest = "Your own.",
            scoring = "None: a typed required count.",
            sourceUrl = "https://github.com/sjtrotter/strength.log", sourceDate = "2026-11-23",
        ),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): TestEvent? {
        byId[id]?.let { return it }
        val exerciseId = id.substringBefore(':', "")
        val format = TestFormat.entries.firstOrNull { it.name == id.substringAfter(':', "") } ?: return null
        val event = derived(exerciseId, format) ?: return null
        return event.takeIf { it.id == id }
    }

    fun derivedId(exerciseId: String, format: TestFormat): String = when {
        format == TestFormat.REPS_IN_WINDOW && exerciseId == "pushup" -> "custom_pushup_window"
        format == TestFormat.REPS_IN_WINDOW && exerciseId == "situp" -> "custom_situp_window"
        else -> "$exerciseId:${format.name}"
    }

    fun derived(exerciseId: String, format: TestFormat): TestEvent? {
        val entry = ExerciseLibrary.find(exerciseId) ?: return null
        if (format !in TestFormat.eligible(entry)) return null
        val setType = format.setType ?: return null
        val id = derivedId(exerciseId, format)
        byId[id]?.let { return it }
        val noun = when (format) {
            TestFormat.REPS_IN_WINDOW -> "in a window"
            TestFormat.REPS_CONTINUOUS -> "max unbroken"
            TestFormat.MAX_HOLD -> "max hold"
            TestFormat.HEAVIEST_FOR_REPS -> return null
        }
        val form = when (format) {
            TestFormat.REPS_IN_WINDOW -> "Your test's count in its window, under your test's own standard."
            TestFormat.REPS_CONTINUOUS -> "Reps without rest until you stop, under your test's own standard."
            TestFormat.MAX_HOLD -> if (entry.pressureCaution) "Held to 2:00 at most here. Keep breathing."
                else "Held until form breaks or you stop."
            TestFormat.HEAVIEST_FOR_REPS -> return null
        }
        return TestEvent(id, 1, "${entry.name}, $noun", exerciseId, setType,
            ceilingSeconds = entry.holdCeilingSeconds.takeIf { entry.pressureCaution },
            form = form, faults = "Your own.", rest = "Your own.", scoring = "None: a typed required number.",
            sourceUrl = "https://github.com/sjtrotter/strength.log", sourceDate = "2026-11-27",
            custom = format == TestFormat.REPS_IN_WINDOW)
    }

    fun editions(exerciseId: String, format: TestFormat): List<TestEvent> =
        all.filter { !it.custom && it.exerciseId == exerciseId && it.setType == format.setType }

    fun requiredLimit(event: TestEvent): Int = ExerciseLibrary.find(event.exerciseId)?.let { entry ->
        entry.holdCeilingSeconds?.takeIf { entry.pressureCaution }
    } ?: 3600

    fun get(id: String): TestEvent = requireNotNull(find(id)) { "Unknown test event: $id" }

    fun window(event: TestEvent, typed: Int?): Int? = window(event.windowSeconds, event.custom, typed)

    fun window(windowSeconds: Int?, custom: Boolean, typed: Int?): Int? = windowSeconds ?: typed?.takeIf { custom }

    fun matches(ref: TestRef): TestEvent? = find(ref.eventId)?.takeIf { it.version == ref.version }
}
