package cloud.trotter.log.strength.playtest

import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertFailsWith

class ScriptAssertionsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun expectedVisibilityFailureAllowsTheNextStep() {
        val step = Step.parse(Json.parseToJsonElement("""{"tap":{"text":"Target"},"expectError":"not-visible"}"""))
        step.assertExpectedError { throw PlaytestError("not-visible", "Use scrollTo first") }
        val missing = assertFailsWith<PlaytestError> { step.assertExpectedError {} }
        assertEquals("script", missing.kind)
        val unexpected = PlaytestError("app-crash", "App failed")
        assertSame(unexpected, assertFailsWith<PlaytestError> { step.assertExpectedError { throw unexpected } })
    }

    @Test fun expectedErrorDoesNotChangeExistingSteps() {
        val step = Step.parse(Json.parseToJsonElement("""{"tap":{"text":"Target"}}"""))
        val error = PlaytestError("not-visible", "Use scrollTo first")
        assertSame(error, assertFailsWith<PlaytestError> { step.assertExpectedError { throw error } })
        listOf(
            """{"expect":{"text":"Target"},"expectError":"not-visible"}""",
            """{"tap":{"text":"Target"},"expectError":"crash"}""",
        ).forEach { json -> assertFailsWith<PlaytestError> { Step.parse(Json.parseToJsonElement(json)) } }
    }

    @Test fun summaryKeepsUtcAndReportsTheScriptsLocalDateAndZone() {
        val file = temporary.newFile("script.json").apply {
            writeText("""{"seat":"local-clock","start":{"date":"2026-11-09","time":"23:39","zone":"America/Chicago"},"steps":[]}""")
        }
        val time = PlaytestTime().apply { set(Instant.parse("2026-11-10T05:39:00Z"), ZoneId.of("America/Chicago")) }
        val artifacts = RunArtifacts(temporary.newFolder("artifacts"), 0).apply { script = Script.parse(file) }
        artifacts.writeSummary(time)
        val summary = Json.parseToJsonElement(File(artifacts.directory, "summary.json").readText()).jsonObject
        assertEquals(JsonPrimitive("2026-11-10T05:39:00Z"), summary["clock_at_exit"])
        assertEquals(JsonPrimitive("2026-11-09T23:39"), summary["clock_at_exit_local"])
        assertEquals(JsonPrimitive("America/Chicago"), summary["clock_zone"])
    }
}
