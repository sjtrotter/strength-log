package io.github.sjtrotter.strengthlog.transfer.health

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure external-session formatting (#17 read path) — plain JVM, no provider. */
class ExternalSessionFormatterTest {

    private val zone = ZoneId.of("America/New_York")

    private fun workout(title: String?, endMillis: Long, pkg: String) =
        ExternalWorkout(title = title, startMillis = endMillis - 1000, endMillis = endMillis, sourcePackage = pkg)

    @Test
    fun sortsNewestFirst() {
        val rows = ExternalSessionFormatter.format(
            listOf(
                workout("Morning lift", 1_000L, "com.example.older"),
                workout("Evening lift", 9_000L, "com.example.newer"),
            ),
            zone,
        )
        assertEquals(listOf("Evening lift", "Morning lift"), rows.map { it.title })
    }

    @Test
    fun labelsSourceAndMarksExternal() {
        val rows = ExternalSessionFormatter.format(
            listOf(workout("Lift", 1_000L, "com.google.android.apps.fitness")),
            zone,
        )
        assertEquals("External · fitness", rows.single().sourceLabel)
        assertTrue(rows.single().sourceLabel.startsWith("External"))
    }

    @Test
    fun blankTitleFallsBackToGenericName() {
        val rows = ExternalSessionFormatter.format(
            listOf(workout(null, 1_000L, "com.example.app"), workout("  ", 2_000L, "com.example.app")),
            zone,
        )
        assertTrue(rows.all { it.title == "Strength session" })
    }
}
