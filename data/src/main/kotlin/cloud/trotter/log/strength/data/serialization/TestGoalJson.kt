package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.TestEventGoal
import cloud.trotter.log.strength.domain.model.TestGoal
import cloud.trotter.log.strength.domain.standards.TestRef
import kotlinx.serialization.Serializable

@Serializable
internal data class TestEventGoalDto(val eventId: String, val version: Int, val windowSeconds: Int? = null, val required: Int)

@Serializable
internal data class TestGoalDto(val v: Int = 1, val events: List<TestEventGoalDto>, val byEpochDay: Long? = null)

object TestGoalJson {
    fun encode(goal: TestGoal): String = SetJson.encode(TestGoalDto.serializer(),
        TestGoalDto(events = goal.events.map {
            TestEventGoalDto(it.ref.eventId, it.ref.version, it.ref.windowSeconds, it.required)
        }, byEpochDay = goal.byEpochDay))

    /** Null only for an unreadable goal; the stored JSON is kept as is. */
    fun decode(json: String?): TestGoal? = json?.let { runCatching { parse(it) }.getOrNull() }

    fun validate(json: String) { parse(json) }

    private fun parse(json: String): TestGoal {
        val dto = SetJson.decode(TestGoalDto.serializer(), json)
        require(dto.v == 1) { "unknown test goal version ${dto.v}" }
        return TestGoal(dto.events.map {
            TestEventGoal(TestRef(it.eventId, it.version, it.windowSeconds), it.required)
        }, dto.byEpochDay)
    }
}
