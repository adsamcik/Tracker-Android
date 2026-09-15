package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingHistoryRepositoryTest {
	@Test
	fun `live snapshot rejects mixed-time capture authority`() {
		val stepsCapture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(100L),
					capturedSources = setOf(HistorySource.STEPS),
					controlSources = emptySet(),
				),
			),
		)
		val pressureCapture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 2L,
					effectiveAt = EpochMs(200L),
					capturedSources = setOf(HistorySource.PRESSURE),
					controlSources = emptySet(),
				),
			),
		)

		assertFailsWith<IllegalArgumentException> {
			LiveSessionHistorySnapshot(
				segmentId = 7L,
				session = SessionHistoryQuery.Found(
					SessionHistory(
						segmentId = 7L,
						capture = stepsCapture,
						qualifiedSources = emptySet(),
						steps = missingSteps(),
					),
				),
				activity = ActivityHistoryQuery.Found(unavailableActivityHistory()),
				pressure = PressureSessionHistoryQuery.Found(
					PressureSessionHistory(
						segmentId = 7L,
						capture = pressureCapture,
						qualifiedSources = emptySet(),
						pressure = unavailablePressureHistory(),
					),
				),
			)
		}
	}

	@Test
	fun `exact Steps-only capture ignores separately named control sources`() {
		val history = SessionHistory(
			segmentId = 7L,
			capture = HistoryCapture.Exact(
				listOf(
					HistoryCaptureRevision(
						revision = 1L,
						effectiveAt = EpochMs(100L),
						capturedSources = setOf(HistorySource.STEPS),
						controlSources = setOf(HistorySource.ACTIVITY),
					),
				),
			),
			qualifiedSources = setOf(HistorySource.STEPS),
			steps = completeSteps(4L),
		)

		assertTrue(history.capturesOnlySteps)
	}

	@Test
	fun `mixed or unverifiable capture cannot be presented as Steps-only`() {
		val mixed = SessionHistory(
			segmentId = 7L,
			capture = HistoryCapture.Exact(
				listOf(
					HistoryCaptureRevision(
						revision = 1L,
						effectiveAt = EpochMs(100L),
						capturedSources = setOf(HistorySource.STEPS, HistorySource.LOCATION),
						controlSources = emptySet(),
					),
				),
			),
			qualifiedSources = setOf(HistorySource.STEPS),
			steps = completeSteps(4L),
		)
		val unverifiable = mixed.copy(
			capture = HistoryCapture.Unverifiable,
			qualifiedSources = emptySet(),
		)

		assertFalse(mixed.capturesOnlySteps)
		assertFalse(unverifiable.capturesOnlySteps)
	}

	@Test
	fun `every retained capture revision must remain Steps-only`() {
		val history = SessionHistory(
			segmentId = 7L,
			capture = HistoryCapture.Exact(
				listOf(
					HistoryCaptureRevision(
						revision = 1L,
						effectiveAt = EpochMs(100L),
						capturedSources = setOf(HistorySource.STEPS),
						controlSources = emptySet(),
					),
					HistoryCaptureRevision(
						revision = 2L,
						effectiveAt = EpochMs(200L),
						capturedSources = setOf(HistorySource.STEPS, HistorySource.LOCATION),
						controlSources = emptySet(),
					),
				),
			),
			qualifiedSources = setOf(HistorySource.STEPS),
			steps = completeSteps(4L),
		)

		assertFalse(history.capturesOnlySteps)
	}

	@Test
	fun `qualification requires exact matching capture authority`() {
		assertFailsWith<IllegalArgumentException> {
			SessionHistory(
				segmentId = 7L,
				capture = HistoryCapture.Unverifiable,
				qualifiedSources = setOf(HistorySource.STEPS),
				steps = completeSteps(4L),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			SessionHistory(
				segmentId = 7L,
				capture = HistoryCapture.Exact(
					listOf(
						HistoryCaptureRevision(
							revision = 1L,
							effectiveAt = EpochMs(100L),
							capturedSources = setOf(HistorySource.STEPS),
							controlSources = emptySet(),
						),
					),
				),
				qualifiedSources = setOf(HistorySource.LOCATION),
				steps = completeSteps(4L),
			)
		}
	}

	@Test
	fun `recent Steps-only entry retains explicit nonnumeric product state`() {
		val entry = StepsOnlyHistoryEntry(
			key = TrackingHistoryEntryKey("logical:one"),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			state = StepsOnlyHistoryListState.PARTIAL,
		)
		assertEquals(StepsOnlyHistoryListState.PARTIAL, entry.state)
	}

	@Test
	fun `Steps-aware page distinguishes a physical echo from an opaque logical row`() {
		val history = StepsOnlyHistoryEntry(
			key = TrackingHistoryEntryKey("logical:one"),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			state = StepsOnlyHistoryListState.AVAILABLE,
		)

		assertEquals(
			StepsAwareHistoryPageEntry.Physical(7L),
			StepsAwareHistoryPageEntry.Physical(7L),
		)
		val logicalRow = StepsAwareHistoryPageEntry.StepsOnly(history)
		assertEquals(history, logicalRow.history)
		assertFailsWith<IllegalArgumentException> {
			StepsAwareHistoryPageEntry.Physical(0L)
		}
	}

	@Test
	fun `source-aware page keeps Pressure identities opaque and physical echoes explicit`() {
		val pressure = PressureOnlyHistoryEntry(
			key = TrackingHistoryEntryKey("pressure:logical:one"),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			pressure = unavailablePressureHistory(),
		)

		val logicalRow = SourceAwareHistoryPageEntry.PressureOnly(pressure)
		assertEquals("TrackingHistoryEntryKey", logicalRow.history.key.toString())
		assertEquals(
			SourceAwareHistoryPageEntry.Physical(7L),
			SourceAwareHistoryPageEntry.Physical(7L),
		)
		assertFailsWith<IllegalArgumentException> {
			SourceAwareHistoryPageEntry.Physical(0L)
		}
	}

	@Test
	fun `source-aware Activity row requires exact Activity-only intent`() {
		val activity = ActivityHistoryEntry(
			key = ActivityHistoryEntryKey("activity"),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			storedZoneIds = setOf("UTC"),
			state = ActivityHistoryProductState.UNAVAILABLE,
			coverage = ActivityHistoryCoverage.NONE,
			activeTime = null,
			fragments = emptyList(),
			causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
		)

		assertFailsWith<IllegalArgumentException> {
			SourceAwareHistoryPageEntry.ActivityOnly(activity)
		}
		assertEquals(
			activity.copy(capturesOnlyActivity = true),
			SourceAwareHistoryPageEntry.ActivityOnly(
				activity.copy(capturesOnlyActivity = true),
			).history,
		)
	}

	@Test
	fun `factless native-only intent stays visible without qualifying a source`() {
		val capture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(100L),
					capturedSources = setOf(HistorySource.WIFI),
					controlSources = emptySet(),
				),
			),
		)
		val wifi = WifiHistoryQuery.Found(
			WifiHistoryEntry(
				key = WifiHistoryEntryKey("wifi"),
				startTime = EpochMs(100L),
				endTime = EpochMs(200L),
				storedZoneIds = emptySet(),
				state = WifiHistoryProductState.MATERIALIZING,
				coverage = WifiHistoryCoverage.NONE,
				observations = emptyList(),
				causes = setOf(WifiHistoryCause.MATERIALIZATION_BEHIND),
				localSelection = WifiLocalHistorySelectionKey("a".repeat(64)),
				capturesOnlyWifi = true,
			),
		)
		val products = SessionHistoryProducts(
			segmentId = 7L,
			wifi = wifi,
			cell = CellHistoryQuery.Found(unavailableCellHistory()),
			activity = ActivityHistoryQuery.Found(unavailableActivityHistory()),
			pressure = pressureQueryFor(
				SessionHistory(
					segmentId = 7L,
					capture = capture,
					qualifiedSources = emptySet(),
					steps = missingSteps(),
				),
			),
		)

		SessionHistory(
			segmentId = 7L,
			capture = capture,
			qualifiedSources = emptySet(),
			steps = missingSteps(),
			sourceProducts = products,
			readSnapshot = TrackingHistoryReadSnapshot(3L, 9L),
		)
		assertFailsWith<IllegalArgumentException> {
			SessionHistory(
				segmentId = 7L,
				capture = capture,
				qualifiedSources = setOf(HistorySource.WIFI),
				steps = missingSteps(),
				sourceProducts = products,
			)
		}
	}

	@Test
	fun `retained radio observation qualifies captured Wi-Fi without a fact count proxy`() {
		val capture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(100L),
					capturedSources = setOf(HistorySource.WIFI),
					controlSources = emptySet(),
				),
			),
		)
		val products = SessionHistoryProducts(
			segmentId = 7L,
			wifi = WifiHistoryQuery.Found(
				WifiHistoryEntry(
					key = WifiHistoryEntryKey("wifi"),
					startTime = EpochMs(100L),
					endTime = EpochMs(200L),
					storedZoneIds = setOf("UTC"),
					state = WifiHistoryProductState.READY,
					coverage = WifiHistoryCoverage.COMPLETE,
					observations = listOf(
						WifiHistoryObservation(
							intervalStartTime = EpochMs(100L),
							observedTime = EpochMs(150L),
							wallTimeUncertaintyMs = 0L,
							availability = WifiHistoryAvailability.AVAILABLE,
							resultCompleteness = WifiHistoryResultCompleteness.COMPLETE,
							submittedResultCount = 1,
							acceptedResultCount = 1,
							rejectedResultCount = 0,
							observationCount = 1,
							bandMix = mapOf(WifiHistoryBand.FIVE_GHZ to 1),
							signalQuality = WifiHistorySignalQuality(-40, -40, -40.0, 1),
							sourceQualityFlags = 0L,
							sourceQualityConfidence = null,
							storedZoneId = "UTC",
						),
					),
					localSelection = WifiLocalHistorySelectionKey("a".repeat(64)),
					capturesOnlyWifi = true,
				),
			),
			cell = CellHistoryQuery.Found(unavailableCellHistory()),
			activity = ActivityHistoryQuery.Found(unavailableActivityHistory()),
			pressure = pressureQueryFor(
				SessionHistory(7L, capture, emptySet(), missingSteps()),
			),
		)

		val history = SessionHistory(
			segmentId = 7L,
			capture = capture,
			qualifiedSources = setOf(HistorySource.WIFI),
			steps = missingSteps(),
			sourceProducts = products,
		)

		assertEquals(setOf(HistorySource.WIFI), history.qualifiedSources)
	}

	@Test
	fun `Steps qualification requires retained covered value but ignores current capability`() {
		val retainedAfterCapabilityChange = StepsHistory(
			count = 12L,
			availability = HistoryAvailability.DISABLED,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.PARTIAL,
			causes = setOf(StepsHistoryCause.PROVIDER_GAP),
		)
		val legacyUnknown = retainedAfterCapabilityChange.copy(
			availability = HistoryAvailability.UNAVAILABLE,
			productState = HistoryProductState.DEGRADED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			causes = setOf(StepsHistoryCause.LEGACY_UNVERIFIED),
		)
		val recordedWithoutValue = StepsHistory(
			count = null,
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.MATERIALIZING,
			coverage = StepsHistoryCoverage.PARTIAL,
			causes = setOf(StepsHistoryCause.MATERIALIZATION_BEHIND),
		)

		assertTrue(retainedAfterCapabilityChange.hasQualifiedRetainedProof)
		assertFalse(legacyUnknown.hasQualifiedRetainedProof)
		assertFalse(recordedWithoutValue.hasQualifiedRetainedProof)
	}

	@Test
	fun `covered count with invalid authority cause cannot qualify Steps`() {
		val invalidatingCauses = listOf(
			StepsHistoryCause.SOURCE_NOT_CAPTURED,
			StepsHistoryCause.BASELINE_ONLY,
			StepsHistoryCause.NO_OBSERVATION,
			StepsHistoryCause.HISTORY_MEMBERSHIP_UNAVAILABLE,
			StepsHistoryCause.HISTORY_INTEGRITY_FAILED,
			StepsHistoryCause.WRITER_PROVENANCE_INVALID,
			StepsHistoryCause.LEGACY_UNVERIFIED,
			StepsHistoryCause.MATERIALIZATION_UNAVAILABLE,
			StepsHistoryCause.FACTS_MISSING,
			StepsHistoryCause.DELETED,
			StepsHistoryCause.EVIDENCE_STATE_UNAVAILABLE,
			StepsHistoryCause.PRIVACY_EPOCH_MISMATCH,
			StepsHistoryCause.VALUE_OVERFLOW,
		)

		invalidatingCauses.forEach { cause ->
			val history = StepsHistory(
				count = 12L,
				availability = HistoryAvailability.UNAVAILABLE,
				evidence = HistoryEvidence.RECORDED,
				productState = HistoryProductState.PARTIAL,
				coverage = StepsHistoryCoverage.PARTIAL,
				causes = setOf(cause),
			)

			assertFalse(history.hasQualifiedRetainedProof, cause.name)
		}
	}

	@Test
	fun `covered count remains qualified through explicit lower-bound and capability causes`() {
		val eligibleCauses = listOf(
			StepsHistoryCause.AVAILABILITY_UNAVAILABLE,
			StepsHistoryCause.CAPTURE_PARTIAL,
			StepsHistoryCause.SESSION_STILL_ACTIVE,
			StepsHistoryCause.MATERIALIZATION_BEHIND,
			StepsHistoryCause.ACQUISITION_INCOMPLETE,
			StepsHistoryCause.PROVIDER_GAP,
			StepsHistoryCause.RETENTION_LIMIT,
		)

		eligibleCauses.forEach { cause ->
			val history = StepsHistory(
				count = 12L,
				availability = HistoryAvailability.UNAVAILABLE,
				evidence = HistoryEvidence.RECORDED,
				productState = HistoryProductState.PARTIAL,
				coverage = StepsHistoryCoverage.PARTIAL,
				causes = setOf(cause),
			)

			assertTrue(history.hasQualifiedRetainedProof, cause.name)
		}
	}

	@Test
	fun `native selected session rejects imported Activity bands`() {
		val capture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(100L),
					capturedSources = setOf(HistorySource.ACTIVITY, HistorySource.STEPS),
					controlSources = emptySet(),
				),
			),
		)
		val importedActivity = ActivityHistoryEntry(
			key = ActivityHistoryEntryKey("activity-imported"),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			storedZoneIds = setOf("UTC"),
			state = ActivityHistoryProductState.READY,
			coverage = ActivityHistoryCoverage.COMPLETE,
			activeTime = ActivityActiveTime(100L, 0L, 0L, 0L),
			fragments = listOf(
				ActivityHistoryFragment.Band(
					storedZoneId = "UTC",
					startTime = EpochMs(100L),
					endTime = EpochMs(200L),
					startUncertaintyMs = 0L,
					endUncertaintyMs = 0L,
					activity = ActivityHistoryType.WALKING,
					mechanism = ActivityHistoryMechanism.TRANSITION,
					refinedTransitionActivity = null,
					confidence = ActivityHistoryConfidence.TransitionSignal,
					wallTimeContinuity = ActivityHistoryWallTimeContinuity.SAME_ANCHOR,
					durationNanos = 100L,
				),
			),
			origin = ActivityHistoryOrigin.IMPORTED,
			capturesOnlyActivity = false,
		)
		val base = SessionHistory(
			segmentId = 7L,
			capture = capture,
			qualifiedSources = emptySet(),
			steps = missingSteps(),
		)
		val products = SessionHistoryProducts(
			segmentId = 7L,
			wifi = WifiHistoryQuery.Found(
				WifiHistoryEntry(
					key = WifiHistoryEntryKey("wifi"),
					startTime = EpochMs(100L),
					endTime = EpochMs(200L),
					storedZoneIds = emptySet(),
					state = WifiHistoryProductState.UNAVAILABLE,
					coverage = WifiHistoryCoverage.NONE,
					observations = emptyList(),
					causes = setOf(WifiHistoryCause.SOURCE_NOT_CAPTURED),
				),
			),
			cell = CellHistoryQuery.Found(unavailableCellHistory()),
			activity = ActivityHistoryQuery.Found(importedActivity),
			pressure = pressureQueryFor(base),
		)

		assertEquals(
			emptySet(),
			SessionHistory.deriveQualifiedSources(capture, missingSteps(), products),
		)
		assertFailsWith<IllegalArgumentException> {
			base.copy(sourceProducts = products)
		}
		assertFailsWith<IllegalArgumentException> {
			LiveSessionHistorySnapshot(
				segmentId = 7L,
				session = SessionHistoryQuery.Found(base),
				activity = ActivityHistoryQuery.Found(importedActivity),
				pressure = pressureQueryFor(base),
			)
		}
	}

	@Test
	fun `portable source rows preserve producer action authority without native membership`() {
		val wifiSelection = WifiImportedHistorySelection(
			key = WifiImportedHistorySelectionKey("a".repeat(64)),
			importRevision = 4L,
			contentChecksum = "b".repeat(64),
		)
		val wifi = SourceAwareHistoryPageEntry.WifiOnly(
			WifiHistoryEntry(
				key = WifiHistoryEntryKey("wifi-imported"),
				startTime = EpochMs(100L),
				endTime = EpochMs(200L),
				storedZoneIds = emptySet(),
				state = WifiHistoryProductState.FAILED,
				coverage = WifiHistoryCoverage.NONE,
				observations = emptyList(),
				causes = setOf(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
				origin = WifiHistoryOrigin.IMPORTED,
				importedSelection = wifiSelection,
			),
		)
		val cellSelection = ImportedCellHistorySelection(
			identity = ImportedCellHistoryIdentity("c".repeat(64)),
			importRevision = 5L,
			contentChecksum = ImportedCellHistoryDigest("d".repeat(64)),
		)
		val cell = SourceAwareHistoryPageEntry.CellOnly(
			unavailableCellHistory().copy(
				state = CellHistoryProductState.UNVERIFIABLE,
				causes = setOf(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
				origin = CellHistoryOrigin.Imported(cellSelection),
				selection = cellSelection,
			),
		)
		val activityEntry = unavailableActivityHistory().copy(
			state = ActivityHistoryProductState.FAILED,
			causes = setOf(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
			origin = ActivityHistoryOrigin.IMPORTED,
		)
		val activity = SourceAwareHistoryPageEntry.ActivityOnly(activityEntry)
		val pressure = SourceAwareHistoryPageEntry.PressureOnly(
			PressureOnlyHistoryEntry(
				key = TrackingHistoryEntryKey("pressure-imported"),
				origin = PressureHistoryOrigin.Imported(
					ImportedPressureHistoryIdentity("sha256:${"e".repeat(64)}"),
				),
				startTime = EpochMs(100L),
				endTime = EpochMs(200L),
				pressure = unavailablePressureHistory().copy(
					causes = setOf(PressureHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
				),
			),
		)

		assertEquals(
			TrackingHistoryActionTarget.Wifi(WifiHistorySelection.Imported(wifiSelection)),
			wifi.actionTarget,
		)
		assertEquals(TrackingHistoryActionTarget.Cell(cellSelection), cell.actionTarget)
		assertEquals(
			TrackingHistoryActionTarget.NonActionable(
				HistorySource.ACTIVITY,
				TrackingHistoryNonActionableReason.ACTIVITY_SELECTOR_UNAVAILABLE,
			),
			activity.actionTarget,
		)
		assertEquals(
			TrackingHistoryActionTarget.NonActionable(
				HistorySource.PRESSURE,
				TrackingHistoryNonActionableReason.PRESSURE_SELECTOR_UNAVAILABLE,
			),
			pressure.actionTarget,
		)
		assertEquals(SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP, wifi.intent)
		assertEquals(SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP, cell.intent)
		assertEquals(SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP, activity.intent)
		assertEquals(SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP, pressure.intent)
	}

	@Test
	fun `Wi-Fi source row rejects a missing producer selector`() {
		assertFailsWith<IllegalArgumentException> {
			SourceAwareHistoryPageEntry.WifiOnly(
				WifiHistoryEntry(
					key = WifiHistoryEntryKey("wifi-unverifiable"),
					startTime = EpochMs(100L),
					endTime = EpochMs(200L),
					storedZoneIds = emptySet(),
					state = WifiHistoryProductState.FAILED,
					coverage = WifiHistoryCoverage.NONE,
					observations = emptyList(),
					causes = setOf(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
					origin = WifiHistoryOrigin.IMPORTED,
				),
			)
		}
	}

	@Test
	fun `live snapshot rejects a different source evidence revision`() {
		val session = SessionHistory(
			segmentId = 7L,
			capture = HistoryCapture.Unverifiable,
			qualifiedSources = emptySet(),
			steps = missingSteps(),
			readSnapshot = TrackingHistoryReadSnapshot(3L, 9L),
		)

		assertFailsWith<IllegalArgumentException> {
			LiveSessionHistorySnapshot(
				segmentId = 7L,
				session = SessionHistoryQuery.Found(session),
				activity = ActivityHistoryQuery.Found(unavailableActivityHistory()),
				pressure = pressureQueryFor(session),
				readSnapshot = TrackingHistoryReadSnapshot(3L, 10L),
			)
		}
	}

	@Test
	fun `source-specific unavailable query requires its source`() {
		assertFailsWith<IllegalArgumentException> {
			SessionHistoryQuery.Unavailable(
				TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			)
		}
		assertFailsWith<IllegalArgumentException> {
			SourceAwareHistoryPageQuery.Unavailable(
				SourceAwareHistoryPageUnavailableReason
					.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
			)
		}
		assertEquals(
			TrackingHistoryReadSnapshot(7L, 11L),
			SourceAwareHistoryPageQuery.Content(
				entries = emptyList(),
				readSnapshot = TrackingHistoryReadSnapshot(7L, 11L),
			).readSnapshot,
		)
	}

	@Test
	fun `Activity action target retains exact selection and fails closed when absent`() {
		val local = unavailableActivityHistory().copy(capturesOnlyActivity = true)
		val importedSelection = importedActivitySelection(local.key)
		val imported = local.copy(
			origin = ActivityHistoryOrigin.IMPORTED,
			capturesOnlyActivity = false,
			importedSelection = importedSelection,
		)
		val retainedImported = imported.copy(
			causes = setOf(ActivityHistoryCause.RETENTION_LIMIT),
			importedSelection = null,
		)
		val failedLocal = local.copy(
			state = ActivityHistoryProductState.FAILED,
			causes = setOf(ActivityHistoryCause.MANIFEST_INTEGRITY_FAILED),
		)

		assertEquals(
			TrackingHistoryActionTarget.Activity(ActivityHistorySelection.Local(local.key)),
			SourceAwareHistoryPageEntry.ActivityOnly(local).actionTarget,
		)
		assertEquals(
			TrackingHistoryActionTarget.Activity(
				ActivityHistorySelection.Imported(importedSelection),
			),
			SourceAwareHistoryPageEntry.ActivityOnly(imported).actionTarget,
		)
		assertEquals(
			TrackingHistoryActionTarget.NonActionable(
				HistorySource.ACTIVITY,
				TrackingHistoryNonActionableReason.ACTIVITY_SELECTOR_UNAVAILABLE,
			),
			SourceAwareHistoryPageEntry.ActivityOnly(retainedImported).actionTarget,
		)
		assertEquals(
			TrackingHistoryActionTarget.NonActionable(
				HistorySource.ACTIVITY,
				TrackingHistoryNonActionableReason.ACTIVITY_SELECTOR_UNAVAILABLE,
			),
			SourceAwareHistoryPageEntry.ActivityOnly(failedLocal).actionTarget,
		)
	}

	@Test
	fun `combined live snapshot rejects mixed found and missing source reads`() {
		val session = SessionHistoryQuery.Found(
			SessionHistory(
				segmentId = 7L,
				capture = HistoryCapture.Exact(
					listOf(
						HistoryCaptureRevision(
							revision = 1L,
							effectiveAt = EpochMs(100L),
							capturedSources = setOf(HistorySource.ACTIVITY),
							controlSources = emptySet(),
						),
					),
				),
				qualifiedSources = emptySet(),
				steps = missingSteps(),
			),
		)

		assertFailsWith<IllegalArgumentException> {
			LiveSessionHistorySnapshot(
				segmentId = 7L,
				session = session,
				activity = ActivityHistoryQuery.NotFound,
				pressure = pressureQueryFor(session.history),
			)
		}
	}

	@Test
	fun `combined live snapshot rejects unacknowledged common Activity-only intent`() {
		val session = SessionHistory(
			segmentId = 7L,
			capture = HistoryCapture.Exact(
				listOf(
					HistoryCaptureRevision(
						revision = 1L,
						effectiveAt = EpochMs(100L),
						capturedSources = setOf(HistorySource.ACTIVITY),
						controlSources = emptySet(),
					),
				),
			),
			qualifiedSources = emptySet(),
			steps = missingSteps(),
		)

		assertFailsWith<IllegalArgumentException> {
			LiveSessionHistorySnapshot(
				segmentId = session.segmentId,
				session = SessionHistoryQuery.Found(session),
				activity = ActivityHistoryQuery.Found(unavailableActivityHistory()),
				pressure = pressureQueryFor(session),
			)
		}
	}

	@Test
	fun `combined live snapshot rejects dual source-only truth`() {
		val capture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(100L),
					capturedSources = setOf(HistorySource.PRESSURE),
					controlSources = emptySet(),
				),
			),
		)
		val session = SessionHistory(
			segmentId = 7L,
			capture = capture,
			qualifiedSources = emptySet(),
			steps = missingSteps(),
		)
		val activity = ActivityHistoryEntry(
			key = ActivityHistoryEntryKey("activity"),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			storedZoneIds = setOf("UTC"),
			state = ActivityHistoryProductState.UNAVAILABLE,
			coverage = ActivityHistoryCoverage.NONE,
			activeTime = null,
			fragments = emptyList(),
			causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
			capturesOnlyActivity = true,
		)

		assertFailsWith<IllegalArgumentException> {
			LiveSessionHistorySnapshot(
				segmentId = 7L,
				session = SessionHistoryQuery.Found(session),
				activity = ActivityHistoryQuery.Found(activity),
				pressure = pressureQueryFor(session),
			)
		}
	}

	@Test
	fun `covered zero remains distinct from missing evidence`() {
		val history = StepsHistory(
			count = 0L,
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.ACTIVE,
			productState = HistoryProductState.READY,
			coverage = StepsHistoryCoverage.COMPLETE,
		)

		assertTrue(history.hasCompleteValue)
		assertFalse(history.isLowerBound)
	}

	@Test
	fun `zero without covered active evidence is rejected`() {
		assertFailsWith<IllegalArgumentException> {
			StepsHistory(
				count = 0L,
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.NONE,
				productState = HistoryProductState.PARTIAL,
				coverage = StepsHistoryCoverage.NONE,
			)
		}
	}

	@Test
	fun `covered zero cannot claim positive-delta recording onset`() {
		assertFailsWith<IllegalArgumentException> {
			StepsHistory(
				count = 0L,
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.RECORDED,
				productState = HistoryProductState.READY,
				coverage = StepsHistoryCoverage.COMPLETE,
			)
		}
	}

	@Test
	fun `partial positive count is explicitly a lower bound`() {
		val history = StepsHistory(
			count = 12L,
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.PARTIAL,
			causes = setOf(StepsHistoryCause.PROVIDER_GAP),
		)

		assertFalse(history.hasCompleteValue)
		assertTrue(history.isLowerBound)
	}

	@Test
	fun `complete historical value does not depend on later acquisition availability`() {
		HistoryAvailability.entries.forEach { availability ->
			val history = StepsHistory(
				count = 12L,
				availability = availability,
				evidence = HistoryEvidence.RECORDED,
				productState = HistoryProductState.READY,
				coverage = StepsHistoryCoverage.COMPLETE,
			)

			assertTrue(history.hasCompleteValue)
			assertFalse(history.isLowerBound)
		}
	}

	@Test
	fun `unknown legacy coverage is neither exact nor a lower bound`() {
		val history = StepsHistory(
			count = 12L,
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.DEGRADED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			causes = setOf(StepsHistoryCause.LEGACY_UNVERIFIED),
		)

		assertFalse(history.hasCompleteValue)
		assertFalse(history.isLowerBound)
	}

	@Test
	fun `recorded acquisition may precede a materialized product value`() {
		val history = StepsHistory(
			count = null,
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.MATERIALIZING,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.MATERIALIZATION_BEHIND),
		)

		assertFalse(history.hasCompleteValue)
		assertFalse(history.isLowerBound)
	}

	@Test
	fun `positive value without recorded evidence is rejected`() {
		assertFailsWith<IllegalArgumentException> {
			StepsHistory(
				count = 1L,
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.ACTIVE,
				productState = HistoryProductState.READY,
				coverage = StepsHistoryCoverage.COMPLETE,
			)
		}
	}

	@Test
	fun `incomplete product state requires a named cause`() {
		assertFailsWith<IllegalArgumentException> {
			StepsHistory(
				count = null,
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.NONE,
				productState = HistoryProductState.PARTIAL,
				coverage = StepsHistoryCoverage.NONE,
			)
		}
	}

	private fun completeSteps(count: Long) = StepsHistory(
		count = count,
		availability = HistoryAvailability.AVAILABLE,
		evidence = HistoryEvidence.RECORDED,
		productState = HistoryProductState.READY,
		coverage = StepsHistoryCoverage.COMPLETE,
	)

	private fun missingSteps() = StepsHistory(
		count = null,
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		causes = setOf(StepsHistoryCause.LEGACY_UNVERIFIED),
	)

	private fun pressureQueryFor(session: SessionHistory) = PressureSessionHistoryQuery.Found(
		PressureSessionHistory(
			segmentId = session.segmentId,
			capture = session.capture,
			qualifiedSources = emptySet(),
			pressure = unavailablePressureHistory(),
		),
	)

	private fun unavailableActivityHistory() = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity:unavailable"),
		startTime = EpochMs(100L),
		endTime = EpochMs(200L),
		storedZoneIds = setOf("UTC"),
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
	)

	private fun unavailableCellHistory() = CellHistoryEntry(
		key = CellHistoryEntryKey("cell:unavailable"),
		startTime = EpochMs(100L),
		endTime = EpochMs(200L),
		storedZoneIds = emptySet(),
		state = CellHistoryProductState.UNAVAILABLE,
		coverage = CellHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(CellHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun importedActivitySelection(
		key: ActivityHistoryEntryKey,
	) = ActivityImportedHistorySelection(
		key = key,
		identity = ActivityImportedHistoryIdentity("a".repeat(64)),
		importRevision = 2L,
		contentChecksum = ActivityImportedHistoryDigest("b".repeat(64)),
		runDeletionScopes = listOf(
			ActivityImportedHistoryRunDeletionScope(
				ActivityImportedHistoryIdentity("c".repeat(64)),
				ActivityImportedHistoryDeletionScopeDigest("d".repeat(64)),
			),
		),
		windowIdentities = listOf(ActivityImportedHistoryIdentity("e".repeat(64))),
		readSnapshot = ActivityImportedHistoryReadSnapshot(7L, 9L),
	)

	private fun unavailablePressureHistory() = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.LEGACY_UNATTRIBUTED),
	)
}
