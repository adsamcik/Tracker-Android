package com.adsamcik.tracker.activity

import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

class ActivityApiBoundaryTest {
	private val projectRoot: File by lazy {
		var dir = File(System.getProperty("user.dir") ?: ".")
		while (!dir.resolve("settings.gradle.kts").exists()) {
			dir = dir.parentFile ?: return@lazy File(System.getProperty("user.dir") ?: ".")
		}
		dir
	}

	private val apiModuleDir: File
		get() = projectRoot.resolve("sensor/activity-api")

	@Test
	fun `activity api sources do not import Google Play Services`() {
		val violations = apiModuleDir.resolve("src/main").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.flatMap { file ->
				file.readLines().mapIndexedNotNull { index, line ->
					if (line.trim().startsWith("import com.google.android.gms")) {
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
	fun `activity api does not depend on legacy core base models`() {
		val sourceViolations = apiModuleDir.resolve("src/main").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.flatMap { file ->
				file.readLines().mapIndexedNotNull { index, line ->
					if (line.trim().startsWith("import com.adsamcik.tracker.shared.base")) {
						"${file.relativeTo(projectRoot)}:${index + 1}: $line"
					} else {
						null
					}
				}
			}
			.toList()
		val buildFile = apiModuleDir.resolve("build.gradle.kts").readText()

		sourceViolations.shouldBeEmpty()
		assertFalse(
			buildFile.contains("project(\":core:base\")"),
			"Activity API must stay independent from Room-backed and GMS-transitive core models",
		)
	}

	@Test
	fun `activity api Gradle dependencies stay GMS-free and engine-free`() {
		val buildFile = apiModuleDir.resolve("build.gradle.kts").readText()
		val dependencyDeclarations = buildFile.lineSequence()
			.map(String::trim)
			.filter { line ->
				line.startsWith("api(") ||
					line.startsWith("implementation(") ||
					line.startsWith("compileOnly(") ||
					line.startsWith("runtimeOnly(")
			}
			.joinToString("\n")
		val gmsAlias = Regex(
			pattern = """libs\.[A-Za-z0-9_.-]*(gms|play\.services|playServices)""",
			option = RegexOption.IGNORE_CASE,
		)

		assertFalse(
			gmsAlias.containsMatchIn(dependencyDeclarations) ||
				dependencyDeclarations.contains("com.google.android.gms"),
			"Activity API must not depend on Google Play Services",
		)
		assertFalse(
			dependencyDeclarations.contains("project(\":stats:engine\")"),
			"Activity API must depend on stats contracts, not stats engine implementation",
		)
	}

	@Test
	fun `activity request contracts do not contain callbacks`() {
		val requestData = apiModuleDir
			.resolve("src/main/java/com/adsamcik/tracker/activity/ActivityRequestData.kt")
			.readText()

		assertFalse(
			requestData.contains("callback", ignoreCase = true),
			"Activity request contracts must expose updates through Flow, not callbacks",
		)
	}
}
