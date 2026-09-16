package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.LiveSessionHistorySnapshot
import com.adsamcik.tracker.stats.api.repository.LocalCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistory
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.SessionHistoryProducts
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryUnavailableReason
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TripDetailRadioSourcePresentationTest {
	@Test
	fun `exact Wi-Fi-only snapshot selects identity-free Wi-Fi detail`() {
		val wifi = wifi()
		snapshot(HistorySource.WIFI, wifi = WifiHistoryQuery.Found(wifi))
			.toTripDetailSourcePresentation() shouldBe TripDetailSourcePresentation.WifiOnly(wifi)
	}

	@Test
	fun `exact Cell-only snapshot selects identity-free Cell detail`() {
		val cell = cell()
		snapshot(HistorySource.CELL, cell = CellHistoryQuery.Found(cell))
			.toTripDetailSourcePresentation() shouldBe TripDetailSourcePresentation.CellOnly(cell)
	}

	@Test
	fun `typed common query unavailability fails closed with source context`() {
		val readSnapshot = TrackingHistoryReadSnapshot(7L, 11L)
		LiveSessionHistorySnapshot(
			segmentId = 42L,
			session = SessionHistoryQuery.Unavailable(
				reason = TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				source = HistorySource.WIFI,
				readSnapshot = readSnapshot,
			),
			activity = ActivityHistoryQuery.NotFound,
			pressure = PressureSessionHistoryQuery.NotFound,
			readSnapshot = readSnapshot,
		).toTripDetailSourcePresentation() shouldBe TripDetailSourcePresentation.Unavailable(
			reason = TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			source = HistorySource.WIFI,
		)
	}

	@Test
	fun `Cell origin conflict stays whole-source unavailable instead of a detail card`() {
		val conflict = cell().copy(
			state = CellHistoryProductState.UNVERIFIABLE,
			coverage = CellHistoryCoverage.NONE,
			observations = emptyList(),
			causes = setOf(CellHistoryCause.ORIGIN_IDENTITY_CONFLICT),
		)
		val presentation = snapshot(
			HistorySource.CELL,
			cell = CellHistoryQuery.Found(conflict),
		).toTripDetailSourcePresentation()

		presentation shouldBe TripDetailSourcePresentation.Unavailable(
			reason = TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			source = HistorySource.CELL,
		)
	}

	private fun snapshot(
		source: HistorySource,
		wifi: WifiHistoryQuery = WifiHistoryQuery.Found(wifiSourceNotCaptured()),
		cell: CellHistoryQuery = CellHistoryQuery.Found(cellSourceNotCaptured()),
	): LiveSessionHistorySnapshot {
		val capture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(1_000L),
					capturedSources = setOf(source),
					controlSources = emptySet(),
				),
			),
		)
		val activity = ActivityHistoryQuery.Found(activitySourceNotCaptured())
		val pressure = PressureSessionHistoryQuery.Found(
			PressureSessionHistory(
				segmentId = 42L,
				capture = capture,
				qualifiedSources = emptySet(),
				pressure = pressureSourceNotCaptured(),
			),
		)
		val products = SessionHistoryProducts(42L, wifi, cell, activity, pressure)
		val history = SessionHistory(
			segmentId = 42L,
			capture = capture,
			qualifiedSources = emptySet(),
			steps = stepsSourceNotCaptured(),
			sourceProducts = products,
		)
		return LiveSessionHistorySnapshot(
			segmentId = 42L,
			session = SessionHistoryQuery.Found(history),
			activity = activity,
			pressure = pressure,
			wifi = wifi,
			cell = cell,
		)
	}

	private fun wifi() = WifiHistoryEntry(
		key = WifiHistoryEntryKey("wifi"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(2_000L),
		storedZoneIds = setOf("UTC"),
		state = WifiHistoryProductState.MATERIALIZING,
		coverage = WifiHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(WifiHistoryCause.MATERIALIZATION_BEHIND),
		localSelection = WifiLocalHistorySelectionKey("a".repeat(64)),
		capturesOnlyWifi = true,
	)

	private fun cell() = CellHistoryEntry(
		key = CellHistoryEntryKey("cell"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(2_000L),
		storedZoneIds = setOf("UTC"),
		state = CellHistoryProductState.MATERIALIZING,
		coverage = CellHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(CellHistoryCause.MATERIALIZATION_BEHIND),
		selection = LocalCellHistorySelection(LocalCellHistoryIdentity("b".repeat(64))),
	)

	private fun wifiSourceNotCaptured() = WifiHistoryEntry(
		key = WifiHistoryEntryKey("wifi-missing"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(2_000L),
		storedZoneIds = setOf("UTC"),
		state = WifiHistoryProductState.UNAVAILABLE,
		coverage = WifiHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(WifiHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun cellSourceNotCaptured() = CellHistoryEntry(
		key = CellHistoryEntryKey("cell-missing"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(2_000L),
		storedZoneIds = setOf("UTC"),
		state = CellHistoryProductState.UNAVAILABLE,
		coverage = CellHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(CellHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun activitySourceNotCaptured() = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(2_000L),
		storedZoneIds = setOf("UTC"),
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(ActivityHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun pressureSourceNotCaptured() = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.FAILED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun stepsSourceNotCaptured() = StepsHistory(
		count = null,
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.FAILED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		causes = setOf(
			StepsHistoryCause.SOURCE_NOT_CAPTURED,
			StepsHistoryCause.AVAILABILITY_UNAVAILABLE,
		),
	)
}
