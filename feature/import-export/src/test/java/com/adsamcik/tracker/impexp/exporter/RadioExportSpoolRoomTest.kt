package com.adsamcik.tracker.impexp.exporter

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.impexp.portable.radioChildDigest
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiGapEntity
import com.adsamcik.tracker.stats.api.repository.AmbientCellGap
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientWifiFact
import com.adsamcik.tracker.stats.api.repository.AmbientWifiGap
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCell
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifi
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangePage
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellSink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiSink
import io.mockk.coEvery
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real export/Room/codec/spool boundary; source authority is supplied by explicit doubles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioExportSpoolRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var context: Context
	private lateinit var cacheRoot: File
	private val spoolDirectory get() = File(cacheRoot, "portable-export-spool")

	@Before fun setUp() = runBlocking {
		val application = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(application)
		cacheRoot = File(application.cacheDir, "radio-spool-room-${UUID.randomUUID()}")
		check(cacheRoot.mkdir())
		context = object : ContextWrapper(application) {
			override fun getCacheDir(): File = cacheRoot
		}
		for (kind in Kind.entries) seedGap(kind, GAP)
	}

	@After fun close() {
		database.close()
		check(cacheRoot.deleteRecursively())
	}

	@Test fun `a blocked destination does not prevent a separate Room writer for either source`() = runBlocking {
		for (kind in Kind.entries) {
			val copyStarted = CountDownLatch(1)
			val finishCopy = CountDownLatch(1)
			val writerFinished = CountDownLatch(1)
			val exportResult = AtomicReference<ExportResult>()
			val exportFailure = AtomicReference<Throwable>()
			val writerFailure = AtomicReference<Throwable>()
			val output = object : ByteArrayOutputStream() {
				override fun write(buffer: ByteArray, offset: Int, length: Int) {
					assertFalse(database.inTransaction())
					copyStarted.countDown()
					check(finishCopy.await(5, TimeUnit.SECONDS))
					super.write(buffer, offset, length)
				}
			}
			val exporting = thread(name = "radio-$kind-copy") {
				try {
					exportResult.set(runBlocking { export(kind, output) })
				} catch (failure: Throwable) { exportFailure.set(failure) }
			}
			var writing: Thread? = null
			try {
				assertTrue(copyStarted.await(5, TimeUnit.SECONDS))
				writing = thread(name = "radio-$kind-during-copy-write") {
					try { runBlocking { database.withTransaction { seedGap(kind, SECOND_GAP) } } }
					catch (failure: Throwable) { writerFailure.set(failure) }
					finally { writerFinished.countDown() }
				}
				assertTrue(writerFinished.await(5, TimeUnit.SECONDS))
				assertNull(writerFailure.get())
				assertNull(exportResult.get())
			} finally {
				finishCopy.countDown()
				exporting.join(5_000)
				writing?.join(5_000)
			}
			assertFalse(exporting.isAlive)
			assertFalse(writing?.isAlive == true)
			assertNull(exportFailure.get())
			assertEquals(ExportResult.Success(recordCount = 1), exportResult.get())
			assertEquals(listOf(GAP), decodedGapIds(kind, output.toByteArray()))
			assertCleanSpool()
		}
	}

	@Test fun `Wi-Fi parent and child reads share the snapshot before a competing deletion`() = runTest {
		seedWifiDetail()
		val writerStarted = CountDownLatch(1)
		val writerFinished = CountDownLatch(1)
		val writerFailure = AtomicReference<Throwable>()
		var writing: Thread? = null
		val output = object : ByteArrayOutputStream() {
			override fun write(buffer: ByteArray, offset: Int, length: Int) {
				assertFalse(database.inTransaction())
				check(writerFinished.await(5, TimeUnit.SECONDS))
				super.write(buffer, offset, length)
			}
		}
		val selected = exporter(Kind.WIFI, afterParentRead = {
			assertTrue(database.inTransaction())
			writing = thread(name = "radio-snapshot-child-delete") {
				try {
					runBlocking {
						writerStarted.countDown()
						database.withTransaction {
							database.openHelper.writableDatabase.execSQL(
								"DELETE FROM imported_ambient_wifi_child WHERE fact_id = ?", arrayOf(FACT))
						}
					}
				} catch (failure: Throwable) { writerFailure.set(failure) }
				finally { writerFinished.countDown() }
			}
			assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
			assertFalse(writerFinished.await(100, TimeUnit.MILLISECONDS))
		})
		try {
			assertEquals(ExportResult.Success(recordCount = 2), selected.exportWithSessionSelection(
				context, output, RANGE, true))
		} finally { writing?.join(5_000) }
		assertFalse(writing?.isAlive == true)
		assertNull(writerFailure.get())
		val decoded = RadioSourceFileJsonV2Codec().readWifi(ByteArrayInputStream(output.toByteArray()))
		assertEquals(FACT, requireNotNull(decoded.background).facts.single().identity)
		assertEquals("Saved network", decoded.backgroundChildren.single().ssid)
		assertEquals(0, database.importedRadioChildDao().wifiBackgroundChildrenForRevision(FACT, 1, 2).size)
		assertCleanSpool()
	}

	@Test fun `no data and staging read failures send no destination bytes and remove the spool`() = runTest {
		database.withTransaction {
			database.openHelper.writableDatabase.execSQL("DELETE FROM imported_ambient_wifi_gap")
			database.openHelper.writableDatabase.execSQL("DELETE FROM imported_ambient_cell_gap")
		}
		for (kind in Kind.entries) {
			val empty = ByteArrayOutputStream()
			assertTrue(export(kind, empty) is ExportResult.Error)
			assertEquals(0, empty.size())
			assertCleanSpool()
			seedGap(kind, GAP)
			val failed = ByteArrayOutputStream()
			assertTrue(export(kind, failed, afterParentRead = { throw IOException("Storage unavailable") })
				is ExportResult.Error)
			assertEquals(0, failed.size())
			assertCleanSpool()
		}
	}

	@Test fun `encoded byte overflow publishes nothing and cannot become successful`() = runTest {
		for (kind in Kind.entries) {
			val output = ByteArrayOutputStream()
			assertTrue(export(kind, output, maximumBytes = 1) is ExportResult.Error)
			assertEquals(0, output.size())
			assertCleanSpool()
		}
	}

	@Test fun `destination failure is not success and leaves destination cleanup with the caller`() = runTest {
		for (kind in Kind.entries) {
			var closed = false
			val output = object : ByteArrayOutputStream() {
				override fun write(buffer: ByteArray, offset: Int, length: Int) {
					assertFalse(database.inTransaction())
					super.write(buffer, offset, minOf(length, 8))
					throw IOException("Destination stopped accepting bytes")
				}
				override fun close() { closed = true; super.close() }
			}
			assertTrue(export(kind, output) is ExportResult.Error)
			assertEquals(8, output.size())
			assertFalse(closed)
			assertCleanSpool()
		}
	}

	@Test fun `cancellation during staging escapes both exporters without publishing bytes`() = runTest {
		for (kind in Kind.entries) {
			val output = ByteArrayOutputStream()
			assertFailsWith<CancellationException> {
				export(kind, output, afterParentRead = { throw CancellationException("Stopped staging") })
			}
			assertEquals(0, output.size())
			assertFalse(database.inTransaction())
			assertCleanSpool()
		}
	}

	@Test fun `copy cancellation removes the spool and never returns a success count`() = runTest {
		for (kind in Kind.entries) {
			var result: ExportResult? = null
			var writes = 0
			var closed = false
			val attempt = launch(start = CoroutineStart.UNDISPATCHED) {
				val caller = currentCoroutineContext()
				result = export(kind, object : OutputStream() {
					override fun write(value: Int) = error("Expected buffered copy")
					override fun write(buffer: ByteArray, offset: Int, length: Int) {
						assertFalse(database.inTransaction())
						writes++
						caller.cancel()
					}
					override fun close() { closed = true }
				})
			}
			attempt.join()
			assertTrue(attempt.isCancelled)
			assertNull(result)
			assertEquals(1, writes)
			assertFalse(closed)
			assertCleanSpool()
		}
	}

	private suspend fun export(kind: Kind, output: OutputStream,
		maximumBytes: Long = RadioSourceFileV2.MAX_FILE_BYTES,
		afterParentRead: suspend () -> Unit = {}): ExportResult =
		exporter(kind, maximumBytes, afterParentRead).exportWithSessionSelection(context, output, RANGE, true)

	private fun exporter(kind: Kind, maximumBytes: Long = RadioSourceFileV2.MAX_FILE_BYTES,
		afterParentRead: suspend () -> Unit = {}): RadioSourceExporter = when (kind) {
		Kind.WIFI -> {
			val background = mockk<ExportPortableAmbientWifi>()
			coEvery { background.export(any(), any()) } coAnswers {
				assertTrue(database.inTransaction())
				val facts = database.ambientWifiFactDao().importedOverWindow(0, 86_400_000, 100).map { row ->
					AmbientWifiPortableIntegrity.createFact(AmbientWifiFact(row.factId,
						AmbientWifiOrigin.valueOf(row.portableOrigin), row.coverageStartTimeMs,
						row.observedTimeMs, row.latestPossibleTimeMs, row.structuralEpochDay,
						row.storedZoneId, AmbientWifiCoverage.valueOf(row.coverageCompleteness),
						row.observationCount, row.twoPointFourGhzCount, row.fiveGhzCount,
						row.sixGhzCount, row.otherBandCount, row.strongestSignalDbm,
						row.weakestSignalDbm, row.meanSignalDbm, row.semanticRevision,
						row.supersedesSemanticRevision))
				}
				val gaps = database.ambientWifiFactDao().importedGapsOverWindow(0, 86_400_000, 100).map { row ->
					AmbientWifiPortableIntegrity.createGap(AmbientWifiGap(row.gapId,
						AmbientWifiOrigin.valueOf(row.portableOrigin), row.structuralEpochDay,
						row.startTimeMs, row.endTimeMs, row.storedZoneId, row.reason))
				}
				afterParentRead()
				if (facts.isEmpty() && gaps.isEmpty()) ExportPortableAmbientWifiResult.NoData
				else {
					secondArg<PortableAmbientWifiSink>().emit(
						AmbientWifiPortableIntegrity.createArchive(ARCHIVE, facts, gaps))
					ExportPortableAmbientWifiResult.Exported(facts.size, gaps.size)
				}
			}
			val imported = mockk<ImportedWifiProductEvaluator>()
			coEvery { imported.selectExportRangeInTransaction(any()) } coAnswers {
				assertTrue(database.inTransaction())
				ImportedWifiProductRangePage(emptyList(), false)
			}
			val captured = mockk<ExportPortableCapturedWifi>()
			PortableWifiSourceExporter(maximumBytes) {
				object : WifiSourceExportEntryPoint {
					override fun database() = database
					override fun capturedWifi() = captured
					override fun backgroundWifi() = background
					override fun importedWifiEvaluator() = imported
				}
			}
		}
		Kind.CELL -> {
			val background = mockk<ExportPortableAmbientCell>()
			coEvery { background.export(any(), any()) } coAnswers {
				assertTrue(database.inTransaction())
				val gaps = database.ambientCellFactDao().importedGapsOverWindow(0, 86_400_000, 100).map { row ->
					AmbientCellPortableIntegrity.createGap(AmbientCellGap(row.gapId,
						AmbientCellOrigin.valueOf(row.portableOrigin), row.structuralEpochDay,
						row.startTimeMs, row.endTimeMs, row.storedZoneId, row.reason))
				}
				afterParentRead()
				if (gaps.isEmpty()) ExportPortableAmbientCellResult.NoData
				else {
					secondArg<PortableAmbientCellSink>().emit(
						AmbientCellPortableIntegrity.createArchive(ARCHIVE, emptyList(), gaps))
					ExportPortableAmbientCellResult.Exported(0, gaps.size)
				}
			}
			val captured = mockk<ExportPortableCapturedCell>()
			PortableCellSourceExporter(maximumBytes) {
				object : CellSourceExportEntryPoint {
					override fun database() = database
					override fun capturedCell() = captured
					override fun backgroundCell() = background
				}
			}
		}
	}

	private suspend fun seedGap(kind: Kind, identity: String) {
		when (kind) {
			Kind.WIFI -> {
				val gap = AmbientWifiPortableIntegrity.createGap(AmbientWifiGap(identity,
					AmbientWifiOrigin.PORTABLE_IMPORT, 0, 1_000, 2_000, "UTC", "Unavailable"))
				database.ambientWifiFactDao().insertImportedGap(ImportedAmbientWifiGapEntity(
					ARCHIVE, identity, gap.contentChecksum, gap.effectChecksum, gap.origin.name,
					gap.startTimeMs, gap.endTimeMs, gap.storedZoneId, gap.structuralEpochDay,
					gap.reason, "policy", 1, 0, 3_000))
			}
			Kind.CELL -> {
				val gap = AmbientCellPortableIntegrity.createGap(AmbientCellGap(identity,
					AmbientCellOrigin.PORTABLE_IMPORT, 0, 1_000, 2_000, "UTC", "Unavailable"))
				database.ambientCellFactDao().insertImportedGap(ImportedAmbientCellGapEntity(
					ARCHIVE, identity, gap.contentChecksum, gap.effectChecksum, gap.origin.name,
					gap.startTimeMs, gap.endTimeMs, gap.storedZoneId, gap.structuralEpochDay,
					gap.reason, "policy", 1, 0, 3_000))
			}
		}
	}

	private suspend fun seedWifiDetail() {
		val fact = AmbientWifiPortableIntegrity.createFact(AmbientWifiFact(FACT,
			AmbientWifiOrigin.PORTABLE_IMPORT, 1_000, 2_000, 2_000, 0, "UTC",
			AmbientWifiCoverage.COMPLETE, 1, 1, 0, 0, 0, -40, -40, -40.0))
		database.ambientWifiFactDao().insertImportedFact(ImportedAmbientWifiFactEntity(
			ARCHIVE, FACT, 1, null, fact.contentChecksum, fact.effectChecksum, fact.origin.name,
			fact.coverageStartTimeMs, fact.observedTimeMs, fact.latestPossibleTimeMs, "UTC", 0,
			fact.coverage.name, 1, 1, 0, 0, 0, -40, -40, -40.0, "policy", 1, 0, 0, 3_000))
		val checksum = radioChildDigest("wifi", listOf(FACT), listOf(1L, 0, 2_000_000_000L,
			2_000L, 0L, "00:11:22:33:44:55", "Saved network", 2_412, -40))
		database.importedRadioChildDao().insertWifiBackground(listOf(ImportedAmbientWifiChildEntity(
			ARCHIVE, FACT, 1, 0, 2_000_000_000, 2_000, 0, "00:11:22:33:44:55",
			"Saved network", 2_412, -40, checksum)))
	}

	private suspend fun decodedGapIds(kind: Kind, bytes: ByteArray): List<String> = when (kind) {
		Kind.WIFI -> RadioSourceFileJsonV2Codec().readWifi(ByteArrayInputStream(bytes)).background?.gaps.orEmpty()
			.map { it.identity }
		Kind.CELL -> RadioSourceFileJsonV2Codec().readCell(ByteArrayInputStream(bytes)).background?.gaps.orEmpty()
			.map { it.identity }
	}

	private fun assertCleanSpool() = assertTrue(spoolDirectory.listFiles().orEmpty().isEmpty())
	private enum class Kind { WIFI, CELL }
	private companion object {
		val ARCHIVE = "a".repeat(64)
		val GAP = "b".repeat(64)
		val SECOND_GAP = "c".repeat(64)
		val FACT = "d".repeat(64)
		val RANGE = 0L..86_399_999L
	}
}
