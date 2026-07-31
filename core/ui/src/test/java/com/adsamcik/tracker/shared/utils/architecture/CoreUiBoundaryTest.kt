package com.adsamcik.tracker.shared.utils.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keeps :core:ui a presentation-only module.
 *
 * Runtime contracts, tracker state, process-wide permission orchestration,
 * workers, and persistence-aware formatting all have dedicated owners and
 * must not drift back into the shared Compose module. Screen-local permission
 * rationale and Activity Result presentation are reusable UI and may live here.
 */
class CoreUiBoundaryTest {
	private val moduleDir = resolveModuleDirectory()
	private val mainDir = File(moduleDir, "src/main")
	private val gradleFile = File(moduleDir, "build.gradle.kts")
	private val manifestFile = File(mainDir, "AndroidManifest.xml")

	@Test
	fun `production sources contain presentation code only`() {
		val forbiddenImports = listOf(
			"android.app.Service",
			"android.content.BroadcastReceiver",
			"androidx.datastore.",
			"androidx.lifecycle.ProcessLifecycleOwner",
			"androidx.room.",
			"androidx.work.",
			"com.adsamcik.tracker.logging.",
			"com.adsamcik.tracker.shared.base.",
			"com.adsamcik.tracker.shared.preferences.",
			"com.adsamcik.tracker.tracker.",
		)
		val forbiddenSymbols = listOf(
			"ModuleInitializer",
			"PermissionData",
			"PermissionManager",
			"TrackerSessionChannel",
			"TrackerUpdateReceiver",
		)
		val violations = mutableListOf<String>()

		mainDir.walkTopDown()
			.filter { it.isFile && it.extension in setOf("kt", "java") }
			.forEach { file ->
				file.readLines().forEachIndexed { index, line ->
					val trimmed = line.trim()
					if (trimmed.startsWith("import ")) {
						forbiddenImports
							.filter(trimmed::contains)
							.forEach { violations += "${file.name}:${index + 1} -> $trimmed" }
					}
					forbiddenSymbols
						.filter { symbol -> Regex("""\b${Regex.escape(symbol)}\b""").containsMatchIn(line) }
						.forEach { violations += "${file.name}:${index + 1} -> $it" }
				}
			}

		assertTrue(
			violations.isEmpty(),
			":core:ui must contain only reusable presentation code:\n" +
				violations.joinToString("\n"),
		)
	}

	@Test
	fun `legacy runtime package paths stay absent`() {
		val forbiddenPaths = listOf(
			"java/com/adsamcik/tracker/shared/utils/module",
			"java/com/adsamcik/tracker/shared/utils/permission",
			"java/com/adsamcik/tracker/shared/utils/extension/SafetyExtensions.kt",
			"java/com/adsamcik/tracker/shared/utils/extension/StringExtensions.kt",
			"java/com/adsamcik/tracker/shared/utils/extension/WorkExtensions.kt",
		)

		forbiddenPaths.forEach { relativePath ->
			val target = File(mainDir, relativePath)
			val containsSource = when {
				target.isFile -> true
				target.isDirectory -> target.walkTopDown().any(File::isFile)
				else -> false
			}
			assertFalse(
				containsSource,
				"Runtime ownership must not return to :core:ui: $relativePath",
			)
		}
	}

	@Test
	fun `module has no project or runtime infrastructure dependencies`() {
		val buildText = gradleFile.readText()
		val forbiddenDependencies = listOf(
			"project(",
			"libs.androidx.datastore.",
			"libs.androidx.room.",
			"libs.androidx.work.",
			"libs.google.play.services.",
			"libs.google.material",
		)

		forbiddenDependencies.forEach { dependency ->
			assertFalse(
				buildText.contains(dependency),
				":core:ui must not depend on runtime infrastructure: $dependency",
			)
		}
	}

	@Test
	fun `module manifest declares no runtime infrastructure`() {
		val manifestText = manifestFile.readText()
		listOf("<uses-permission", "<service", "<receiver", "<provider").forEach { tag ->
			assertFalse(
				manifestText.contains(tag),
				":core:ui must not contribute runtime infrastructure: $tag",
			)
		}
	}

	private fun resolveModuleDirectory(): File {
		val candidates = listOf(File("."), File("core/ui"))
		return candidates.firstOrNull { candidate ->
			File(candidate, "build.gradle.kts").isFile &&
				File(candidate, "src/main").isDirectory &&
				File(candidate, "src/main/AndroidManifest.xml").isFile
		} ?: error(
			":core:ui module directory not found; checked: " +
				candidates.joinToString { it.absolutePath },
		)
	}
}
