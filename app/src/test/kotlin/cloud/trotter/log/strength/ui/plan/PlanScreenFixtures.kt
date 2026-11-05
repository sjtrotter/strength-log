package cloud.trotter.log.strength.ui.plan

internal fun planScreenFixture(count: Int = 1, menu: String? = null) = PlanUiState(
    name = "Prep", hasProgram = true, menuDayId = menu,
    days = (0 until count).map { index ->
        val letter = ('A' + index).toString()
        PlanDay(letter, index, "Day $letter", 0, 0, index == 0)
    },
)
