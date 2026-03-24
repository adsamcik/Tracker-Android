package com.adsamcik.tracker.shared.map

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class SmapModuleContractTest {

	@Test
	fun `smap currently exposes no kotlin or java source api`() {
		val mainDir = resolveExistingFile("src\\main", "smap\\src\\main")
		val sourceFiles = mainDir
			.walkTopDown()
			.filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
			.toList()

		assertTrue(
			sourceFiles.isEmpty(),
			"Expected smap to remain a build-only module placeholder, found source files: ${sourceFiles.joinToString()}",
		)
	}

	@Test
	fun `smap build stays ready for unit tests`() {
		val buildFile = resolveExistingFile("build.gradle.kts", "smap\\build.gradle.kts")
		val buildScript = buildFile.readText()

		assertTrue("testImplementation(libs.junit5.jupiter)" in buildScript)
		assertTrue("testImplementation(libs.kotlin.test)" in buildScript)
		assertTrue("tasks.withType<Test>().configureEach" in buildScript)
	}

	private fun resolveExistingFile(vararg candidates: String): File {
		return candidates
			.asSequence()
			.map(::File)
			.firstOrNull(File::exists)
			?: File(candidates.last())
	}
}
