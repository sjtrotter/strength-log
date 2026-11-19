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

    @Test fun optionalParsesOnSelectorStepsOnly() {
        listOf("tap", "longPress", "type", "scrollTo", "swipe", "expect").forEach { action ->
            val extra = when (action) {
                "type" -> ",\"value\":\"12\""
                "swipe" -> ",\"direction\":\"up\""
                else -> ""
            }
            val step = Step.parse(Json.parseToJsonElement("""{"$action":{"text":"Target"$extra},"optional":true}"""))
            assertEquals(true, step.optional)
        }
        listOf(
            """{"advance":"1s","optional":true}""",
            """{"back":true,"optional":true}""",
            """{"recreate":true,"optional":true}""",
            """{"checkpoint":"end","optional":true}""",
            """{"note":"note","optional":true}""",
            """{"tap":{"text":"Target"},"optional":true,"expectError":"not-visible"}""",
            """{"tap":{"text":"Target"},"optional":"yes"}""",
            """{"tap":{"text":"Target"},"optional":false}""",
            """{"tap":{"text":"Target"},"optional":"true"}""",
        ).forEach { json ->
            assertEquals("script", assertFailsWith<PlaytestError> { Step.parse(Json.parseToJsonElement(json)) }.kind)
        }
    }

    @Test fun advanceAcceptsMilliseconds() {
        listOf("500ms" to 500L, "4s880ms" to 4_880L, "1m30s" to 90_000L, "2d" to 172_800_000L).forEach { (text, millis) ->
            val step = Step.parse(Json.parseToJsonElement("""{"advance":"$text"}"""))
            assertEquals(millis, requireNotNull(step.duration).toMillis())
        }
        listOf("880ms4s", "0ms", "").forEach { text ->
            assertEquals("script", assertFailsWith<PlaytestError> {
                Step.parse(Json.parseToJsonElement("""{"advance":"$text"}"""))
            }.kind)
        }
    }

    @Test fun captureModesKeepTheirExistingDefaultsAndAcceptCompact() {
        val file = temporary.newFile("capture.json")
        listOf(null, "checkpoints", "compact").forEach { mode ->
            val capture = mode?.let { "\"capture\":\"$it\"," }.orEmpty()
            file.writeText("""{"seat":"capture",$capture"steps":[]}""")
            val script = Script.parse(file)
            assertEquals(mode == "checkpoints", script.checkpointsOnly)
            assertEquals(mode == "compact", script.compactCapture)
        }
        file.writeText("""{"seat":"capture","capture":"unknown","steps":[]}""")
        assertEquals("script", assertFailsWith<PlaytestError> { Script.parse(file) }.kind)
    }

    @Test fun enabledSelectorAcceptsBothStatesAndRejectsNonBooleanValues() {
        for (enabled in listOf(true, false)) {
            val step = Step.parse(Json.parseToJsonElement("""{"expect":{"text":"End rest","enabled":$enabled}}"""))
            assertEquals(enabled, requireNotNull(step.selector).flag("enabled"))
        }
        assertFailsWith<PlaytestError> {
            Step.parse(Json.parseToJsonElement("""{"expect":{"text":"End rest","enabled":"false"}}"""))
        }
    }

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
