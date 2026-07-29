package com.adsamcik.tracker.game.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Goals observe the immutable tracker API state. They must not regain the old
 * Room-backed session channel or the reflective listener bridge.
 */
class GameTrackerStateBoundaryTest {
	private val moduleDir = resolveModuleDirectory()

	@Test
	fun `goals depend on tracker API snapshots`() {
		val goalsDir = File(
			moduleDir,
			"src/main/java/com/adsamcik/tracker/game/goals",
		)
		check(goalsDir.isDirectory) { "Game goals source directory is missing" }

		val source = goalsDir.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.joinToString("\n") { it.readText() }

		assertTrue(source.contains("TrackerStateReader"))
		assertTrue(source.contains("TrackerSessionSnapshot"))
		assertFalse(source.contains("TrackerSessionChannel"))
		assertFalse(source.contains("TrackerUpdateReceiver"))
		assertFalse(source.contains("com.adsamcik.tracker.shared.base.data.TrackerSession"))
	}

	@Test
	fun `game declares tracker API without engine implementation dependency`() {
		val buildText = File(moduleDir, "build.gradle.kts").readText()

		assertTrue(buildText.contains("""project(":tracker:api")"""))
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
