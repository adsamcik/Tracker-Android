package com.adsamcik.tracker.game.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse

/**
 * Goal presentation is composed only from source-qualified numeric summaries. It must not
 * regain a live tracker snapshot, Room-backed session channel, or reflective listener bridge.
 */
class GameTrackerStateBoundaryTest {
	private val moduleDir = resolveModuleDirectory()

	@Test
	fun `goal presentation has no tracker snapshot dependency`() {
		val goalsDir = File(
			moduleDir,
			"src/main/java/com/adsamcik/tracker/game/goals",
		)
		check(goalsDir.isDirectory) { "Game goals source directory is missing" }

		val source = goalsDir.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.joinToString("\n") { it.readText() }

		assertFalse(source.contains("TrackerStateReader"))
		assertFalse(source.contains("TrackerSessionSnapshot"))
		assertFalse(source.contains("TrackerSessionChannel"))
		assertFalse(source.contains("TrackerUpdateReceiver"))
		assertFalse(source.contains("com.adsamcik.tracker.shared.base.data.TrackerSession"))
	}

	@Test
	fun `game declares no tracker session dependency`() {
		val buildText = File(moduleDir, "build.gradle.kts").readText()

		assertFalse(buildText.contains("""project(":tracker:api")"""))
		assertFalse(buildText.contains("""project(":tracker:engine")"""))
	}

	private fun resolveModuleDirectory(): File {
		val candidates = listOf(File("."), File("feature/game"))
		return candidates.firstOrNull { candidate ->
			File(candidate, "build.gradle.kts").isFile &&
				File(candidate, "src/main").isDirectory
		} ?: error(
			":feature:game module directory not found; checked: " +
				candidates.joinToString { it.absolutePath },
		)
	}
}
