package cloud.trotter.log.strength.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Source-level guardrail for the small bespoke inventory in the migration brief. */
class MaterialComponentConventionTest {
    @Test
    fun `new conventional components use Material 3 or explain the missing capability`() {
        val sourceRoot = sequenceOf(
            File(System.getProperty("user.dir"), "app/src/main"),
            File(System.getProperty("user.dir"), "src/main"),
        ).firstOrNull(File::isDirectory)
        assertTrue(sourceRoot != null, "Could not locate app/src/main")

        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> declarations(file).asSequence() }
            .filter { declaration -> declaration.looksHandRolled() }
            .filterNot { declaration -> declaration.name in standingExceptions }
            .filterNot { declaration -> declaration.hasMaterialGapKDoc() }
            .map { declaration -> "${declaration.file.relativeTo(sourceRoot)}: ${declaration.name}" }
            .toList()

        assertTrue(
            violations.isEmpty(),
            "Hand-rolled conventional UI must use M3 or carry KDoc explaining what M3 lacks:\n${violations.joinToString("\n")}",
        )
    }

    private fun declarations(file: File): List<Declaration> {
        val source = file.readText()
        return function.findAll(source).mapNotNull { match ->
            val bodyStart = source.indexOf('{', match.range.last + 1)
            if (bodyStart < 0) return@mapNotNull null
            val bodyEnd = matchingBrace(source, bodyStart) ?: return@mapNotNull null
            Declaration(
                file = file,
                name = match.groupValues[1],
                kdoc = attachedKDoc(source, match.range.first),
                body = source.substring(bodyStart, bodyEnd + 1),
            )
        }.toList()
    }

    private fun attachedKDoc(source: String, declarationStart: Int): String {
        val prefix = source.substring(maxOf(0, declarationStart - 1_200), declarationStart)
        val candidate = kdoc.findAll(prefix).lastOrNull() ?: return ""
        val between = prefix.substring(candidate.range.last + 1)
        return candidate.value.takeIf { annotationsAndWhitespace.matches(between) }.orEmpty()
    }

    private fun matchingBrace(source: String, opening: Int): Int? {
        var depth = 0
        for (index in opening until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun Declaration.looksHandRolled(): Boolean {
        if (!componentName.containsMatchIn(name)) return false
        val box = "Box(" in body || "Box (" in body
        val background = ".background(" in body
        val border = ".border(" in body
        val interaction = interactionPattern.containsMatchIn(body)
        val oneDpRule = background && thinRulePattern.containsMatchIn(body)
        return box && ((background && border && interaction) || oneDpRule)
    }

    private fun Declaration.hasMaterialGapKDoc(): Boolean =
        materialPattern.containsMatchIn(kdoc) && gapPattern.containsMatchIn(kdoc)

    private data class Declaration(val file: File, val name: String, val kdoc: String, val body: String)

    private companion object {
        val function = Regex("""fun\s+(?:Modifier\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val kdoc = Regex("""/\*\*[\s\S]*?\*/""")
        val annotationsAndWhitespace =
            Regex("""(?:\s|@[A-Za-z0-9_.]+(?:\([^\n]*\))?|private|internal|protected|public)*""")
        val componentName = Regex("""(?:Button|Card|Row|Toggle|Stepper|Divider|Hairline|Rule)$""")
        val interactionPattern =
            Regex("""\.(?:clickable|toggleable|selectable|pressable|pressableSelectable|pressableToggleable)\s*\(""")
        val thinRulePattern = Regex("""\.(?:height|width)\s*\(\s*1\.dp\s*\)""")
        val materialPattern = Regex("""\b(?:M3|Material 3)\b""", RegexOption.IGNORE_CASE)
        val gapPattern = Regex("""\b(?:lacks?|does not|cannot|no)\b""", RegexOption.IGNORE_CASE)

        // Named in the Phase 10 brief. Wear/dial code is excluded by sourceRoot.
        val standingExceptions = setOf(
            "SelectionCard",
            "SetRow",
            "RemoveButton",
            "EquipmentFilterRow",
            "SheetButton",
        )
    }
}
