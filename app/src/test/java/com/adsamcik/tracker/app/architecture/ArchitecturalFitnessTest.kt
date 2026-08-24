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
 * - Contract APIs must remain Android/Room-free where their boundary requires it
 * - Feature and app presentation must not open persistence databases or import entities/DAOs
 * - Android production code cannot install an alternate crash or Logcat diagnostics writer
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
	inner class `Tracebox production integration` {
		@Test
		fun `app has one unconditional Tracebox dependency path`() {
			val buildText = projectRoot.resolve("app/build.gradle.kts").readText()
			buildList {
				listOf(
					"implementation(libs.tracebox)",
					"implementation(libs.tracebox.native)",
					"implementation(libs.tracebox.ui.compose)",
				).filterNot(buildText::contains)
					.mapTo(this) { "app/build.gradle.kts -> missing $it" }
				listOf(
					"traceboxTrial",
					"TRACEBOX_TRIAL_AVAILABLE",
					"flavorDimensions",
					"productFlavors",
				).filterTo(this) { marker -> marker in buildText }
				listOf(
					"app/src/standard",
					"app/src/traceboxTrial",
				).filter { projectRoot.resolve(it).exists() }
					.mapTo(this) { path -> "$path -> retired source set still exists" }
			}.shouldBeEmpty()
		}

		@Test
		fun `CI consumes immutable Tracebox packages with a scoped workflow token`() {
			val settings = projectRoot.resolve("settings.gradle.kts").readText()
			val androidWorkflow = projectRoot.resolve(".github/workflows/android.yml").readText()
			val codeqlWorkflow = projectRoot.resolve(".github/workflows/codeql.yml").readText()
			val workflowToken = "GITHUB_TOKEN: $" + "{{ github.token }}"

			buildList {
				if ("if (!providers.environmentVariable(\"CI\").isPresent)" !in settings) {
					add("settings.gradle.kts -> CI must not resolve Tracebox from Maven Local")
				}
				if ("https://maven.pkg.github.com/adsamcik/tracebox" !in settings) {
					add("settings.gradle.kts -> Tracebox GitHub Packages repository is missing")
				}
				if (Regex("(?m)^\\s+packages: read\\s*$").findAll(androidWorkflow).count() < 2) {
					add("android.yml -> both Gradle jobs need packages: read")
				}
				if (androidWorkflow.windowed(workflowToken.length).count { it == workflowToken } < 2) {
					add("android.yml -> both Gradle jobs must expose github.token to Gradle")
				}
				if ("Verify Tracebox package access" !in androidWorkflow ||
					"--refresh-dependencies" !in androidWorkflow
				) {
					add("android.yml -> forced Tracebox package resolution check is missing")
				}
				if ("packages: read" !in codeqlWorkflow || workflowToken !in codeqlWorkflow) {
					add("codeql.yml -> manual Gradle build needs read-only package authentication")
				}
			}.shouldBeEmpty()
		}

		@Test
		fun `Application installs Tracebox during attachment and excludes its handler`() {
			val source = projectRoot.resolve(
				"app/src/main/java/com/adsamcik/tracker/app/Application.kt",
			).readText()
			val attach = source.indexOf("override fun attachBaseContext(base: Context)")
			val attachSuper = source.indexOf("super.attachBaseContext(base)", attach)
			val handlerGuard = source.indexOf("isTraceboxHandlerProcessName(processName, packageName)")
			val install = source.indexOf("TrackerTraceboxRuntime.install(this)")
			val onCreate = source.indexOf("override fun onCreate()")
			buildList {
				if (attach < 0 || attachSuper < attach || onCreate < 0 || attachSuper > onCreate) {
					add("Tracebox bootstrap must run from Application.attachBaseContext")
				}
				if (handlerGuard < attachSuper || handlerGuard > install) {
					add("Tracebox handler guard must precede attachment-time installation")
				}
				if (install < 0 || install > onCreate) {
					add("Tracebox must install before providers and Application.onCreate")
				}
				listOf(
					"Reporter.initialize(",
					"Logger.initialize(",
					"CrashHandler(",
					"TraceboxTrial",
					"TRACEBOX_TRIAL_AVAILABLE",
				).filterTo(this) { marker -> marker in source }
			}.shouldBeEmpty()
		}

		@Test
		fun `production app sources contain no trial integration`() {
			findPatternMatching(
				sourceDir = projectRoot.resolve("app/src/main"),
				pattern = Regex("""\b(?:TraceboxTrial|traceboxTrial|TRACEBOX_TRIAL)\w*"""),
				excludeDirs = STANDARD_EXCLUDES,
			).shouldBeEmpty()
		}

		@Test
		fun `Tracebox is the only app diagnostics UI and legacy surfaces are absent`() {
			val settingsRoute = projectRoot.resolve(
				"app/src/main/java/com/adsamcik/tracker/app/settings/SettingsRoute.kt",
			).readText()
			val rootSettings = projectRoot.resolve(
				"app/src/main/java/com/adsamcik/tracker/app/settings/root/RootSettingsScreen.kt",
			).readText()
			val manifest = projectRoot.resolve("app/src/main/AndroidManifest.xml").readText()
			val strings = projectRoot.resolve("app/src/main/res/values/strings.xml").readText()

			buildList {
				if ("SettingsScreen.Diagnostics -> TraceboxDiagnosticsScreen(" !in settingsRoute) {
					add("Settings diagnostics route must open TraceboxDiagnosticsScreen")
				}
				if ("onNavigate(SettingsScreen.Diagnostics)" !in rootSettings) {
					add("Root settings must expose the normal Tracebox diagnostics screen")
				}
				addAll(
					findPatternMatching(
						sourceDir = projectRoot.resolve("app/src"),
						pattern = Regex(
							"""\b(?:CrashManagerActivity|CrashLogManager|DebugLogReader|""" +
								"""DebugLogEntry|LegacyDiagnosticDeletionResult|""" +
								"""LegacyDiagnosticsStore|RoomDebugLogReader)\b|""" +
								"""com\.adsamcik\.tracker\.logger(?:\.|$)|""" +
								"""\bReporterFacade\b|debug_database|pre-Tracebox""",
						),
						excludeDirs = STANDARD_EXCLUDES + listOf(
							"test",
							"androidTest",
							"testFixtures",
						),
						skipComments = true,
					),
				)
				listOf(
					"CrashManagerActivity",
					"pre-Tracebox",
					"TrackerDiagnostics",
					"TrackerDiagnosticCode",
					"TraceboxDiagnosticsController",
					"TraceboxDiagnosticsViewModel",
					"settings_debug_crash_manager",
					"settings_debug_log_viewer",
				).filterTo(this) { marker -> marker in manifest || marker in strings }
			}.shouldBeEmpty()
		}

		@Test
		fun `Android production sources contain no alternate diagnostics writer`() {
			findPatternMatching(
				sourceDir = projectRoot,
				pattern = Regex(
					"""(?:android\.util\.Log\b|java\.util\.logging\b|org\.slf4j\b|""" +
						"""timber\.log\.Timber\b|co\.touchlab\.kermit\b|\.penaltyLog\s*\(|""" +
						"""setDefaultUncaughtExceptionHandler\s*\(|""" +
						"""Thread\.UncaughtExceptionHandler\b)""",
				),
				excludeDirs = STANDARD_EXCLUDES + listOf(
					"test",
					"androidTest",
					"commonTest",
					"androidHostTest",
					"testFixtures",
				),
				skipComments = true,
			).shouldBeEmpty()
		}

		@Test
		fun `retired logging modules and APIs cannot return`() {
			val retiredModuleNames = listOf("logging", "logging-api")
			val retiredTypeName = "Tracker" + "Log"
			val settings = projectRoot.resolve("settings.gradle.kts").readText()

			buildList {
				retiredModuleNames.forEach { moduleName ->
					if (projectRoot.resolve("core/$moduleName").exists()) {
						add("core/$moduleName -> retired module directory still exists")
					}
					if ("\":core:$moduleName\"" in settings) {
						add("settings.gradle.kts -> retired :core:$moduleName module is included")
					}
				}
				addAll(
					findPatternMatching(
						sourceDir = projectRoot,
						pattern = Regex(
							"""(?:com\.adsamcik\.tracker\.(?:logging\.api|logger)(?:\.|$)|""" +
								"""\b${Regex.escape(retiredTypeName)}\b)""",
						),
						excludeDirs = STANDARD_EXCLUDES,
						excludeFiles = listOf("ArchitecturalFitnessTest.kt"),
						skipComments = true,
					),
				)
			}.shouldBeEmpty()
		}
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
	inner class `contract API purity` {
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

		@Test
		fun `diagnostics boundary reexports Tracebox without a Tracker facade`() {
			val buildText = projectRoot.resolve("core/diagnostics/build.gradle.kts").readText()
			if ("api(libs.tracebox)" !in buildText) {
				error(":core:diagnostics must expose Tracebox directly")
			}
			val diagnosticsDir = projectRoot.resolve("core/diagnostics/src/main")
			if (diagnosticsDir.exists()) {
				findPatternMatching(
					sourceDir = diagnosticsDir,
					pattern = Regex("TrackerDiagnostics|TrackerDiagnosticCode|TrackerLog"),
					excludeDirs = STANDARD_EXCLUDES,
				).shouldBeEmpty()
			}
		}

		@Test
		fun `statistics feature api is Android free`() {
			val statisticsApiDir = projectRoot.resolve("feature/statistics/api/src/main")
			check(statisticsApiDir.isDirectory) {
				"Expected :feature:statistics:api production sources at " +
					statisticsApiDir.absolutePath
			}

			val violations = findImportsMatching(
				sourceDir = statisticsApiDir,
				pattern = Regex("""^import\s+(?:android\.|androidx\.)"""),
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
						file.name == "build.gradle.kts"
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
				.map { (owner, dependency) -> "$owner -> $dependency" }
				.toList()

			violations.shouldBeEmpty()
		}

		@Test
		fun `feature ViewModels depend on ports rather than Room implementations`() {
			val featureDir = projectRoot.resolve("feature")
			check(featureDir.isDirectory) {
				"Expected feature module directory at ${featureDir.absolutePath}"
			}

			val excludes = STANDARD_EXCLUDES + listOf("src/test", "src/androidTest")
			val violations = featureDir.walkTopDown()
				.onEnter { directory -> !directory.isInExcludedDirectory(excludes) }
				.filter { file ->
					file.isFile &&
						file.extension == "kt" &&
						!file.isInExcludedDirectory(excludes)
				}
				.filter { file ->
					VIEW_MODEL_DECLARATION_PATTERN.containsMatchIn(file.readText())
				}
				.flatMap { file ->
					file.readLines().mapIndexedNotNull { index, line ->
						if (
							FEATURE_VIEWMODEL_PERSISTENCE_IMPORT_PATTERNS.any {
								pattern -> pattern.containsMatchIn(line)
							}
						) {
							"${file.relativeTo(featureDir)}:${index + 1}: $line"
						} else {
							null
						}
					}
				}
				.toList()

			if (violations.isNotEmpty()) {
				error(
					"Feature ViewModels must depend on feature-facing repositories/ports, " +
						"not AppDatabase, Room APIs/DAOs, or persistence entities. Violations:\n" +
						violations.joinToString("\n")
				)
			}
		}

		@Test
		fun `feature UI and ViewModels do not open persistence databases directly`() {
			val featureDir = projectRoot.resolve("feature")
			check(featureDir.isDirectory) {
				"Expected feature module directory at ${featureDir.absolutePath}"
			}

			val excludes = STANDARD_EXCLUDES + listOf("src/test", "src/androidTest")
			val violations = featureDir.walkTopDown()
				.onEnter { directory -> !directory.isInExcludedDirectory(excludes) }
				.filter { file ->
					file.isFile &&
						file.extension == "kt" &&
						!file.isInExcludedDirectory(excludes)
				}
				.filter { file ->
					val path = "/${file.relativeTo(featureDir).invariantSeparatorsPath}"
					val source = file.readText()
					"/ui/" in path ||
						"/compose/" in path ||
						COMPOSABLE_DECLARATION_PATTERN.containsMatchIn(source) ||
						VIEW_MODEL_DECLARATION_PATTERN.containsMatchIn(source)
				}
				.flatMap { file ->
					file.readLines().mapIndexedNotNull { index, line ->
						val isPersistenceReference =
							PRESENTATION_DATABASE_OPEN_PATTERN.containsMatchIn(line) ||
								FEATURE_UI_PERSISTENCE_IMPORT_PATTERNS.any {
									pattern -> pattern.containsMatchIn(line)
								}
						if (isPersistenceReference && !isCommentLine(line)) {
							"${file.relativeTo(featureDir)}:${index + 1}: $line"
						} else {
							null
						}
					}
				}
				.toList()

			if (violations.isNotEmpty()) {
				error(
					"Feature UI and ViewModels must obtain persisted data through an injected " +
						"repository/port rather than opening a persistence database. Violations:\n" +
						violations.joinToString("\n")
				)
			}
		}

		@Test
		fun `app ViewModels and Compose depend on data ports rather than Room details`() {
			val appSourceDir = projectRoot.resolve("app/src/main")
			check(appSourceDir.isDirectory) {
				"Expected :app production sources at ${appSourceDir.absolutePath}"
			}

			val excludes = STANDARD_EXCLUDES + listOf("src/test", "src/androidTest")
			val violations = appSourceDir.walkTopDown()
				.onEnter { directory -> !directory.isInExcludedDirectory(excludes) }
				.filter { file ->
					file.isFile &&
						file.extension == "kt" &&
						!file.isInExcludedDirectory(excludes)
				}
				.filter { file ->
					val source = file.readText()
					COMPOSABLE_DECLARATION_PATTERN.containsMatchIn(source) ||
						VIEW_MODEL_DECLARATION_PATTERN.containsMatchIn(source)
				}
				.flatMap { file ->
					file.readLines().mapIndexedNotNull { index, line ->
						val isPersistenceReference =
							PRESENTATION_DATABASE_OPEN_PATTERN.containsMatchIn(line) ||
								APP_PRESENTATION_PERSISTENCE_IMPORT_PATTERNS.any {
									pattern -> pattern.containsMatchIn(line)
								}
						if (isPersistenceReference && !isCommentLine(line)) {
							"${file.relativeTo(appSourceDir)}:${index + 1}: $line"
						} else {
							null
						}
					}
				}
				.toList()

			if (violations.isNotEmpty()) {
				error(
					"App ViewModels and Compose presentation must depend on repositories/ports, " +
						"not Room APIs, DAOs, persistence entities, or databases. Violations:\n" +
						violations.joinToString("\n")
				)
			}
		}

		@Test
		fun `foundation data modules do not own Compose presentation`() {
			val modules = listOf("core/base", "core/common")
			val violations = modules.flatMap { module ->
				val sourceDir = projectRoot.resolve("$module/src/main")
				val buildFile = projectRoot.resolve("$module/build.gradle.kts")
				check(sourceDir.isDirectory) {
					"Expected :${module.replace('/', ':')} production sources at ${sourceDir.absolutePath}"
				}
				check(buildFile.isFile) {
					"Expected :${module.replace('/', ':')} build file at ${buildFile.absolutePath}"
				}

				val sourceViolations = findPatternMatching(
					sourceDir = sourceDir,
					pattern = COMPOSE_PRESENTATION_PATTERN,
					excludeDirs = STANDARD_EXCLUDES,
					skipComments = true,
				)
				val buildText = buildFile.readText()
				val dependencyViolations = FOUNDATION_COMPOSE_BUILD_MARKERS
					.filter(buildText::contains)
					.map { marker -> "$module/build.gradle.kts -> $marker" }
				sourceViolations + dependencyViolations
			}.toMutableList()

			val baseSourceDir = projectRoot.resolve("core/base/src/main")
			val oldPermissionPresentation = baseSourceDir.resolve(
				"java/com/adsamcik/tracker/shared/base/permission/ContextualPermissionRequest.kt"
			)
			oldPermissionPresentation
				.takeIf(File::exists)
				?.let { violations += "${it.relativeTo(projectRoot)} -> presentation source remains" }

			if (violations.isNotEmpty()) {
				error(
					":core:base and :core:common own data/runtime infrastructure and must not contain " +
						"Compose presentation. Move reusable UI into a presentation-owned module. " +
						"Violations:\n" + violations.joinToString("\n")
				)
			}
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
	inner class `Collected data worker startup fence` {
		@Test
		fun `every collected data worker owns a full startup and generation fence`() {
			val productionWorkers = projectRoot.walkTopDown()
				.onEnter { directory -> !directory.isInExcludedDirectory(STANDARD_EXCLUDES) }
				.filter { file ->
					file.isFile &&
						file.extension == "kt" &&
						"/src/main/" in file.invariantSeparatorsPath &&
						"CoroutineWorker" in file.readText()
				}
				.associate { file -> file.relativeTo(projectRoot).invariantSeparatorsPath to file.readText() }

			val collectedWorkers = productionWorkers
				.filterValues(COLLECTED_DATA_WORKER_PERSISTENCE_PATTERN::containsMatchIn)
			val violations = buildList {
				val unreviewed = collectedWorkers.keys - COLLECTED_DATA_WORKER_PATHS
				unreviewed.sorted().mapTo(this) { path ->
					"$path -> collected persistence worker is not in the reviewed fence set"
				}
				val missing = COLLECTED_DATA_WORKER_PATHS - collectedWorkers.keys
				missing.sorted().mapTo(this) { path ->
					"$path -> reviewed collected persistence worker is missing or no longer discoverable"
				}

				collectedWorkers.forEach { (path, source) ->
					listOf(
						"TrackingStartupGate",
						"TrackingStartupResult.Ready",
						"TrackingStartupResult.RetryableFailure",
						"TrackingStartupResult.Blocked",
						"currentGeneration",
						"isReady",
					).filterNot(source::contains)
						.mapTo(this) { marker -> "$path -> missing startup fence marker `$marker`" }
					if ("Provider<" !in source && "Lazy<" !in source) {
						add("$path -> collected persistence must be injected through Provider/Lazy")
					}
					EAGER_COLLECTED_DATA_PROPERTY_PATTERN.findAll(source).forEach { match ->
						add(
							"$path -> collected persistence `${match.groupValues[1]}` is injected eagerly",
						)
					}
					if (APP_DATABASE_SINGLETON_OPEN_PATTERN.containsMatchIn(source)) {
						add("$path -> worker must use its gated AppDatabase Provider, not a singleton open")
					}

					val reconcileIndex = source.indexOf("trackingStartupGate.reconcile()")
					val readinessGuardIndex = listOf(
						"trackingStartupGate.isReady",
						"isReadyGeneration(",
						"requireReadyGeneration(",
					).map(source::indexOf).filter { it >= 0 }.minOrNull() ?: -1
					val generationIndex = source.indexOf("trackingStartupGate.currentGeneration")
					PROVIDER_PROPERTY_PATTERN.findAll(source).forEach { declaration ->
						val providerName = declaration.groupValues[1]
						val resolutionPattern = Regex("""\b${Regex.escape(providerName)}\.get\(\)""")
						resolutionPattern.findAll(source).forEach { resolution ->
							val resolutionIndex = resolution.range.first
							if (
								reconcileIndex !in 0 until resolutionIndex ||
								readinessGuardIndex !in 0 until resolutionIndex ||
								generationIndex !in 0 until reconcileIndex
							) {
								add(
									"$path -> `$providerName.get()` resolves before Ready + generation fencing",
								)
							}
						}
					}
					if (!RETRYABLE_STARTUP_OUTCOME_PATTERN.containsMatchIn(source)) {
						add("$path -> retryable startup failure must map to the worker retry outcome")
					}
					if (!BLOCKED_STARTUP_OUTCOME_PATTERN.containsMatchIn(source)) {
						add("$path -> blocked startup must map to a terminal worker outcome")
					}
				}
			}

			if (violations.isNotEmpty()) {
				error(
					"Workers that can read or write collected persistence must resolve it lazily, " +
						"observe full TrackingStartupGate.Ready, and fence the process generation. " +
						"Only retryable startup failures may request WorkManager backoff.\n" +
						violations.joinToString("\n"),
				)
			}
		}

		@Test
		fun `provider-only cleanup workers stay outside the collected data gate`() {
			PROVIDER_ONLY_CLEANUP_EXEMPTIONS.forEach { (path, evidence) ->
				val source = projectRoot.resolve(path).readText()
				check("TrackingStartupGate" !in source) {
					"$path unexpectedly joined the collected-data gate; review its cleanup ownership"
				}
				check(evidence in source) {
					"$path must document why it is safe before Tracker Room startup"
				}
			}
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

	@Nested
	inner class `Power claim safety` {
		@Test
		fun `battery optimization is not presented as a background reliability guarantee in any locale`() {
			val resourceDirectory = projectRoot.resolve("app/src/main/res")
			val defaultResources = resourceDirectory.resolve("values/strings.xml").readText()
			fun String.definesStringResource(key: String): Boolean =
				Regex("""<string\b[^>]*\bname\s*=\s*"${Regex.escape(key)}"[^>]*>""")
					.containsMatchIn(this)

			listOf(
				"auto-tracking stays reliable",
				"Reliable background tracking",
				"can run reliably",
				"keep it running",
			).forEach { unsupportedClaim ->
				check(unsupportedClaim !in defaultResources) {
					"Battery exemption cannot guarantee provider, Android, or OEM reliability: " +
						unsupportedClaim
				}
			}

			val guardedKeys = setOf(
				"setup_background_access_subtitle",
				"background_reliability_title",
				"background_reliability_exempt_desc",
				"background_reliability_optimized_desc",
			)
			check(guardedKeys.all { key -> defaultResources.definesStringResource(key) }) {
				"The truthful default background-reliability copy must define every guarded key"
			}
			val staleLocaleOverrides = resourceDirectory.listFiles().orEmpty()
				.asSequence()
				.filter { directory -> directory.isDirectory && directory.name.startsWith("values-") }
				.map { directory -> directory.resolve("strings.xml") }
				.filter(File::isFile)
				.flatMap { stringsFile ->
					val localizedResources = stringsFile.readText()
					guardedKeys.asSequence()
						.filter { key -> localizedResources.definesStringResource(key) }
						.map { key -> "${stringsFile.parentFile.name}/strings.xml -> $key" }
				}
				.toList()

			staleLocaleOverrides.shouldBeEmpty()
		}
	}

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

		private val COLLECTED_DATA_WORKER_PATHS = setOf(
			"app/src/main/java/com/adsamcik/tracker/app/maintenance/RetentionPipelineWorker.kt",
			"app/src/main/java/com/adsamcik/tracker/maintenance/DataRetentionWorker.kt",
			"app/src/main/java/com/adsamcik/tracker/maintenance/DatabaseMaintenanceWorker.kt",
			"domain/points/src/main/java/com/adsamcik/tracker/points/work/PointsWorker.kt",
			"feature/import-export/src/main/java/com/adsamcik/tracker/impexp/exporter/automation/ExportPlanWorker.kt",
			"feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/worker/ImportWorker.kt",
			"sensor/activity/src/main/java/com/adsamcik/tracker/activity/ActivityRecognitionWorker.kt",
			"stats/data/src/main/java/com/adsamcik/tracker/stats/data/worker/AchievementWorker.kt",
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/worker/DailySummaryMaterializationWorker.kt",
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/worker/HistoricalTrajectoryReconstructionWorker.kt",
			"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/worker/PendingSignalDrainWorker.kt",
		)

		private val COLLECTED_DATA_WORKER_PERSISTENCE_PATTERN = Regex(
			"""\b(?:AppDatabase|PointsDatabase|[A-Za-z0-9_]+Dao|PersistenceProcessor)\b""",
		)

		private val PROVIDER_PROPERTY_PATTERN = Regex(
			"""private val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*Provider<""",
		)

		private val EAGER_COLLECTED_DATA_PROPERTY_PATTERN = Regex(
			"""private val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?:AppDatabase|PointsDatabase|[A-Za-z0-9_]+Dao|PersistenceProcessor)\b""",
		)

		private val APP_DATABASE_SINGLETON_OPEN_PATTERN = Regex(
			"""\bAppDatabase\.database\s*\(""",
		)

		private val RETRYABLE_STARTUP_OUTCOME_PATTERN = Regex(
			"""TrackingStartupResult\.RetryableFailure\s*->[^\n]*(?:Result\.retry\(\)|WorkOutcome\.RETRY)""",
		)

		private val BLOCKED_STARTUP_OUTCOME_PATTERN = Regex(
			"""TrackingStartupResult\.Blocked\s*->(?:\s*\{)?(?:(?!Result\.retry\(\)|WorkOutcome\.RETRY)[\s\S]){0,240}(?:Result\.(?:success|failure)\(.*?\)|WorkOutcome\.(?:COMPLETE|FAILED))""",
		)

		private val PROVIDER_ONLY_CLEANUP_EXEMPTIONS = mapOf(
			"core/base/src/main/java/com/adsamcik/tracker/shared/base/database/migration/DatabaseMigrationBackupCleanupWorker.kt" to
				"deletes only migration-backup files and never opens Tracker Room",
			"sensor/activity/src/main/java/com/adsamcik/tracker/activity/api/registration/ActivityRegistrationCleanupWorker.kt" to
				"owns only the no-backup cleanup",
		)

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

		private val FEATURE_DATABASE_IMPORT_PATTERN = Regex(
			"""^\s*import\s+com\.adsamcik\.tracker\.shared\.base\.database(?:\.|$)"""
		)

		private val VIEW_MODEL_DECLARATION_PATTERN = Regex(
			"""\b(?:class|object)\s+[A-Za-z_][A-Za-z0-9_]*ViewModel\b"""
		)

		private val COMPOSABLE_DECLARATION_PATTERN = Regex("""@Composable\b""")

		private val FEATURE_VIEWMODEL_PERSISTENCE_IMPORT_PATTERNS = listOf(
			FEATURE_DATABASE_IMPORT_PATTERN,
			Regex("""^\s*import\s+androidx\.room(?:\.|$)"""),
			Regex(
				"""^\s*import\s+com\.adsamcik\.tracker\.shared\.base\.data\.SessionActivity\s*$"""
			),
		)

		private val PRESENTATION_DATABASE_OPEN_PATTERN = Regex(
			"""\b(?:AppDatabase|LogDatabase)\.database\s*\("""
		)

		private val FEATURE_UI_PERSISTENCE_IMPORT_PATTERNS = listOf(
			FEATURE_DATABASE_IMPORT_PATTERN,
			Regex(
				"""^\s*import\s+com\.adsamcik\.tracker\.shared\.base\.data\.SessionActivity\s*$"""
			),
		)

		private val APP_PRESENTATION_PERSISTENCE_IMPORT_PATTERNS = listOf(
			Regex(
				"""^\s*import\s+com\.adsamcik\.tracker\.shared\.base\.database\.(?:dao|data)(?:\.|$)"""
			),
			Regex("""^\s*import\s+androidx\.room(?:\.|$)"""),
			Regex(
				"""^\s*import\s+com\.adsamcik\.tracker\.shared\.base\.data\.""" +
					"""(?:SessionActivity|NativeSessionActivity)\s*$"""
			),
			Regex(
				"""^\s*import\s+com\.adsamcik\.tracker\.logger\.""" +
					"""(?:LogData|CrashData|LogDatabase|GenericLogDao|CrashDataDao|CrashExporter)\s*$"""
			),
		)

		private val COMPOSE_PRESENTATION_PATTERN = Regex(
			"""\b(?:androidx\.compose|androidx\.activity\.compose)\.|@Composable\b"""
		)

		private val FOUNDATION_COMPOSE_BUILD_MARKERS = listOf(
			"""id("tracker.android.compose")""",
			"""alias(libs.plugins.compose""",
			"""project(":core:ui")""",
			"libs.compose.",
			"libs.accompanist.permissions",
			"libs.activity.compose",
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
		)

		// LEGACY direct-`Dispatchers.IO/Main/Default` callsites that pre-date
		// the project-wide `DispatchersProvider` rule. New code must NOT join
		// this list — inject `DispatchersProvider` instead. Tracked as carried-
		// over tech debt; intentionally short to make every entry visible.
		private val LEGACY_DISPATCHERS_DIRECT_USE_ALLOWLIST = emptyList<String>()

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
