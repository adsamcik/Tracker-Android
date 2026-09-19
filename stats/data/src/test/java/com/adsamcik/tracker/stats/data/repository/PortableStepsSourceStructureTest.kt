package com.adsamcik.tracker.stats.data.repository

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PortableStepsSourceStructureTest {
	private val projectRoot: File by lazy {
		generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
			.first { it.resolve("settings.gradle.kts").isFile }
	}

	@Test
	fun `v2 export declarations remain accessible at their owning class boundaries`() {
		val backend = source(
			"feature/import-export/src/main/java/com/adsamcik/tracker/impexp/exporter/" +
				"AmbientStepsPortableSourceBackend.kt",
		)
		val reader = source(
			"stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/" +
				"ImportedStepsProductReader.kt",
		)
		val roomReader = source(
			"stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/" +
				"PortableStepsRoomReader.kt",
		)
		val ambientExporter = source(
			"stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/" +
				"RoomExportPortableAmbientSteps.kt",
		)

		assertTrue(Regex("""(?m)^\tsuspend fun exportV2\(""").containsMatchIn(backend))
		assertTrue(
			Regex("""(?m)^\tsuspend fun exportV2InTransaction\(""").containsMatchIn(reader),
		)
		assertTrue(
			Regex("""(?m)^internal sealed interface PortableStepsV2Snapshot""")
				.containsMatchIn(roomReader),
		)
		assertTrue("import javax.inject.Singleton" in ambientExporter)
		assertTrue(
			Regex("""@Singleton\s+internal class RoomExportPortableAmbientStepsV2""")
				.containsMatchIn(ambientExporter),
		)
	}

	@Test
	fun `portable test classes do not declare duplicate test method signatures`() {
		val testFiles = listOf(
			"core/model/src/androidHostTest/kotlin/com/adsamcik/tracker/shared/model/steps/" +
				"portable/PortableCountDomainGraphV2Test.kt",
			"feature/import-export/src/test/java/com/adsamcik/tracker/impexp/exporter/" +
				"PortableAmbientStepsExporterTest.kt",
			"feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/" +
				"PortableAmbientStepsFileImportTest.kt",
			"feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/" +
				"PortableStepsFileImportTest.kt",
			"stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/" +
				"RoomImportedAmbientStepsTransferTest.kt",
		).map(projectRoot::resolve)
		val testName = Regex(
			"""(?s)@Test\s+(?:@Suppress\([^\n]+\)\s+)?fun\s+(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)\s*\(""",
		)
		val duplicates = testFiles.asSequence()
			.mapNotNull { file ->
				val names = testName.findAll(file.readText()).map { it.groupValues[1] }.toList()
				val repeated = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
				repeated.takeIf { it.isNotEmpty() }?.let {
					"${file.relativeTo(projectRoot).invariantSeparatorsPath}:$it"
				}
			}
			.toList()

		assertEquals(emptyList(), duplicates)
	}

	private fun source(relativePath: String): String = projectRoot.resolve(relativePath).readText()
}
