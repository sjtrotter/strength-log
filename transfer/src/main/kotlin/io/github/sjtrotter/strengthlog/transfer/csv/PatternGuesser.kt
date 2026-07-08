package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.domain.model.MovementPattern

/**
 * Best-effort keyword guess at an unmatched CSV exercise name's
 * [MovementPattern], for the import preview's editable suggestion (PLAN.md
 * A2: "unmatched names ... a fuzzy pattern suggestion the user can change").
 * Never authoritative: the guess only ever seeds a value the user reviews
 * before [CsvHistoryImporter.commit] creates a custom exercise with it.
 *
 * Rules are checked in order (most specific first, e.g. "leg curl" before the
 * generic "curl") and the first match wins. A name matching nothing falls
 * back to the catalog's first pattern rather than throwing — a wrong guess is
 * harmless (it's just a starting point the user edits), while silently
 * skipping the row is not (PLAN.md forbids silent guessing at the *matching*
 * step, not at picking a default for the *suggestion* itself).
 */
object PatternGuesser {

    private val RULES: List<Pair<Regex, MovementPattern>> = listOf(
        rule("squat", MovementPattern.SQUAT_BILATERAL),
        rule("lunge|split squat|step.?up|bulgarian", MovementPattern.SINGLE_LEG),
        rule("deadlift|rdl|hinge|good.?morning|hip thrust", MovementPattern.HINGE),
        rule("leg curl|hamstring curl|nordic", MovementPattern.KNEE_FLEXION),
        rule("leg extension", MovementPattern.KNEE_EXTENSION),
        rule("overhead press|shoulder press|military press|\\bohp\\b", MovementPattern.V_PUSH),
        rule("bench|chest press|push.?up|dip", MovementPattern.H_PUSH),
        rule("pull.?up|chin.?up|lat pulldown", MovementPattern.V_PULL),
        rule("row|pulldown", MovementPattern.H_PULL),
        rule("lateral raise|side raise", MovementPattern.SIDE_DELT),
        rule("rear delt|face pull|reverse fly", MovementPattern.REAR_DELT),
        rule("tricep|pushdown|skull.?crusher|jm press", MovementPattern.TRICEPS),
        rule("curl", MovementPattern.BICEPS),
        rule("seated calf", MovementPattern.CALF_SOLEUS),
        rule("calf", MovementPattern.CALF_GASTROC),
        rule("plank|dead.?bug", MovementPattern.CORE_ANTI_EXT),
        rule("pallof|anti.?rotation|woodchop", MovementPattern.CORE_ANTI_ROT),
        rule("crunch|sit.?up|core", MovementPattern.CORE_FLEX),
        rule("run|bike|erg|elliptical|cardio|row(?:er|ing) machine", MovementPattern.CARDIO),
    )

    /** Default when no keyword rule matches. */
    private val FALLBACK: MovementPattern = MovementPattern.SQUAT_BILATERAL

    fun guess(exerciseName: String): MovementPattern =
        RULES.firstOrNull { (regex, _) -> regex.containsMatchIn(exerciseName) }?.second ?: FALLBACK

    private fun rule(pattern: String, movementPattern: MovementPattern) =
        Regex(pattern, RegexOption.IGNORE_CASE) to movementPattern
}
