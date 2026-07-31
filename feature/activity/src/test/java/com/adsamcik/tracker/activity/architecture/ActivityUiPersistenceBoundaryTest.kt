package com.adsamcik.tracker.activity.architecture

import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ActivityUiPersistenceBoundaryTest {
	@Test
	fun `activity UI depends on its repository port instead of Room`() {
		assertUiHasNoPersistenceImports(resolveModuleDirectory())
	}

	@Test
	fun `repository contract is feature owned and Room entity stays in adapter`() {
		val moduleDir = resolveModuleDirectory()
		val sourceRoot = File(moduleDir, "src/main/java")
		val contractFile = File(
			sourceRoot,
			"com/adsamcik/tracker/activity/data/SessionActivityRepository.kt",
		)
		check(contractFile.isFile) { "Session activity repository contract is missing" }
		val contract = contractFile.readText()

		assertTrue(contract.contains("data class SessionActivityItem"))
		assertTrue(contract.contains("data class CreateSessionActivityCommand"))
		assertTrue(contract.contains("data class UpdateSessionActivityCommand"))
		assertTrue(!contract.contains("com.adsamcik.tracker.shared.base"))

		val entityLeaks = sourceRoot.walkTopDown()
			.filter {
				it.isFile &&
					it.extension == "kt" &&
					it.name != "RoomSessionActivityRepository.kt"
			}
			.flatMap { file ->
				file.readLines().mapIndexedNotNull { index, line ->
					if (
						line.contains("shared.base.data.SessionActivity") ||
						Regex("""\bSessionActivity\b""").containsMatchIn(line)
					) {
						"${file.name}:${index + 1} -> ${line.trim()}"
					} else {
						null
					}
				}
			}
			.toList()

		assertTrue(
			entityLeaks.isEmpty(),
			"Room SessionActivity leaked outside its adapter:\n${entityLeaks.joinToString("\n")}",
		)
	}

	private fun resolveModuleDirectory(): File {
		val candidates = listOf(File("."), File("feature/activity"))
		return candidates.firstOrNull {
			File(it, "build.gradle.kts").isFile && File(it, "src/main").isDirectory
		} ?: error(":feature:activity module directory not found")
	}
}

private fun assertUiHasNoPersistenceImports(moduleDir: File) {
	val violations = File(moduleDir, "src/main/java").walkTopDown()
		.filter { it.isFile && it.extension == "kt" && "/ui/" in it.invariantSeparatorsPath }
		.flatMap { file ->
			file.readLines().mapIndexedNotNull { index, line ->
				if (
					line.contains("shared.base.database") ||
					line.contains("shared.base.data.SessionActivity") ||
					Regex("""\bSessionActivity\b""").containsMatchIn(line) ||
					Regex("""\bAppDatabase\b|\b[A-Z][A-Za-z0-9]*Dao\b""").containsMatchIn(line)
				) {
					"${file.name}:${index + 1} -> ${line.trim()}"
				} else {
					null
				}
			}
		}
		.toList()
	assertTrue(violations.isEmpty(), violations.joinToString("\n"))
}
