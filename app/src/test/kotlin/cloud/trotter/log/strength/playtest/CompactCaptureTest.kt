package cloud.trotter.log.strength.playtest

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactCaptureTest {
    @Test fun listsRemovalsAndAdditionsInTheirScreenOrder() {
        assertEquals(
            "003-tap-next\n- Old heading\n- Old footer\n+ New heading\n+ New footer\n",
            formatCompactDiff("003-tap-next",
                listOf(CaptureText("Old heading"), CaptureText("Shared"), CaptureText("Old footer")),
                listOf(CaptureText("New heading"), CaptureText("Shared"), CaptureText("New footer"))),
        )
    }

    @Test fun unchangedTextProducesOnlyTheStepName() {
        val texts = listOf(CaptureText("Same"), CaptureText("Same"))
        assertEquals("004-expect\n", formatCompactDiff("004-expect", texts, texts))
    }

    @Test fun repeatedTextKeepsOccurrenceCounts() {
        val one = listOf(CaptureText("Set"))
        val two = one + one
        assertEquals("add\n+ Set\n", formatCompactDiff("add", one, two))
        assertEquals("remove\n- Set\n", formatCompactDiff("remove", two, one))
    }

    @Test fun dialogTextIsDistinctFromBackgroundTextAndEachLineHasAPrefix() {
        assertEquals(
            "dialog\n- Finish\n+ [dialog] Finish\n+ [dialog] Cancel\n",
            formatCompactDiff("dialog", listOf(CaptureText("Finish")),
                listOf(CaptureText("Finish\n\nCancel", inDialog = true))),
        )
    }

    @Test fun snapshotTextUsesScreenOrderAndDialogMarkers() {
        fun node(text: String, left: Int, top: Int, dialog: Boolean = false) =
            SemanticsShape(listOf(text), null, left, top, 40, 20, dialog, 0)
        assertEquals(
            listOf(CaptureText("Top left"), CaptureText("Top right"), CaptureText("Bottom", true)),
            listOf(node("Bottom", 0, 100, true), node("Top right", 50, 0), node("Top left", 0, 0)).captureTexts(),
        )
    }
}
