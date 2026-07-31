package com.adsamcik.tracker.shared.common

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Architecture fitness check for the :core:common foundation module.
 *
 * :core:common must remain a leaf of the dependency graph: it may depend only on
 * external libraries and :core:diagnostics. It must NOT depend on the database,
 * Room, the Room @Entity data models, or other base-resident packages — otherwise
 * the god-module coupling that this module was extracted to break would be
 * reintroduced.
 */
class CoreCommonBoundaryTest {

	private val forbiddenImports = listOf(
		"androidx.room",
		"com.adsamcik.tracker.shared.base.database",
		"com.adsamcik.tracker.shared.base.data.",
		"com.adsamcik.tracker.shared.base.extension",
		"com.adsamcik.tracker.shared.base.permission",
		"com.adsamcik.tracker.shared.base.misc",
		"com.adsamcik.tracker.shared.base.assist",
	)

	@Test
	fun `core common contains no forbidden imports`() {
		val mainDir = resolveExistingDirectory("src/main", "core/common/src/main")
		val violations = mutableListOf<String>()

		mainDir.walkTopDown()
			.filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
			.forEach { file ->
				file.readLines().forEachIndexed { index, line ->
					val trimmed = line.trim()
					if (trimmed.startsWith("import ")) {
						forbiddenImports.forEach { forbidden ->
							if (trimmed.contains(forbidden)) {
								violations.add("${file.name}:${index + 1} -> $trimmed")
							}
						}
					}
				}
			}

		assertTrue(
			violations.isEmpty(),
			"core:common must not depend on database/Room/base data models. Violations:\n" +
				violations.joinToString("\n"),
		)
	}

	private fun resolveExistingDirectory(vararg candidates: String): File {
		return candidates
			.asSequence()
			.map(::File)
			.firstOrNull(File::isDirectory)
			?: error(
				"core:common source directory not found; checked: " +
					candidates.joinToString(),
			)
	}
}
