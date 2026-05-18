package com.adsamcik.tracker.app.architecture

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Architectural fitness functions that verify project-wide conventions
 * by scanning source files on the host JVM.
 *
 * These tests enforce hard rules from the project's architecture:
 * - No GlobalScope usage (use injected CoroutineScope)
 * - No direct Dispatchers usage (use DispatchersProvider)
 * - stats-api must remain Android-free (pure Kotlin)
 * - No android.location.Location in test sources (use fakes)
 * - No compositionLocalOf<Any> (overly broad type)
 */
class ArchitecturalFitnessTest {

	private val projectRoot: File by lazy {
		// Walk up from the working directory to find the project root (contains settings.gradle.kts)
		val userDir = System.getProperty("user.dir") ?: "."
		var dir = File(userDir)
		while (true) {
			if (dir.resolve("settings.gradle.kts").exists() || dir.resolve("detekt.yml").exists()) {
				return@lazy dir
			}
			val parent = dir.parentFile ?: break
			dir = parent
		}
		// Fallback: assume CWD is the project root
		File(userDir)
	}

	@Nested
	inner class `GlobalScope ban` {
		@Test
		fun `no module uses GlobalScope`() {
			val violations = findImportsMatching(
				sourceDir = projectRoot,
				pattern = Regex("import\\s+kotlinx\\.coroutines\\.GlobalScope"),
				excludeDirs = STANDARD_EXCLUDES,
			)
			violations.shouldBeEmpty()
		}
	}

	@Nested
	inner class `Direct Dispatchers ban` {
		@Test
		fun `no module imports Dispatchers IO directly`() {
			val violations = findImportsMatching(
				sourceDir = projectRoot,
				pattern = Regex("import\\s+kotlinx\\.coroutines\\.Dispatchers\\.IO\\b"),
				excludeDirs = STANDARD_EXCLUDES,
				// DI module legitimately provides dispatchers
				excludeFiles = listOf("InfrastructureModule.kt", "DefaultDispatchersProvider.kt"),
			)
			violations.shouldBeEmpty()
		}

		@Test
		fun `no module imports Dispatchers Main directly`() {
			val violations = findImportsMatching(
				sourceDir = projectRoot,
				pattern = Regex("import\\s+kotlinx\\.coroutines\\.Dispatchers\\.Main\\b"),
				excludeDirs = STANDARD_EXCLUDES,
				excludeFiles = listOf("DefaultDispatchersProvider.kt"),
			)
			violations.shouldBeEmpty()
		}

		@Test
		fun `no module imports Dispatchers Default directly`() {
			val violations = findImportsMatching(
				sourceDir = projectRoot,
				pattern = Regex("import\\s+kotlinx\\.coroutines\\.Dispatchers\\.Default\\b"),
				excludeDirs = STANDARD_EXCLUDES,
				excludeFiles = listOf("InfrastructureModule.kt", "DefaultDispatchersProvider.kt"),
			)
			violations.shouldBeEmpty()
		}
	}

	@Nested
	inner class `stats-api purity` {
		@Test
		fun `stats-api commonMain has zero Android imports`() {
			val statsApiDir = projectRoot.resolve("stats-api/src/commonMain")
			if (!statsApiDir.exists()) return // Module not present

			val violations = findImportsMatching(
				sourceDir = statsApiDir,
				pattern = Regex("^import\\s+(android\\.|androidx\\.)"),
				excludeDirs = STANDARD_EXCLUDES,
			)
			violations.shouldBeEmpty()
		}
	}

	@Nested
	inner class `Test source hygiene` {
		@Test
		fun `test sources do not use android location Location directly`() {
			val violations = findImportsMatching(
				sourceDir = projectRoot,
				pattern = Regex("import\\s+android\\.location\\.Location$"),
				excludeDirs = STANDARD_EXCLUDES,
				onlyInDirs = listOf("src/test"),
			)
			// Allow current legacy tests until they migrate to FakeLocationSource.
			val filtered = violations.filterNot { violation ->
				val normalizedViolation = violation.replace('\\', '/')
				LEGACY_TEST_LOCATION_IMPORT_ALLOWLIST.any { allowed -> normalizedViolation.startsWith(allowed) }
			}
			filtered.shouldBeEmpty()
		}
	}

