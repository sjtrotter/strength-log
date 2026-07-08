package io.github.sjtrotter.strengthlog.transfer.csv

import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import kotlin.test.Test
import kotlin.test.assertEquals

class PatternGuesserTest {

    @Test
    fun `recognizes common keywords`() {
        assertEquals(MovementPattern.SQUAT_BILATERAL, PatternGuesser.guess("Front Squat"))
        assertEquals(MovementPattern.HINGE, PatternGuesser.guess("Romanian Deadlift"))
        assertEquals(MovementPattern.H_PUSH, PatternGuesser.guess("Incline Bench Press"))
        assertEquals(MovementPattern.V_PUSH, PatternGuesser.guess("Seated Overhead Press"))
        assertEquals(MovementPattern.H_PULL, PatternGuesser.guess("Cable Row"))
        assertEquals(MovementPattern.V_PULL, PatternGuesser.guess("Weighted Pull-Up"))
        assertEquals(MovementPattern.BICEPS, PatternGuesser.guess("Dumbbell Curl"))
        assertEquals(MovementPattern.KNEE_FLEXION, PatternGuesser.guess("Lying Leg Curl"))
        assertEquals(MovementPattern.CARDIO, PatternGuesser.guess("Outdoor Run"))
    }

    @Test
    fun `is case insensitive`() {
        assertEquals(MovementPattern.SQUAT_BILATERAL, PatternGuesser.guess("FRONT SQUAT"))
    }

    @Test
    fun `falls back to a default when nothing matches`() {
        assertEquals(MovementPattern.SQUAT_BILATERAL, PatternGuesser.guess("Gizmo Contraption 3000"))
    }
}
