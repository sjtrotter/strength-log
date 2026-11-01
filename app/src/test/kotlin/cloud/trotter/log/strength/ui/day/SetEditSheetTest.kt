package cloud.trotter.log.strength.ui.day

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SetEditSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun validEditsWriteImmediatelyAndDoneOnlyDismisses() {
        val writes = mutableListOf<Double>()
        var dismissed = 0
        val card = workoutDayFixture().exercises[1].copy(equipment = listOf(Equipment.BARBELL))
        var row by mutableStateOf(card.rows[1])
        rule.setContent { AppTheme {
            SetEditSheet(card, row, WeightUnit.LB, 3, workoutActions().copy(onWeightChange = { id, slot, index, value ->
                assertEquals(2L, id)
                assertEquals(Slot.MAIN, slot)
                assertEquals(1, index)
                writes += value
                row = row.copy(weightDisplay = value)
            }), onDismiss = { dismissed++ })
        } }
        rule.onNodeWithText("25 a side on the bar. Only this set changes.").assertExists()
        rule.onNodeWithText("Weight, lb").performTextReplacement("100")
        rule.runOnIdle { assertEquals(listOf(100.0), writes) }
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, dismissed)
        assertEquals(listOf(100.0), writes)
    }

    @Test fun typingDigitsKeepsTheFocusedTextAndStepperUpdatesIt() {
        val writes = mutableListOf<Double>()
        val card = workoutDayFixture().exercises[1]
        var row by mutableStateOf(card.rows[1])
        rule.setContent {
            AppTheme {
                SetEditSheet(
                    card = card,
                    row = row,
                    unit = WeightUnit.LB,
                    dayIndex = 3,
                    actions = workoutActions().copy(onWeightChange = { _, _, _, value ->
                        writes += value
                        row = row.copy(weightDisplay = value)
                    }),
                    onDismiss = {},
                )
            }
        }
        val field = rule.onNodeWithText("Weight, lb")
        field.performClick().performTextClearance()
        for (digit in listOf("1", "3", "5")) {
            field.performTextInput(digit)
            rule.waitForIdle()
        }
        field.assertTextContains("135")
        rule.runOnIdle { assertEquals(listOf(2.5, 12.5, 135.0), writes) }
        rule.onNodeWithContentDescription("Weight, 5 more").performClick()
        field.assertTextContains("140")
        rule.runOnIdle { assertEquals(140.0, writes.last(), 0.0) }
    }

    @Test fun repsOnlyHasNoWeightFieldAndRemoveUsesRoundIdentity() {
        val card = workoutDayFixture().exercises[1].copy(tracking = TrackingType.REPS, isSuperset = true)
        var removed: Triple<Long, Int, Boolean>? = null
        rule.setContent { AppTheme {
            SetEditSheet(card, card.rows[1], WeightUnit.LB, 3, workoutActions().copy(onRemoveSet = { id, index, paired ->
                removed = Triple(id, index, paired)
            }), onDismiss = {})
        } }
        rule.onNodeWithText("Weight, lb").assertDoesNotExist()
        rule.onNodeWithText("Reps").assertExists()
        rule.onNodeWithContentDescription("Set menu").performClick()
        rule.onNodeWithText("Remove set").performClick()
        assertEquals(Triple(2L, 1, true), removed)
    }

    @Test fun doneTrackCorrectionsUseTheirOwnSlots() {
        val card = workoutDayFixture().exercises[1].copy(
            tracking = TrackingType.TIMED, timedShowsWeight = false, isSuperset = true,
            partnerTitle = "Pull-up", partnerTracking = TrackingType.REPS,
        )
        val row = card.rows[1].copy(done = true, seconds = 45, partner = PartnerRowState(0.0, 6, done = true))
        val changes = mutableListOf<Pair<String, Int>>()
        rule.setContent { AppTheme {
            SetEditSheet(card, row, WeightUnit.LB, 3, workoutActions().copy(
                onSecondsChange = { id, slot, index, seconds ->
                    assertEquals(card.programExerciseId, id)
                    assertEquals(row.index, index)
                    changes += slot to seconds
                },
                onRepsChange = { id, slot, index, reps ->
                    assertEquals(card.programExerciseId, id)
                    assertEquals(row.index, index)
                    changes += slot to reps
                },
            ), onDismiss = {})
        } }
        rule.onNodeWithContentDescription("Seconds, 5 more").performClick()
        rule.onNodeWithContentDescription("Pull-up, Reps, one more").performClick()
        assertEquals(listOf(Slot.MAIN to 50, Slot.SS to 7), changes)
    }

    @Test fun timedFieldsOnlyOfferWeightWhenThePrescriptionCarriesIt() {
        val card = workoutDayFixture().exercises[1].copy(tracking = TrackingType.TIMED, timedShowsWeight = false)
        rule.setContent { AppTheme { SetEditSheet(card, card.rows[1].copy(seconds = 45), WeightUnit.LB, 3, workoutActions(), {}) } }
        rule.onNodeWithText("Seconds").assertExists()
        rule.onNodeWithText("Weight, lb").assertDoesNotExist()
        rule.onNodeWithText("Reps").assertDoesNotExist()
    }
}
