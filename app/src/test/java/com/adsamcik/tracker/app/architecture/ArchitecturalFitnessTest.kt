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
 * - No direct Dispatchers usage (use DispatchersProvider) — both the
 *   member-import form and the whole-object-import + qualified-use form
 *   are banned outside the provider/DI files
 * - Only `:core:network` may construct OkHttpClient (every other consumer must
 *   route through `NetworkGateway` / `OkHttpBackedGateway.okHttpCallFactory()`)
 * - `:stats:api` must remain Android-free (pure Kotlin)
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

		// R1 round-7 P3: the three import-form tests above miss the OTHER
		// common shape — whole-object import (`kotlinx.coroutines.Dispatchers`)
		// followed by `Dispatchers.IO/Main/Default` references. That is exactly
		// what `DefaultNetworkGateway` introduced. This stronger check scans
		// production-source bodies for any `Dispatchers.IO/Main/Default`
		// reference, regardless of the import shape, so dispatcher boundaries
		// cannot drift through the alternate import.
		//
		// The allowlist documents the LEGITIMATE provider/DI files plus the
		// small set of LEGACY direct-usage callsites we have not migrated yet.
		// New files MUST inject `DispatchersProvider` instead.
		@Test
		fun `production code does not reference Dispatchers IO Main or Default directly`() {
			val violations = findPatternMatching(
				sourceDir = projectRoot,
				pattern = DISPATCHERS_USAGE_PATTERN,
				// src/debug ships in debug builds only and is intentionally allowed
				// to use direct dispatchers for seeders/dev tooling that never run
				// in release. src/test and src/androidTest are tests, not production.
				excludeDirs = STANDARD_EXCLUDES + listOf(
					"src/test",
					"src/androidTest",
					"src/debug",
				),
				excludeFiles = DISPATCHERS_PROVIDER_FILES + listOf("ArchitecturalFitnessTest.kt"),
				skipComments = true,
			)
			val filtered = violations.filterNot { violation ->
				val normalized = violation.replace('\\', '/')
				LEGACY_DISPATCHERS_DIRECT_USE_ALLOWLIST.any { allowed -> normalized.startsWith(allowed) }
			}
			if (filtered.isNotEmpty()) {
				error(
					"Direct `Dispatchers.IO/Main/Default` usage in production code is " +
						"forbidden. Inject `DispatchersProvider` (or `@IoDispatcher CoroutineDispatcher`) " +
						"and use `.io / .main / .default` instead so tests can steer threading " +
						"through the test scheduler. If this is a NEW legitimate provider, " +
						"add it to DISPATCHERS_PROVIDER_FILES; if it is legacy callsite, add " +
						"it to LEGACY_DISPATCHERS_DIRECT_USE_ALLOWLIST with a migration note.\n" +
						"Violations:\n" + filtered.joinToString("\n")
				)
			}
		}
	}

	@Nested
	inner class `OkHttp construction ban` {
		// R1 round-7 P4: `:core:network` exposes okhttp3 as `api` so consumers can
		// request a raw `Call.Factory` from `OkHttpBackedGateway.okHttpCallFactory()`
		// for libraries like MapLibre that need a real `OkHttpClient`. Constructing
		// a fresh `OkHttpClient` anywhere ELSE bypasses the gateway's interceptor
		// chain (kill switch, allowlist, rate limit, HTTPS guard, anonymous UA)
		// and silently re-introduces an unaudited egress path — defeating the
		// whole purpose of routing every network consumer through `NetworkGateway`.
		// Only `:core:network` itself may construct `OkHttpClient`.
		@Test
		fun `only the network module constructs OkHttpClient`() {
			val violations = findPatternMatching(
				sourceDir = projectRoot,
				pattern = OKHTTP_CONSTRUCT_PATTERN,
				// Tests under src/test/src/androidTest may construct OkHttp freely for
				// MockWebServer fixtures. Production code under any module OTHER than
				// :network may not.
				excludeDirs = STANDARD_EXCLUDES + listOf(
					"src/test",
					"src/androidTest",
					"core/network/src/main",
				),
				excludeFiles = listOf("ArchitecturalFitnessTest.kt"),
				skipComments = true,
			)
			if (violations.isNotEmpty()) {
				error(
					"OkHttpClient construction outside :core:network is forbidden. Inject " +
						"`NetworkGateway` for suspend-based requests, or call " +
						"`OkHttpBackedGateway.okHttpCallFactory()` for libraries that " +
						"need a raw `okhttp3.Call.Factory`. Violations:\n" +
						violations.joinToString("\n")
				)
			}
		}
	}

	@Nested
	inner class `stats api purity` {
		@Test
		fun `stats api commonMain has zero Android imports`() {
			val statsApiDir = projectRoot.resolve("stats/api/src/commonMain")
			check(statsApiDir.isDirectory) {
				"Expected :stats:api commonMain source directory at ${statsApiDir.absolutePath}"
			}

			val violations = findImportsMatching(
				sourceDir = statsApiDir,
				pattern = Regex("^import\\s+(android\\.|androidx\\.)"),
				excludeDirs = STANDARD_EXCLUDES,
			)
			violations.shouldBeEmpty()
		}
	}

	@Nested
	inner class `Module dependency direction` {
		@Test
		fun `core network does not depend on the database module`() {
			val buildFile = projectRoot.resolve("core/network/build.gradle.kts")
			check(buildFile.isFile) {
				"Expected :core:network build file at ${buildFile.absolutePath}"
			}

			val violations = PROJECT_DEPENDENCY_PATTERN.findAll(buildFile.readText())
				.map { match -> match.groupValues[1] }
				.filter { dependency -> dependency == ":core:base" }
				.toList()

			violations.shouldBeEmpty()
		}

		@Test
		fun `feature modules do not add implementation to implementation edges`() {
			val featureDir = projectRoot.resolve("feature")
			check(featureDir.isDirectory) {
				"Expected feature module directory at ${featureDir.absolutePath}"
			}

			val violations = featureDir.walkTopDown()
				.onEnter { directory ->
					!directory.isInExcludedDirectory(STANDARD_EXCLUDES)
				}
				.filter { file ->
					file.isFile &&
						file.name == "build.gradle.kts" &&
						file.parentFile?.name != "api"
				}
				.flatMap { buildFile ->
					val moduleDirectory = checkNotNull(buildFile.parentFile)
					val owner = ":" + moduleDirectory
						.relativeTo(projectRoot)
						.invariantSeparatorsPath
						.replace('/', ':')
					PROJECT_DEPENDENCY_PATTERN.findAll(buildFile.readText())
						.map { match -> owner to match.groupValues[1] }
				}
				.filter { (_, dependency) ->
					dependency.startsWith(":feature:") && !dependency.endsWith(":api")
				}
				.filterNot { edge -> edge in LEGACY_FEATURE_IMPLEMENTATION_EDGES }
				.map { (owner, dependency) -> "$owner -> $dependency" }
				.toList()

			violations.shouldBeEmpty()
		}

		@Test
		fun `feature modules depend on contracts rather than engine implementations`() {
			val featureDir = projectRoot.resolve("feature")
			check(featureDir.isDirectory) {
				"Expected feature module directory at ${featureDir.absolutePath}"
			}

			val violations = featureDir.walkTopDown()
				.onEnter { directory ->
					!directory.isInExcludedDirectory(STANDARD_EXCLUDES)
				}
				.filter { file -> file.isFile && file.name == "build.gradle.kts" }
				.flatMap { buildFile ->
					val moduleDirectory = checkNotNull(buildFile.parentFile)
					val owner = ":" + moduleDirectory
						.relativeTo(projectRoot)
						.invariantSeparatorsPath
						.replace('/', ':')
					PROJECT_DEPENDENCY_PATTERN.findAll(buildFile.readText())
						.map { match -> owner to match.groupValues[1] }
				}
				.filter { (_, dependency) -> dependency.endsWith(":engine") }
				.map { (owner, dependency) -> "$owner -> $dependency" }
				.toList()

			violations.shouldBeEmpty()
		}

		@Test
		fun `tracker notification feature does not own preference persistence`() {
			val notificationSourceDir = projectRoot.resolve(
				"feature/tracker/src/main/java/com/adsamcik/tracker/feature/tracker/notification"
			)
			check(notificationSourceDir.isDirectory) {
				"Expected tracker notification feature sources at " +
					notificationSourceDir.absolutePath
			}

			val violations = findImportsMatching(
				sourceDir = notificationSourceDir,
				pattern = Regex(
					"""^\s*import\s+com\.adsamcik\.tracker\.shared\.base\.database(?:\.|$)"""
				),
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

		return sourceDir.walkTopDown()
			.onEnter { directory ->
				!directory.isInExcludedDirectory(excludeDirs)
			}
			.filter { it.isFile && it.extension == "kt" }
			.filterNot { file -> file.isInExcludedDirectory(excludeDirs) }
			.filter { file -> excludeFiles.none { name -> file.name == name } }
			.filter { file ->
				if (onlyInDirs.isEmpty()) return@filter true
				val normalized = file.absolutePath.replace('\\', '/')
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
		skipComments: Boolean = false,
	): List<String> {
		if (!sourceDir.exists()) return emptyList()

		return sourceDir.walkTopDown()
			.onEnter { directory ->
				!directory.isInExcludedDirectory(excludeDirs)
			}
			.filter { it.isFile && it.extension == "kt" }
			.filterNot { file -> file.isInExcludedDirectory(excludeDirs) }
			.filter { file -> excludeFiles.none { name -> file.name == name } }
			.flatMap { file ->
				file.readLines()
					.mapIndexedNotNull { index, line ->
						if (!pattern.containsMatchIn(line)) return@mapIndexedNotNull null
						if (skipComments && isCommentLine(line)) return@mapIndexedNotNull null
						"${file.relativeTo(sourceDir)}:${index + 1}: $line"
					}
			}
			.toList()
	}

	private fun File.isInExcludedDirectory(excludeDirs: List<String>): Boolean {
		val normalizedPath = absolutePath.replace('\\', '/')
		return excludeDirs.any { excluded ->
			normalizedPath.endsWith("/$excluded") || "/$excluded/" in normalizedPath
		}
	}

	/**
	 * Best-effort line-comment detector: skips lines that are clearly comments
	 * by their leading non-whitespace token. Does not understand multi-line
	 * `/* ... */` blocks — but our scanners are looking for non-trivial source
	 * tokens (`OkHttpClient(`, `Dispatchers.IO`) and matches inside block
	 * comments are vanishingly rare. KDoc lines (` * text`) are correctly
	 * skipped because their first non-whitespace char is `*`.
	 */
	private fun isCommentLine(line: String): Boolean {
		val trimmed = line.trimStart()
		return trimmed.startsWith("//") ||
			trimmed.startsWith("*") ||
			trimmed.startsWith("/*")
	}

	companion object {
		// Exclude build artifacts, IDE caches, AND git worktrees (`.worktrees/`) —
		// worktrees contain other branches' source trees that aren't part of the
		// current branch's compilation unit but otherwise look like real sources
		// and trip every fitness check.
		private val STANDARD_EXCLUDES = listOf("build", ".gradle", ".idea", ".git", ".worktrees")

		// `(?<![A-Za-z0-9_.])` ensures we don't match `setOkHttpClient(`,
		// `MyOkHttpClient(`, etc. — only `OkHttpClient(` and `OkHttpClient.Builder(`
		// as standalone tokens (preceded by whitespace, `=`, `(`, `:`, etc.).
		private val OKHTTP_CONSTRUCT_PATTERN = Regex(
			"""(?<![A-Za-z0-9_.])OkHttpClient(?:\s*\(|\.Builder\s*\()"""
		)

		// `(?<![A-Za-z0-9_])` ensures we don't match `MyDispatchers.IO` or
		// `customDispatchers.io` (lowercase) — only the platform `Dispatchers`
		// object's IO/Main/Default fields. `\b` after the field name prevents
		// `Dispatchers.IOSomething` false positives.
		private val DISPATCHERS_USAGE_PATTERN = Regex(
			"""(?<![A-Za-z0-9_])Dispatchers\.(IO|Main|Default)\b"""
		)

		private val PROJECT_DEPENDENCY_PATTERN = Regex(
			"""project\(\s*"(:[^"]+)"\s*\)"""
		)

		// Existing feature-implementation edges that need contract extraction.
		// New implementation edges must not join this list.
		private val LEGACY_FEATURE_IMPLEMENTATION_EDGES = setOf(
			":feature:statistics" to ":feature:map",
			":feature:statistics" to ":feature:import-export",
		)

		// Files that LEGITIMATELY reference Dispatchers.* directly because they
		// ARE the project's dispatcher provider / DI plumbing. The file
		// `DispatchersProvider.kt` hosts both the `DispatchersProvider` interface
		// and the `DefaultDispatchersProvider` object that returns the real
		// platform dispatchers.
		private val DISPATCHERS_PROVIDER_FILES = listOf(
			"DispatchersProvider.kt",
			"DefaultDispatchersProvider.kt",
			"InfrastructureModule.kt",
			"LoggerDispatchers.kt",
		)

		// LEGACY direct-`Dispatchers.IO/Main/Default` callsites that pre-date
		// the project-wide `DispatchersProvider` rule. New code must NOT join
		// this list — inject `DispatchersProvider` instead. Tracked as carried-
		// over tech debt; intentionally short to make every entry visible.
		private val LEGACY_DISPATCHERS_DIRECT_USE_ALLOWLIST = listOf(
			// `withContext(Dispatchers.IO)` in WorkManager worker / ViewModel —
			// should migrate to injected `DispatchersProvider.io` so tests can
			// steer through the test scheduler instead of relying on real I/O
			// pool threads.
			"app/src/main/java/com/adsamcik/tracker/app/settings/osm/OsmImportSettingsViewModel.kt:",
		)

		private val LEGACY_TEST_LOCATION_IMPORT_ALLOWLIST = listOf(
			"tracker/engine/src/test/java/com/adsamcik/tracker/tracker/altitude/AltitudeProcessorTest.kt:",
			"tracker/engine/src/test/java/com/adsamcik/tracker/tracker/component/consumer/data/LocationTrackerComponentTest.kt:",
			"tracker/engine/src/test/java/com/adsamcik/tracker/tracker/component/consumer/SessionTrackerComponentTest.kt:",
			"tracker/engine/src/test/java/com/adsamcik/tracker/tracker/component/trigger/FusedLocationCollectionTriggerTest.kt:",
			"tracker/engine/src/test/java/com/adsamcik/tracker/tracker/data/collection/TrackingCycleTest.kt:",
			"tracker/engine/src/test/java/com/adsamcik/tracker/tracker/service/TrackingCycleDispatcherTest.kt:",
			"tracker/engine/src/test/java/com/adsamcik/tracker/tracker/service/TrackingOrchestratorIntegrationTest.kt:",
			// Multi-session lifecycle integration test (commit f8410a8e5) drives
			// the orchestrator with real LocationData payloads — LocationData
			// wraps List<android.location.Location>, so the test needs the
			// platform type to construct fixtures. Replace once a
			// TestLocations.location(...) helper lands in :testing-common.
			"tracker/engine/src/test/java/com/adsamcik/tracker/tracker/service/MultiSessionLifecycleTest.kt:",
			"feature/map/src/test/java/com/adsamcik/tracker/map/presentation/sensors/LocationAndSensorsManagerTest.kt:",
		)
	}
}
