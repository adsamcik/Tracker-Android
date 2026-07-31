package com.adsamcik.tracker.statistics.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keeps statistics dependent on feature contracts rather than sibling feature implementations.
 */
class StatisticsFeatureBoundaryTest {
    private val moduleDir = resolveModuleDirectory()
    private val mainDir = File(moduleDir, "src/main")

    @Test
    fun `statistics declares feature APIs without sibling implementations`() {
        val buildText = File(moduleDir, "build.gradle.kts").readText()

        assertTrue(buildText.contains("""project(":feature:map:api")"""))
        assertTrue(buildText.contains("""project(":feature:statistics:api")"""))
        assertFalse(buildText.contains("""project(":feature:map")"""))
        assertFalse(buildText.contains("""project(":feature:import-export")"""))
        assertFalse(buildText.contains("libs.maplibre."))
    }

    @Test
    fun `statistics uses route preview and GPX export contracts`() {
        val sourceFiles = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val source = sourceFiles.joinToString("\n") { it.readText() }

        assertTrue(source.contains("RoutePreviewRenderer"))
        assertTrue(source.contains("RoutePoint"))
        assertTrue(source.contains("TripGpxExporter"))
        assertFalse(source.contains("com.adsamcik.tracker.impexp."))

        val forbiddenImports = sourceFiles.flatMap { file ->
            file.readLines()
                .mapIndexedNotNull { index, line ->
                    val import = line.trim()
                    if (
                        import.startsWith("import com.adsamcik.tracker.map.") ||
                        import.startsWith("import org.maplibre.")
                    ) {
                        "${file.relativeTo(moduleDir)}:${index + 1}: $import"
                    } else {
                        null
                    }
                }
        }
        assertTrue(
            forbiddenImports.isEmpty(),
            "Statistics must import only map API contracts:\n${forbiddenImports.joinToString("\n")}",
        )
    }

    @Test
    fun `map API contains contracts rather than geometry implementations`() {
        val featureDirectory = requireNotNull(moduleDir.canonicalFile.parentFile)
        val mapApiMain = File(featureDirectory, "map/api/src/main")
        check(mapApiMain.isDirectory) {
            "Expected :feature:map:api production sources at ${mapApiMain.absolutePath}"
        }
        val source = mapApiMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertTrue(source.contains("RoutePreviewRenderer"))
        assertTrue(source.contains("RoutePoint"))
        assertFalse(source.contains("PolylineOptimizer"))
    }

    private fun resolveModuleDirectory(): File {
        val candidates = listOf(File("."), File("feature/statistics"))
        return candidates.firstOrNull { candidate ->
            File(candidate, "build.gradle.kts").isFile &&
                File(candidate, "src/main").isDirectory
        } ?: error(
            ":feature:statistics module directory not found; checked: " +
                candidates.joinToString { it.absolutePath },
        )
    }
}
