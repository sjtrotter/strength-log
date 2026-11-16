package cloud.trotter.log.strength.ui.setup

import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.units.SecondsStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Bodyweight conversion and rest preferences delegate to their domain rules. */
class SetupStateBuilderTest {

    // --- unit-toggle display conversion delegates to :domain WeightUnit ------

    @Test
    fun bodyweightDisplay_delegates_to_WeightUnit_conversion() {
        val cfg = LifterConfig(bodyweightLb = 220)
        assertEquals(220.0, SetupStateBuilder.bodyweightDisplay(cfg, WeightUnit.LB))
        assertEquals(99.8, SetupStateBuilder.bodyweightDisplay(cfg, WeightUnit.KG))
    }

    @Test
    fun bodyweightLb_round_trips_through_WeightUnit_conversion() {
        assertEquals(220.0, SetupStateBuilder.bodyweightLb(220.0, WeightUnit.LB))
        val kgDisplay = WeightUnit.KG.fromLb(220.0)
        assertEquals(220.0, SetupStateBuilder.bodyweightLb(kgDisplay, WeightUnit.KG))
    }

    // --- buildUiState wires everything together -------------------------------

    @Test
    fun buildUiState_assembles_app_preferences() {
        val state = SetupStateBuilder.buildUiState(WeightUnit.LB)

        assertEquals(WeightUnit.LB, state.unit)
    }

    // --- rest-timer editor state mapping (watch W2c) --------------------------

    @Test
    fun restCategoryRows_shows_RestPolicy_default_when_no_override_exists() {
        val rows = SetupStateBuilder.restCategoryRows(RestSettings(overrides = emptyMap()))

        assertEquals(RestCategory.entries.size, rows.size)
        RestCategory.entries.forEachIndexed { index, category ->
            assertEquals(category, rows[index].category)
            assertEquals(RestPolicy.defaultSeconds(category), rows[index].seconds)
        }
    }

    @Test
    fun restCategoryRows_shows_the_override_when_one_is_set() {
        val rows = SetupStateBuilder.restCategoryRows(RestSettings(overrides = mapOf(RestCategory.TOP to 210)))

        assertEquals(210, rows.first { it.category == RestCategory.TOP }.seconds)
        // Every other bucket is untouched and still reads its RestPolicy default.
        assertEquals(RestPolicy.defaultSeconds(RestCategory.RAMP), rows.first { it.category == RestCategory.RAMP }.seconds)
    }

    @Test
    fun restCategoryRows_shows_a_zero_override_verbatim() {
        val rows = SetupStateBuilder.restCategoryRows(RestSettings(overrides = mapOf(RestCategory.LIGHT to 0)))

        assertEquals(0, rows.first { it.category == RestCategory.LIGHT }.seconds)
    }

    @Test
    fun restTimerLabel_renders_zero_as_OFF_and_delegates_otherwise_to_SecondsStepper() {
        assertEquals("OFF", SetupStateBuilder.restTimerLabel(0))
        assertEquals(SecondsStepper.format(90), SetupStateBuilder.restTimerLabel(90))
        assertEquals(SecondsStepper.format(180), SetupStateBuilder.restTimerLabel(180))
    }

    @Test
    fun buildUiState_wires_restSettings_into_restTimerEnabled_and_restCategories() {
        val restSettings = RestSettings(enabled = false, overrides = mapOf(RestCategory.WORK to 45))

        val state = SetupStateBuilder.buildUiState(WeightUnit.LB, restSettings)

        assertEquals(false, state.restTimerEnabled)
        assertEquals(45, state.restCategories.first { it.category == RestCategory.WORK }.seconds)
    }

    @Test
    fun buildUiState_defaults_restSettings_to_enabled_with_no_overrides() {
        val state = SetupStateBuilder.buildUiState(WeightUnit.LB)

        assertEquals(true, state.restTimerEnabled)
        RestCategory.entries.forEach { category ->
            assertEquals(RestPolicy.defaultSeconds(category), state.restCategories.first { it.category == category }.seconds)
        }
    }
}
