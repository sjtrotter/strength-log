package cloud.trotter.log.strength.playtest

import androidx.compose.ui.test.junit4.ComposeTestRule
import cloud.trotter.log.strength.audit.dumpSemantics
import java.io.File

internal class ReplayCaptures(
    private val compose: ComposeTestRule,
    private val artifacts: RunArtifacts,
    private val script: Script,
) {
    private var previousTexts = emptyList<CaptureText>()

    fun launch(deadline: StepDeadline) {
        deadline.call { capture("000-launch", png = true) }
        if (script.compactCapture) previousTexts = deadline.call { snapshotTexts() }
    }

    fun step(index: Int, step: Step, deadline: StepDeadline) {
        val name = "%03d-%s".format(index, step.slug)
        if (script.compactCapture) {
            val texts = deadline.call { snapshotTexts() }
            if (step.action == "checkpoint" || index == script.steps.lastIndex) {
                deadline.call { capture(name, png = true) }
            } else if (index >= artifacts.captureFrom) {
                deadline.call {
                    File(artifacts.directory, "steps/$name.txt").writeText(formatCompactDiff(name, previousTexts, texts))
                }
            }
            previousTexts = texts
        } else if (index >= artifacts.captureFrom || step.action == "checkpoint") {
            deadline.call { capture(name, png = !script.checkpointsOnly || step.action == "checkpoint") }
        }
    }

    fun failure(name: String, error: Throwable) {
        val deadline = StepDeadline(1_000)
        try {
            deadline.run(compose) { capture(name, png = true) }
        } catch (captureError: Throwable) {
            error.addSuppressed(captureError)
            try {
                deadline.run(compose) { capture(name, png = false) }
            } catch (dumpError: Throwable) {
                error.addSuppressed(dumpError)
            }
        }
    }

    private fun snapshotTexts(): List<CaptureText> = compose.semanticsSnapshot(compose.density.density).captureTexts()

    private fun capture(name: String, png: Boolean) {
        val file = File(artifacts.directory, "steps/$name.png")
        if (png) compose.writePlaytestShot(file, compose.density.density) else {
            check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
            File(file.parentFile, "$name.json").writeText(compose.dumpSemantics(compose.density.density))
        }
    }
}
