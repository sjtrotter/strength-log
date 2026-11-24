package cloud.trotter.log.strength.ui.log

import cloud.trotter.log.strength.data.db.entity.TestResultEntity
import cloud.trotter.log.strength.data.serialization.TestEventJson
import cloud.trotter.log.strength.domain.standards.TestEvents
import cloud.trotter.log.strength.domain.standards.TestRef
import java.time.LocalDate
import java.time.ZoneOffset

internal fun testResultFixture(
    ref: TestRef = TestRef("aft_hrp", 1, 120),
    value: Int = 31,
    id: Long = 1,
    completedAt: Long = LocalDate.of(2026, 11, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
): TestResultEntity {
    val event = TestEvents.get(ref.eventId).copy(version = ref.version)
    return TestResultEntity(id, completedAt, 1, "A", null, null, null, event.exerciseId,
        ref.eventId, ref.version, ref.windowSeconds, if (event.timed) null else value,
        if (event.timed) value else null, if (event.timed) "STOPPED" else "TIME_UP", TestEventJson.encode(event))
}
