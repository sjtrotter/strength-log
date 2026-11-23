package cloud.trotter.log.strength.domain.standards

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
            rest = "Time keeps running.",
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

    fun find(id: String): TestEvent? = byId[id]
    fun get(id: String): TestEvent = requireNotNull(byId[id]) { "Unknown test event: $id" }

    fun window(event: TestEvent, typed: Int?): Int? = event.windowSeconds ?: typed?.takeIf { event.custom }

    fun matches(ref: TestRef): TestEvent? = find(ref.eventId)?.takeIf { it.version == ref.version }
}
