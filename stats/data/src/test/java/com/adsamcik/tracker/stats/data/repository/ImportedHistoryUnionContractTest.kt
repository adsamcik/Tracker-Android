package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.PortableActivityDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureDigest
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelection
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ImportedHistoryUnionContractTest {
	@Test
	fun `imported recency orders newest tuple then source then opaque member identity`() {
		val rows = listOf(
			recency(HistorySource.CELL, 100L, "member-z"),
			recency(HistorySource.WIFI, 100L, "member-a"),
			recency(HistorySource.WIFI, 100L, "member-z"),
			recency(HistorySource.ACTIVITY, 200L, "member-a"),
		)

		rows.sortedWith(importedHistoryRecencyOrder) shouldContainExactly listOf(
			recency(HistorySource.ACTIVITY, 200L, "member-a"),
			recency(HistorySource.WIFI, 100L, "member-z"),
			recency(HistorySource.WIFI, 100L, "member-a"),
			recency(HistorySource.CELL, 100L, "member-z"),
		)
		rows.first().newestMemberTieIdentity.toString() shouldBe
			"ImportedHistoryRecencyTieIdentity"
	}

	@Test
	fun `imported recency rejects unsupported source and unrelated page failures`() {
		shouldThrow<IllegalArgumentException> {
			recency(HistorySource.STEPS, 100L, "member")
		}
		shouldThrow<IllegalArgumentException> {
			ImportedHistoryEligiblePage.Unavailable(
				SourceAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT,
			)
		}
	}

	@Test
	fun `pending producer bindings fail closed without an eligible page`() = runTest {
		val unavailable = ImportedHistoryEligiblePage.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
		)

		PendingActivityImportedHistoryEligibleReader
			.recentImportedEligibleForSharedHistoryInTransaction(1) shouldBe unavailable
	}

	@Test
	fun `Wi-Fi eligible entry requires the exact imported selector`() {
		val selected = wifiSelection('a', 'b')

		shouldThrow<IllegalArgumentException> {
			WifiImportedHistoryEligibleEntry(
				entry = wifiEntry(selected),
				selection = wifiSelection('c', 'd'),
				recency = recency(HistorySource.WIFI, 150L, "wifi-run"),
			)
		}
	}

	@Test
	fun `Cell eligible entry requires matching imported origin and selector`() {
		val selected = cellSelection('a', 'b')

		shouldThrow<IllegalArgumentException> {
			CellImportedHistoryEligibleEntry(
				entry = cellEntry(selected),
				selection = cellSelection('c', 'd'),
				recency = recency(HistorySource.CELL, 150L, "cell-run"),
			)
		}
	}

	@Test
	fun `Activity eligible entry rejects imported source-only intent`() {
		val entry = activityEntry(capturesOnlyActivity = true)

		shouldThrow<IllegalArgumentException> {
			ActivityImportedHistoryEligibleEntry(
				entry = entry,
				selector = entry.key,
				identity = PortableActivityOpaqueIdentity("a".repeat(64)),
				importRevision = 1L,
				contentChecksum = PortableActivityDigest("b".repeat(64)),
				recency = recency(HistorySource.ACTIVITY, 150L, "activity-run"),
			)
		}
	}

	@Test
	fun `Activity eligible entry requires the public selector to match its entry key`() {
		val entry = activityEntry(capturesOnlyActivity = false)

		shouldThrow<IllegalArgumentException> {
			ActivityImportedHistoryEligibleEntry(
				entry = entry,
				selector = ActivityHistoryEntryKey("different-activity"),
				identity = PortableActivityOpaqueIdentity("a".repeat(64)),
				importRevision = 1L,
				contentChecksum = PortableActivityDigest("b".repeat(64)),
				recency = recency(HistorySource.ACTIVITY, 150L, "activity-run"),
			)
		}
	}

	@Test
	fun `Pressure eligible entry requires its exact imported identity`() {
		val identity = pressureIdentity('a')

		shouldThrow<IllegalArgumentException> {
			PressureImportedHistoryEligibleEntry(
				entry = pressureEntry(identity),
				identity = pressureIdentity('b'),
				importRevision = 1L,
				contentChecksum = PortablePressureDigest("sha256:${"c".repeat(64)}"),
				recency = recency(HistorySource.PRESSURE, 150L, "pressure-run"),
			)
		}
	}

	@Test
	fun `eligible entries reject wrong source recency`() {
		val wifiSelection = wifiSelection('a', 'b')
		val cellSelection = cellSelection('c', 'd')
		val activity = activityEntry(capturesOnlyActivity = false)
		val pressureIdentity = pressureIdentity('e')

		shouldThrow<IllegalArgumentException> {
			WifiImportedHistoryEligibleEntry(
				wifiEntry(wifiSelection),
				wifiSelection,
				recency(HistorySource.CELL, 150L, "run"),
			)
		}
		shouldThrow<IllegalArgumentException> {
			CellImportedHistoryEligibleEntry(
				cellEntry(cellSelection),
				cellSelection,
				recency(HistorySource.WIFI, 150L, "run"),
			)
		}
		shouldThrow<IllegalArgumentException> {
			ActivityImportedHistoryEligibleEntry(
				activity,
				activity.key,
				PortableActivityOpaqueIdentity("f".repeat(64)),
				1L,
				PortableActivityDigest("1".repeat(64)),
				recency(HistorySource.PRESSURE, 150L, "run"),
			)
		}
		shouldThrow<IllegalArgumentException> {
			PressureImportedHistoryEligibleEntry(
				pressureEntry(pressureIdentity),
				pressureIdentity,
				1L,
				PortablePressureDigest("sha256:${"2".repeat(64)}"),
				recency(HistorySource.ACTIVITY, 150L, "run"),
			)
		}
	}

	@Test
	fun `eligible entries reject recency outside their authenticated envelope`() {
		val wifiSelection = wifiSelection('a', 'b')
		val cellSelection = cellSelection('c', 'd')
		val activity = activityEntry(capturesOnlyActivity = false)
		val pressureIdentity = pressureIdentity('e')

		shouldThrow<IllegalArgumentException> {
			WifiImportedHistoryEligibleEntry(
				wifiEntry(wifiSelection),
				wifiSelection,
				recency(HistorySource.WIFI, 99L, "run"),
			)
		}
		shouldThrow<IllegalArgumentException> {
			CellImportedHistoryEligibleEntry(
				cellEntry(cellSelection),
				cellSelection,
				recency(HistorySource.CELL, 201L, "run"),
			)
		}
		shouldThrow<IllegalArgumentException> {
			ActivityImportedHistoryEligibleEntry(
				activity,
				activity.key,
				PortableActivityOpaqueIdentity("f".repeat(64)),
				1L,
				PortableActivityDigest("1".repeat(64)),
				recency(HistorySource.ACTIVITY, 99L, "run"),
			)
		}
		shouldThrow<IllegalArgumentException> {
			PressureImportedHistoryEligibleEntry(
				pressureEntry(pressureIdentity),
				pressureIdentity,
				1L,
				PortablePressureDigest("sha256:${"2".repeat(64)}"),
				recency(HistorySource.PRESSURE, 201L, "run"),
			)
		}
	}

	private fun recency(
		source: HistorySource,
		startTimeMs: Long,
		tieIdentity: String,
	) = ImportedHistoryRecency(
		source = source,
		newestMemberStartTimeMs = startTimeMs,
		newestMemberTieIdentity = ImportedHistoryRecencyTieIdentity(tieIdentity),
	)

	private fun wifiSelection(
		identity: Char,
		checksum: Char,
	) = WifiImportedHistorySelection(
		key = WifiImportedHistorySelectionKey(identity.toString().repeat(64)),
		importRevision = 1L,
		contentChecksum = checksum.toString().repeat(64),
	)

	private fun wifiEntry(selection: WifiImportedHistorySelection) = WifiHistoryEntry(
		key = WifiHistoryEntryKey("wifi"),
		startTime = EpochMs(100L),
		endTime = EpochMs(200L),
		storedZoneIds = emptySet(),
		state = WifiHistoryProductState.FAILED,
		coverage = WifiHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
		origin = WifiHistoryOrigin.IMPORTED,
		importedSelection = selection,
	)

	private fun cellSelection(
		identity: Char,
		checksum: Char,
	) = ImportedCellHistorySelection(
		identity = ImportedCellHistoryIdentity(identity.toString().repeat(64)),
		importRevision = 1L,
		contentChecksum = ImportedCellHistoryDigest(checksum.toString().repeat(64)),
	)

	private fun cellEntry(selection: ImportedCellHistorySelection) = CellHistoryEntry(
		key = CellHistoryEntryKey("cell"),
		startTime = EpochMs(100L),
		endTime = EpochMs(200L),
		storedZoneIds = emptySet(),
		state = CellHistoryProductState.UNVERIFIABLE,
		coverage = CellHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
		origin = CellHistoryOrigin.Imported(selection),
		selection = selection,
	)

	private fun activityEntry(capturesOnlyActivity: Boolean) = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity"),
		startTime = EpochMs(100L),
		endTime = EpochMs(200L),
		storedZoneIds = emptySet(),
		state = ActivityHistoryProductState.FAILED,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
		origin = ActivityHistoryOrigin.IMPORTED,
		capturesOnlyActivity = capturesOnlyActivity,
	)

	private fun pressureIdentity(value: Char) =
		ImportedPressureHistoryIdentity("sha256:${value.toString().repeat(64)}")

	private fun pressureEntry(identity: ImportedPressureHistoryIdentity) =
		PressureOnlyHistoryEntry(
			key = TrackingHistoryEntryKey("pressure"),
			origin = PressureHistoryOrigin.Imported(identity),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			pressure = PressureHistory(
				availability = HistoryAvailability.UNAVAILABLE,
				evidence = HistoryEvidence.NONE,
				productState = HistoryProductState.FAILED,
				coverage = PressureHistoryCoverage.UNKNOWN,
				windows = emptyList(),
				causes = setOf(PressureHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
			),
		)
}