	@Nested
	inner class `Compose safety` {
		@Test
		fun `no compositionLocalOf with Any type`() {
			val violations = findPatternMatching(
				sourceDir = projectRoot,
				// @Suppress: regex literal, not actual usage
				pattern = Regex("""compositionLocalOf<Any>"""),
				excludeDirs = STANDARD_EXCLUDES + listOf("src/test", "src/androidTest"),
				excludeFiles = listOf("ArchitecturalFitnessTest.kt"),
			)
			violations.shouldBeEmpty()
		}
	}

	// ─── Helpers ──────────────────────────────────────────────────────

	private fun findImportsMatching(
		sourceDir: File,
		pattern: Regex,
		excludeDirs: List<String> = emptyList(),
		excludeFiles: List<String> = emptyList(),
		onlyInDirs: List<String> = emptyList(),
	): List<String> {
		if (!sourceDir.exists()) return emptyList()

		// Normalize path separators for cross-platform matching
		fun String.normalizedPath() = replace('\\', '/')

		return sourceDir.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.filter { file ->
				val normalized = file.absolutePath.normalizedPath()
				excludeDirs.none { dir -> "/$dir/" in normalized }
			}
			.filter { file -> excludeFiles.none { name -> file.name == name } }
			.filter { file ->
				if (onlyInDirs.isEmpty()) return@filter true
				val normalized = file.absolutePath.normalizedPath()
				onlyInDirs.any { dir -> "/$dir/" in normalized }
			}
			.flatMap { file ->
				file.readLines()
					.mapIndexedNotNull { index, line ->
						if (pattern.containsMatchIn(line)) {
							"${file.relativeTo(sourceDir)}:${index + 1}: $line"
						} else {
							null
						}
					}
			}
			.toList()
	}

	private fun findPatternMatching(
		sourceDir: File,
		pattern: Regex,
		excludeDirs: List<String> = emptyList(),
		excludeFiles: List<String> = emptyList(),
	): List<String> {
		if (!sourceDir.exists()) return emptyList()

		fun String.normalizedPath() = replace('\\', '/')

		return sourceDir.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.filter { file ->
				val normalized = file.absolutePath.normalizedPath()
				excludeDirs.none { dir -> "/$dir/" in normalized }
			}
			.filter { file -> excludeFiles.none { name -> file.name == name } }
			.flatMap { file ->
				file.readLines()
					.mapIndexedNotNull { index, line ->
						if (pattern.containsMatchIn(line)) {
							"${file.relativeTo(sourceDir)}:${index + 1}: $line"
						} else {
							null
						}
					}
			}
			.toList()
	}

	companion object {
		private val STANDARD_EXCLUDES = listOf("build", ".gradle", ".idea", ".git")
		private val LEGACY_TEST_LOCATION_IMPORT_ALLOWLIST = listOf(
			"tracker/src/test/java/com/adsamcik/tracker/tracker/altitude/AltitudeProcessorTest.kt:",
			"tracker/src/test/java/com/adsamcik/tracker/tracker/component/consumer/data/LocationTrackerComponentTest.kt:",
			"tracker/src/test/java/com/adsamcik/tracker/tracker/component/consumer/SessionTrackerComponentTest.kt:",
			"tracker/src/test/java/com/adsamcik/tracker/tracker/component/trigger/AndroidLocationCollectionTriggerTest.kt:",
			"tracker/src/test/java/com/adsamcik/tracker/tracker/component/trigger/FusedLocationCollectionTriggerTest.kt:",
			"tracker/src/test/java/com/adsamcik/tracker/tracker/data/collection/TrackingCycleTest.kt:",
			"tracker/src/test/java/com/adsamcik/tracker/tracker/service/TrackingOrchestratorIntegrationTest.kt:",
			"map/src/test/java/com/adsamcik/tracker/map/presentation/sensors/LocationAndSensorsManagerTest.kt:",
		)
	}
}
