package cloud.trotter.log.strength.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgramDisplayTest {
    @Test
    fun accentIndexUsesTheLeadingLetterAndWrapsAtThePaletteSize() {
        assertEquals(0, Program.accentIndex("A"))
        assertEquals(1, Program.accentIndex("B"))
        assertEquals(2, Program.accentIndex("C"))
        assertEquals(3, Program.accentIndex("D"))
        assertEquals(4, Program.accentIndex("E"))
        assertEquals(6, Program.accentIndex("G"))
        assertEquals(0, Program.accentIndex("H"))
        assertEquals(2, Program.accentIndex("C1"))
        assertEquals(2, Program.accentIndex("C2"))
    }

    @Test
    fun accentIndexAcceptsLowercaseLetters() {
        assertEquals(0, Program.accentIndex("a"))
        assertEquals(1, Program.accentIndex("b"))
        assertEquals(2, Program.accentIndex("c1"))
        assertEquals(6, Program.accentIndex("g"))
        assertEquals(0, Program.accentIndex("h"))
    }

    @Test
    fun accentIndexFallsBackToAForEmptyAndNonLetterIds() {
        listOf("", "1", "?", "[", "é", " A").forEach { id ->
            assertEquals(0, Program.accentIndex(id), id)
        }
    }

    @Test
    fun dayToShowHonorsExistingStrengthCardioAndEmptyDays() {
        val cardio = day("C1", ProgramDayKind.CARDIO)
        val a = day("A")
        val c = day("C")
        val program = Program(listOf(cardio, a, c))

        program.days.forEach { day -> assertEquals(day, program.dayToShow(day.id)) }
    }

    @Test
    fun dayToShowPrefersTheFirstStrengthDayForNullOrRemovedSuggestions() {
        val cardio = day("C1", ProgramDayKind.CARDIO)
        val a = day("A")
        val program = Program(listOf(cardio, a, day("C")))

        assertEquals(a, program.dayToShow(null))
        assertEquals(a, program.dayToShow("B"))
        assertEquals(a, program.dayToShow(""))
    }

    @Test
    fun dayToShowFallsBackToTheFirstDayWhenThereAreNoStrengthDays() {
        val first = day("C1", ProgramDayKind.CARDIO)
        val second = day("C2", ProgramDayKind.CARDIO)
        val program = Program(listOf(first, second))

        assertEquals(first, program.dayToShow(null))
        assertEquals(first, program.dayToShow("removed"))
        assertEquals(second, program.dayToShow("C2"))
    }

    @Test
    fun dayToShowReturnsNullForAnEmptyProgram() {
        val program = Program(emptyList())
        assertNull(program.dayToShow(null))
        assertNull(program.dayToShow("A"))
    }

    private fun day(id: String, kind: ProgramDayKind = ProgramDayKind.STRENGTH) =
        ProgramDay(id, "Day $id", "", emptyList(), kind = kind)
}
