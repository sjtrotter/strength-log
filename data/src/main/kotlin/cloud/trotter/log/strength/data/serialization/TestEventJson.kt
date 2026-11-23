package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.standards.TestEvent
import cloud.trotter.log.strength.domain.standards.TestSetType
import kotlinx.serialization.Serializable

@Serializable
internal data class TestEventDto(
    val id: String,
    val version: Int,
    val name: String,
    val exerciseId: String,
    val setType: String,
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
    fun toDomain(): TestEvent = TestEvent(id, version, name, exerciseId, TestSetType.valueOf(setType),
        windowSeconds, cadenceSeconds, ceilingSeconds, repCap, form, faults, rest, scoring, sourceUrl, sourceDate, custom)
}

/** The rules a result was scored under, stored verbatim with it (#646). */
object TestEventJson {
    fun encode(event: TestEvent): String = SetJson.encode(TestEventDto.serializer(),
        TestEventDto(event.id, event.version, event.name, event.exerciseId, event.setType.name, event.windowSeconds,
            event.cadenceSeconds, event.ceilingSeconds, event.repCap, event.form, event.faults, event.rest,
            event.scoring, event.sourceUrl, event.sourceDate, event.custom))

    fun decode(json: String?): TestEvent? = json?.let {
        runCatching { SetJson.decode(TestEventDto.serializer(), it).toDomain() }.getOrNull()
    }
}
