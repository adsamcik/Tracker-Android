package com.adsamcik.tracker.tracker.api

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse

class TrackerApiBoundaryTest {
	private val projectRoot: File by lazy {
		var dir = File(System.getProperty("user.dir") ?: ".")
		while (!dir.resolve("settings.gradle.kts").exists()) {
			dir = dir.parentFile ?: return@lazy File(System.getProperty("user.dir") ?: ".")
		}
		dir
	}

	private val apiModuleDir: File
		get() = projectRoot.resolve("tracker/api-module")

	@Test
	fun `tracker api sources do not import stats engine`() {
		val violations = apiModuleDir.resolve("src/main").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.flatMap { file ->
				file.readLines().mapIndexedNotNull { index, line ->
					if (line.trim().startsWith("import com.adsamcik.tracker.stats.engine")) {
						"${file.relativeTo(projectRoot)}:${index + 1}: $line"
					} else {
						null
					}
				}
			}
			.toList()

		violations.shouldBeEmpty()
	}

	@Test
	fun `tracker api Gradle file does not export stats engine`() {
		val gradleFile = apiModuleDir.resolve("build.gradle.kts")
		val exportedStatsEngine = Regex("""\bapi\s*\(\s*project\(":stats:engine"\)\s*\)""")
			.containsMatchIn(gradleFile.readText())

		assertFalse(exportedStatsEngine, "tracker/api-module must not export :stats:engine")
	}

	@Test
	fun `implementation heavy background runtime is outside tracker api module`() {
		val implementationFiles = listOf(
			apiModuleDir.resolve("src/main/java/com/adsamcik/tracker/tracker/api/BackgroundTrackingApi.kt"),
			apiModuleDir.resolve("src/main/java/com/adsamcik/tracker/tracker/api/StepActivityCorroborator.kt"),
		).filter(File::exists)

		implementationFiles.map { it.relativeTo(projectRoot).path }.shouldBeEmpty()
	}

	@Test
	fun `feature modules do not depend on mutable tracker controller`() {
		val violations = projectRoot.resolve("feature").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.flatMap { file ->
				file.readLines().mapIndexedNotNull { index, line ->
					if (line.trim() ==
						"import com.adsamcik.tracker.tracker.controller.TrackerServiceController"
					) {
						"${file.relativeTo(projectRoot)}:${index + 1}: $line"
					} else {
						null
					}
				}
			}
			.toList()

		violations.shouldBeEmpty()
	}

	@Test
	fun `tracker state reader does not expose mutable Room session entities`() {
		val readerFile = apiModuleDir.resolve(
			"src/main/java/com/adsamcik/tracker/tracker/controller/TrackerServiceController.kt"
		)
		val readerSource = readerFile.readText()
			.substringAfter("interface TrackerStateReader")
			.substringBefore("interface TrackerServiceController")
		val stateFlowProperties = Regex(
			"""val\s+(\w+)\s*:\s*StateFlow\s*<\s*([^>]+)\s*>"""
		).findAll(readerSource)
			.associate { match -> match.groupValues[1] to match.groupValues[2].replace(" ", "") }

		stateFlowProperties["sessionFlow"] shouldBe "TrackerSessionSnapshot?"
		stateFlowProperties["lastSessionFlow"] shouldBe "TrackerSessionSnapshot?"
		stateFlowProperties
			.filterValues { type -> type.removeSuffix("?").substringAfterLast('.') == "TrackerSession" }
			.keys
			.shouldBeEmpty()
	}
}
