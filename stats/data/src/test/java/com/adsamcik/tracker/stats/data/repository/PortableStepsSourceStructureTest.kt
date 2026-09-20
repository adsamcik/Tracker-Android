package com.adsamcik.tracker.stats.data.repository

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

	@Test
	fun `portable full clear stages maximum authority instead of heap aggregating it`() {
		val fullClear = source(
			"core/base/src/main/java/com/adsamcik/tracker/shared/base/database/" +
				"ImportedPortableStepsCountDomainFullClear.kt",
		)
		val production = fullClear.substring(
			fullClear.indexOf(
				"internal suspend fun AppDatabase.preserveImportedPortableCountDomainFullClearFences",
			),
			fullClear.indexOf(
				"private suspend fun AppDatabase.authenticatedImportedSessionBindingsForFullClear",
			),
		)

		assertTrue("AuthenticatedFullClearOwnerFenceStaging(" in production)
		assertFalse("AuthenticatedFullClearOwnerFenceAccumulator" in fullClear)
		assertFalse("sortedMapOf<Long, PortableCountDomainOwnerRevisionV2>" in fullClear)
		assertFalse(".fences()" in production)
		assertTrue("CREATE TEMP TABLE \$OWNER_TABLE" in fullClear)
		assertTrue("CREATE TEMP TABLE \$REVISION_TABLE" in fullClear)
		assertTrue("ORDER BY owner_kind, owner_identity LIMIT ?" in fullClear)
		assertTrue("MAX_FULL_CLEAR_OWNER_FENCES = 262_144" in fullClear)
		assertTrue("MAX_FULL_CLEAR_OWNER_REVISIONS =" in fullClear)
		assertTrue("staging.close()" in production)
	}

	private fun source(relativePath: String): String = projectRoot.resolve(relativePath).readText()
}
