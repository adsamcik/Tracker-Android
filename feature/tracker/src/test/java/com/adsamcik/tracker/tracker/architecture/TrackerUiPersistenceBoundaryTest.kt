package com.adsamcik.tracker.tracker.architecture

import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TrackerUiPersistenceBoundaryTest {
	@Test
	fun `tracker Compose depends on its recent trips port instead of Room`() {
		val moduleDir = resolveModuleDirectory()
		val violations = File(moduleDir, "src/main/java").walkTopDown()
			.filter { it.isFile && it.extension == "kt" && "/ui/" in it.invariantSeparatorsPath }
			.flatMap(::persistenceViolations)
			.toList()

		assertTrue(violations.isEmpty(), violations.joinToString("\n"))
	}

	private fun resolveModuleDirectory(): File {
		val candidates = listOf(File("."), File("feature/tracker"))
		return candidates.firstOrNull {
			File(it, "build.gradle.kts").isFile && File(it, "src/main").isDirectory
		} ?: error(":feature:tracker module directory not found")
	}

	private fun persistenceViolations(file: File): Sequence<String> =
		file.readLines().asSequence().mapIndexedNotNull { index, line ->
			if (
				line.contains("shared.base.database") ||
				Regex("""\bAppDatabase\b|\b[A-Z][A-Za-z0-9]*Dao\b""").containsMatchIn(line)
			) {
				"${file.name}:${index + 1} -> ${line.trim()}"
			} else {
				null
			}
		}
}
