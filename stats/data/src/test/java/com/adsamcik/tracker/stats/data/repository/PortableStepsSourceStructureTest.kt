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
		val captureFences = source(
			"core/base/src/main/java/com/adsamcik/tracker/shared/base/database/steps/" +
				"imported/StepsFullClearFences.kt",
		)
		val graphDao = source(
			"core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/" +
				"ImportedPortableStepsCountDomainDao.kt",
		)

		assertTrue("AuthenticatedFullClearOwnerFenceStaging(" in production)
		assertFalse("AuthenticatedFullClearOwnerFenceAccumulator" in fullClear)
		assertFalse("sortedMapOf<Long, PortableCountDomainOwnerRevisionV2>" in fullClear)
		assertFalse(".fences()" in production)
		assertTrue("CREATE TEMP TABLE \$BINDING_TABLE" in fullClear)
		assertTrue("bindingPageForFullClear" in fullClear)
		assertTrue("fun bindingPageForFullClear" in graphDao)
		assertFalse("allBindingsForFullClear" in graphDao)
		assertTrue("CREATE TEMP TABLE \$OWNER_TABLE" in fullClear)
		assertTrue("CREATE TEMP TABLE \$REVISION_TABLE" in fullClear)
		assertTrue("ORDER BY owner_kind, owner_identity LIMIT ?" in fullClear)
		assertTrue("maximumOwnerCount = null" in production)
		assertTrue("maximumOwnerRevisionCount = null" in production)
		assertFalse("MAX_FULL_CLEAR_OWNER_FENCES" in fullClear)
		assertFalse("MAX_FULL_CLEAR_OWNER_REVISIONS" in fullClear)
		assertFalse("MAX_STEPS_FULL_CLEAR_FENCES" in captureFences)
		assertTrue("readStepsFenceOrNull(sqlite, digest)" in captureFences)
		assertTrue("staging.close()" in production)
	}

	@Test
	fun `portable full clear stages compact session identity instead of retained products`() {
		val fullClear = source(
			"core/base/src/main/java/com/adsamcik/tracker/shared/base/database/" +
				"ImportedPortableStepsCountDomainFullClear.kt",
		)
		val sessionPaging = fullClear.substring(
			fullClear.indexOf(
				"private suspend fun AppDatabase.authenticatedImportedSessionBindingsForFullClear",
			),
			fullClear.indexOf(
				"private suspend fun AppDatabase.authenticatedImportedAmbientBindingsForFullClear",
			),
		)
		val stagedType = fullClear.substring(
			fullClear.indexOf("private data class StagedFullClearSessionProduct"),
			fullClear.indexOf("private data class StagedFullClearOwner"),
		)

		assertTrue("staging.addSessionProduct(product, authenticated, binding != null)" in sessionPaging)
		assertFalse("Map<String, RetainedImportedStepsEntry>" in fullClear)
		assertFalse("linkedMapOf<String, RetainedImportedStepsEntry>" in fullClear)
		assertTrue("CREATE TEMP TABLE \$SESSION_PRODUCT_TABLE" in fullClear)
		assertTrue("content_checksum TEXT NOT NULL" in fullClear)
		assertTrue("source_format TEXT NOT NULL" in fullClear)
		assertTrue("source_receipt_identity TEXT" in fullClear)
		assertTrue("require(identities.size <= FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE)" in fullClear)
		assertTrue("WHERE product_identity IN (\$placeholders)" in fullClear)
		assertTrue("forEachEntryForRetentionInTransaction" in sessionPaging)
		assertFalse("readEntriesForRetentionInTransaction(page.map" in fullClear)
		assertFalse("SESSION_FULL_CLEAR_PAGE_SIZE" in fullClear)
		assertFalse("RetainedImportedStepsEntry" in stagedType)
		assertFalse("PortableStepsRun" in stagedType)
		assertFalse("StepFactRevision" in stagedType)
	}

	@Test
	fun `portable byte header scanner bounds known fields while streaming unknown payloads`() {
		val dispatch = source(
			"feature/import-export/src/main/java/com/adsamcik/tracker/impexp/portable/" +
				"PortableStepsJsonVersionDispatch.kt",
		)
		val fileImport = source(
			"feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/" +
				"PortableStepsFileImport.kt",
		)

		assertTrue("PortableRootHeaderScanner" in dispatch)
		assertTrue("else -> skipValue(ROOT_DEPTH)" in dispatch)
		assertTrue("readString(maximumEncodedBytes = null, capture = false)" in dispatch)
		assertTrue("MAX_NESTING_DEPTH = 32" in dispatch)
		assertTrue("MAX_NAME_BYTES = 384" in dispatch)
		assertFalse("JsonReader" in dispatch)
		assertTrue("when (portableStepsSchemaVersion(bytes))" in fileImport)
		assertTrue("codec.decode(bytes)" in fileImport)
		assertTrue("PortableStepsJsonV2Codec().decode(bytes)" in fileImport)
	}

	private fun source(relativePath: String): String = projectRoot.resolve(relativePath).readText()
}
