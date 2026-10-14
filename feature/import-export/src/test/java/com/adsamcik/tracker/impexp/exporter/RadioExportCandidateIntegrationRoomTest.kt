package com.adsamcik.tracker.impexp.exporter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.PortableCellUnverifiableReason
import com.adsamcik.tracker.shared.base.database.PortableCellUnavailableReason
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCell
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifi
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangePage
import com.adsamcik.tracker.stats.api.repository.PortableWifiUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableWifiUnavailableReason
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real exporter-to-Room candidate routing; qualification results are explicit source doubles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioExportCandidateIntegrationRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var context: Application

	@Before fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After fun close() = database.close()

	@Test fun `exact Wi-Fi detail export resolves its source selector without querying sibling data`() = runTest {
		seed("old", 100, 200, SourceDestinationOwnerEntity.SOURCE_WIFI)
		seed("chosen", 1_100, 1_500, SourceDestinationOwnerEntity.SOURCE_WIFI)
		val seen = mutableListOf<String>()
		val captured = mockk<ExportPortableCapturedWifi>()
		coEvery { captured.export(any(), any()) } coAnswers {
			seen += firstArg<com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest>().logicalTrackingId
			throw CancellationException("stop after exact source selection")
		}
		val key = com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity.derive(
			com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind.LOGICAL_ENTRY, "chosen").value
		assertFailsWith<CancellationException> {
			PortableWifiSourceExporter { wifiEntry(captured) }.writeSelection(context,
				com.adsamcik.tracker.stats.api.repository.WifiHistorySelection.Local(
					com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey(key)), ByteArrayOutputStream())
		}
		seen shouldBe listOf("chosen")
	}

	@Test fun `exact Cell detail export resolves its source selector without date substitution`() = runTest {
		seed("old", 100, 200, SourceDestinationOwnerEntity.SOURCE_CELL)
		seed("chosen", 1_100, 1_500, SourceDestinationOwnerEntity.SOURCE_CELL)
		val seen = mutableListOf<String>()
		val captured = mockk<ExportPortableCapturedCell>()
		coEvery { captured.export(any(), any()) } coAnswers {
			seen += firstArg<com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellRequest>().logicalTrackingId
			throw CancellationException("stop after exact source selection")
		}
		val key = com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity.derive(
			com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind.LOGICAL_ENTRY, "chosen").value
		assertFailsWith<CancellationException> {
			PortableCellSourceExporter { cellEntry(captured) }.writeSelection(context,
				com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection(
					com.adsamcik.tracker.stats.api.repository.LocalCellHistoryIdentity(key)), ByteArrayOutputStream())
		}
		seen shouldBe listOf("chosen")
	}

	@Test fun `narrow Wi-Fi export never qualifies an unrelated unreadable old recording`() = runTest {
		seed("old", 100, 200, SourceDestinationOwnerEntity.SOURCE_WIFI)
		seed("current", 1_100, 1_500, SourceDestinationOwnerEntity.SOURCE_WIFI)
		val seen = mutableListOf<String>()
		val captured = mockk<ExportPortableCapturedWifi>()
		coEvery { captured.export(any(), any()) } coAnswers {
			val id = firstArg<com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest>().logicalTrackingId
			seen += id
			if (id != "current") error("An unrelated unreadable source must not enter qualification")
			ExportPortableCapturedWifiResult.Unavailable(PortableWifiUnavailableReason.PROVIDER_UNAVAILABLE)
		}
		(PortableWifiSourceExporter { wifiEntry(captured) }.exportWithSessionSelection(
			context, ByteArrayOutputStream(), 1_000L..1_999L, true) is ExportResult.Error) shouldBe true
		seen shouldBe listOf("current")
	}

	@Test fun `narrow Cell export never qualifies an unrelated unreadable old recording`() = runTest {
		seed("old", 100, 200, SourceDestinationOwnerEntity.SOURCE_CELL)
		seed("current", 1_100, 1_500, SourceDestinationOwnerEntity.SOURCE_CELL)
		val seen = mutableListOf<String>()
		val captured = mockk<ExportPortableCapturedCell>()
		coEvery { captured.export(any(), any()) } coAnswers {
			val id = firstArg<com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellRequest>().logicalTrackingId
			seen += id
			if (id != "current") error("An unrelated unreadable source must not enter qualification")
			ExportPortableCapturedCellResult.Unavailable(PortableCellUnavailableReason.PROVIDER_UNAVAILABLE)
		}
		(PortableCellSourceExporter { cellEntry(captured) }.exportWithSessionSelection(
			context, ByteArrayOutputStream(), 1_000L..1_999L, true) is ExportResult.Error) shouldBe true
		seen shouldBe listOf("current")
	}

	@Test fun `an unreadable relevant radio source is still an error rather than a successful empty file`() = runTest {
		seed("wifi", 1_100, 1_500, SourceDestinationOwnerEntity.SOURCE_WIFI)
		seed("cell", 1_100, 1_500, SourceDestinationOwnerEntity.SOURCE_CELL)
		val wifi = mockk<ExportPortableCapturedWifi>()
		coEvery { wifi.export(any(), any()) } returns ExportPortableCapturedWifiResult.Unverifiable(
			PortableWifiUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE)
		val cell = mockk<ExportPortableCapturedCell>()
		coEvery { cell.export(any(), any()) } returns ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE)
		(PortableWifiSourceExporter { wifiEntry(wifi) }.exportWithSessionSelection(
			context, ByteArrayOutputStream(), 1_000L..1_999L, true) is ExportResult.Error) shouldBe true
		(PortableCellSourceExporter { cellEntry(cell) }.exportWithSessionSelection(
			context, ByteArrayOutputStream(), 1_000L..1_999L, true) is ExportResult.Error) shouldBe true
	}

	@Test fun `cancellation from qualification escapes both export Room snapshots`() = runTest {
		seed("wifi", 1_100, 1_500, SourceDestinationOwnerEntity.SOURCE_WIFI)
		seed("cell", 1_100, 1_500, SourceDestinationOwnerEntity.SOURCE_CELL)
		val wifi = mockk<ExportPortableCapturedWifi>()
		coEvery { wifi.export(any(), any()) } throws CancellationException("cancel Wi-Fi")
		val cell = mockk<ExportPortableCapturedCell>()
		coEvery { cell.export(any(), any()) } throws CancellationException("cancel Cell")
		assertFailsWith<CancellationException> {
			PortableWifiSourceExporter { wifiEntry(wifi) }.exportWithSessionSelection(
				context, ByteArrayOutputStream(), 1_000L..1_999L, true)
		}
		assertFailsWith<CancellationException> {
			PortableCellSourceExporter { cellEntry(cell) }.exportWithSessionSelection(
				context, ByteArrayOutputStream(), 1_000L..1_999L, true)
		}
	}

	private fun wifiEntry(captured: ExportPortableCapturedWifi): WifiSourceExportEntryPoint {
		val background = mockk<ExportPortableAmbientWifi>()
		coEvery { background.export(any(), any()) } returns ExportPortableAmbientWifiResult.NoData
		val imported = mockk<ImportedWifiProductEvaluator>()
		coEvery { imported.selectExportRangeInTransaction(any()) } returns ImportedWifiProductRangePage(emptyList(), false)
		return object : WifiSourceExportEntryPoint {
			override fun database() = database
			override fun capturedWifi() = captured
			override fun backgroundWifi() = background
			override fun importedWifiEvaluator() = imported
		}
	}

	private fun cellEntry(captured: ExportPortableCapturedCell): CellSourceExportEntryPoint {
		val background = mockk<ExportPortableAmbientCell>()
		coEvery { background.export(any(), any()) } returns ExportPortableAmbientCellResult.NoData
		return object : CellSourceExportEntryPoint {
			override fun database() = database
			override fun capturedCell() = captured
			override fun backgroundCell() = background
		}
	}

	private suspend fun seed(id: String, start: Long, end: Long, source: Int) {
		val dao = database.sourceSessionDao()
		dao.insertSession(LogicalTrackingSessionEntity(id, "FINALIZED", 1, 1, 1, "USER", "boot",
			start, 1, end, 2, end, 1, null))
		dao.saveCompleteness(SourceSessionCompletenessEntity(id, "run:$id", source, "provider:$id",
			1, 1, 1, true, "COMPLETE", "COMPLETE", null, null, end))
		val segment = database.sessionSegmentDao().insert(SessionSegment(startTimeMs = start,
			endTimeMs = end, distanceM = 0f, steps = null, primaryActivity = null,
			activityConfidence = null, sampleCount = 0, source = SegmentSource.USER_CREATED,
			inferenceVersion = null, createdAt = end, logicalTrackingId = id, serviceRunId = "run:$id"))
		dao.insertServiceRun(SourceServiceRunEntity("run:$id", id, "FINALIZED", 1, 1, 0,
			start, 1, end, "COMPLETE", sessionSegmentId = segment))
	}
}
