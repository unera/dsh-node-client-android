package ru.uvw.dshnc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every screen of the app is declared in the manifest.
 *
 * An `Activity` that is not declared is not a compile error: the tap on its menu item opens
 * nothing, the window flickers and the app lands back where it was — which is exactly how the
 * "Наименование" screen failed on a phone while every other test was green. The check is cheap,
 * and it is about a file no Kotlin compiler looks at.
 *
 * The list of screens comes from the sources instead of a hand-kept list here: a new screen is
 * written as a `.kt` file first, and a list in the test would be a second place to forget it.
 */
class ManifestActivitiesTest {
    @Test
    fun `every activity class is declared in the manifest`() {
        val manifest = locate("src/main/AndroidManifest.xml")
        val text = manifest.readText()
        val screens = sources()
        // a broken scan would pass silently, which is worse than no test at all
        assertTrue("не нашлось ни одной активности в исходниках", screens.isNotEmpty())
        val undeclared = screens.filter { name -> !text.contains(".$name\"") && !text.contains(".$name ") }
        assertTrue("активности не объявлены в AndroidManifest.xml: $undeclared", undeclared.isEmpty())
    }

    /** Every top-level `class …Activity` written in the app's sources. */
    private fun sources (): List<String> {
        val dir = locate("src/main/kotlin/ru/uvw/dshnc")
        val declared = Regex("^class ([A-Za-z0-9_]*Activity)\\b", RegexOption.MULTILINE)
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .flatMap { file -> declared.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .distinct()
            .sorted()
    }

    /**
     * The path from the module directory or from the repository root.
     *
     * Gradle runs a unit test in the module directory; the fallback keeps the test working when
     * the runner starts it from the root instead.
     */
    private fun locate (path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        val nested = File("app/$path")
        assertTrue("не нашёлся путь $path (ни ./$path, ни ./app/$path)", nested.exists())
        return nested
    }
}
