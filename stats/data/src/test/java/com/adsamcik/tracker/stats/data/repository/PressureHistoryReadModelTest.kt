package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.PressureHistoryPresentationState
import com.adsamcik.tracker.stats.api.repository.PressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PressureWindowQualification
import io.kotest.matchers.shouldBe
import kotlin.test.assertFailsWith
import org.junit.Test

class PressureHistoryReadModelTest {
	@Test
	fun publicPressureOnlyEntryRetainsLogicalGroupingWithoutPhysicalIdentity() {
		val first = available(
			segment = segment(id = 1L, logicalId = "logical", runId = "run-a", startMs = 100L),
			window = window("fact-a", "run-a", "Europe/Prague", 10L, 1001f, 1000f),
		)
		val replacement = available(
			segment = segment(id = 2L, logicalId = "logical", runId = "run-b", startMs = 200L),
			window = window(
				factId = "fact-b",
				runId = "run-b",
				zoneId = "America/New_York",
				startElapsedNanos = 20L,
				first = 999f,
				last = 998f,
				manifestRevision = 2L,
			),
		)

		val public = requireNotNull(
			PressureLogicalHistoryComposer.compose(listOf(first, replacement))
				.single()
				.toPublicPressureOnlyEntryOrNull(),
		)

		public.key.toString() shouldBe "TrackingHistoryEntryKey"
		public.state shouldBe PressureHistoryPresentationState.READY
		public.pressure.windows.size shouldBe 2
		public.pressure.zoneAuthorities shouldBe
			linkedSetOf("Europe/Prague", "America/New_York")
		public.pressure.summary?.firstHectopascals shouldBe 1001f
		public.pressure.summary?.latestHectopascals shouldBe 998f
		public.pressure.windows.first().sensorAccuracy shouldBe PressureSensorAccuracy.HIGH
		public.pressure.windows.first().qualification shouldBe PressureWindowQualification.COMPLETE
		public.pressure.windows.first().maximumInterSampleGapNanos shouldBe 1L
		public.pressure.windows.first().actualToExpectedSampleRatio shouldBe 1.0
	}

	@Test
	fun publicPressureFailureHasNoFabricatedSummaryOrZone() {
		val unavailable = PressurePhysicalHistory(
			segment = segment(id = 3L, logicalId = "logical", runId = "run-a", startMs = 300L),
			captureAuthority = HistoricalCaptureAuthority.Exact(
				listOf(
					HistoricalCaptureRevision(
						manifestRevision = 1L,
						effectiveWallTimeMs = 300L,
						capturedSources = setOf(TrackingSourceComponent.PRESSURE),
						controlSources = emptySet(),
					),
				),
			),
			windows = emptyList(),
			availability = PressureHistoryAvailability.AVAILABLE,
			evidence = PressureHistoryEvidence.NO_OBSERVATION,
			materialization = PressureHistoryMaterialization.FAILED,
			coverage = PressureHistoryCoverage.UNKNOWN,
			reasons = setOf(PressureHistoryReason.PRESSURE_FACT_INTEGRITY_FAILED),
		)

		val publicEntry = requireNotNull(
			PressureLogicalHistoryComposer.compose(listOf(unavailable))
				.single()
				.toPublicPressureOnlyEntryOrNull(),
		)
		val public = publicEntry.pressure

		publicEntry.state shouldBe PressureHistoryPresentationState.FAILED
		public.summary shouldBe null
		public.zoneAuthorities shouldBe emptySet()
	}

	@Test
	fun failedRetainedPressureRemainsVisibleButUnqualifiedAndIneligibleForSharedReplacement() {
		val failed = available(
			segment = segment(id = 4L, logicalId = "logical", runId = "run-a", startMs = 400L),
			window = window("fact-a", "run-a", "UTC", 10L, 1001f, 1000f),
		).copy(
			materialization = PressureHistoryMaterialization.FAILED,
			coverage = PressureHistoryCoverage.PARTIAL,
			reasons = setOf(PressureHistoryReason.TERMINAL_PROJECTION_FAILURE),
		)

		val logical = PressureLogicalHistoryComposer.compose(listOf(failed)).single()
		val session = failed.toPublicPressureSessionHistory()
		val sourceOnly = requireNotNull(logical.toPublicPressureOnlyEntryOrNull())

		failed.hasQualifiedRetainedProof shouldBe false
		failed.qualifiedSources shouldBe emptySet()
		logical.isOrdinarilyDiscoverable shouldBe true
		logical.isSharedSourceOnlyEligible shouldBe false
		logical.toSharedPressureOnlyEntryOrNull() shouldBe null
		session.qualifiedSources shouldBe emptySet()
		session.pressure.presentationState shouldBe PressureHistoryPresentationState.FAILED
		session.pressure.summary?.windowCount shouldBe 1
		sourceOnly.state shouldBe PressureHistoryPresentationState.FAILED
		sourceOnly.pressure.summary?.firstHectopascals shouldBe 1001f
	}

	@Test
	fun readyAndPartialAuthenticatedPressureWindowsRemainQualified() {
		val ready = available(
			segment = segment(id = 5L, logicalId = "ready", runId = "run-ready", startMs = 500L),
			window = window("fact-ready", "run-ready", "UTC", 10L, 1001f, 1000f),
		)
		val partial = available(
			segment = segment(id = 6L, logicalId = "partial", runId = "run-partial", startMs = 600L),
			window = window("fact-partial", "run-partial", "UTC", 20L, 1000f, 999f),
		).copy(
			coverage = PressureHistoryCoverage.PARTIAL,
			reasons = setOf(PressureHistoryReason.PARTIAL_FACT),
		)

		listOf(ready, partial).forEach { history ->
			history.hasQualifiedRetainedProof shouldBe true
			history.qualifiedSources shouldBe setOf(TrackingSourceComponent.PRESSURE)
			PressureLogicalHistoryComposer.compose(listOf(history))
				.single().also { logical ->
					logical.isSharedSourceOnlyEligible shouldBe true
					requireNotNull(logical.toSharedPressureOnlyEntryOrNull())
				}
			history.toPublicPressureSessionHistory().qualifiedSources shouldBe
				setOf(com.adsamcik.tracker.stats.api.repository.HistorySource.PRESSURE)
		}
	}

	@Test
	fun retainedPressureWindowRequiresItsExactCaptureRevisionProvenance() {
		val valid = available(
			segment = segment(id = 7L, logicalId = "logical", runId = "run-a", startMs = 700L),
			window = window("fact-a", "run-a", "UTC", 10L, 1001f, 1000f),
		)

		assertFailsWith<IllegalArgumentException> {
			valid.copy(
				captureAuthority = HistoricalCaptureAuthority.Exact(
					listOf(
						HistoricalCaptureRevision(
							manifestRevision = 2L,
							effectiveWallTimeMs = 700L,
							capturedSources = setOf(TrackingSourceComponent.PRESSURE),
							controlSources = emptySet(),
						),
					),
				),
			)
		}
	}

	@Test
	fun replacementRunsGroupOnlyByExplicitLogicalIdentityAndPreservePhysicalAuthority() {
		val first = available(
			segment = segment(id = 1L, logicalId = "logical", runId = "run-a", startMs = 100L),
			window = window(
				factId = "fact-a",
				runId = "run-a",
				zoneId = "Europe/Prague",
				startElapsedNanos = 10L,
				first = 1001f,
				last = 1000f,
			),
		)
		val replacement = available(
			segment = segment(id = 2L, logicalId = "logical", runId = "run-b", startMs = 50L),
			window = window(
				factId = "fact-b",
				runId = "run-b",
				zoneId = "America/New_York",
				startElapsedNanos = 20L,
				manifestRevision = 2L,
				first = 999f,
				last = 998f,
			),
		)

		val entry = PressureLogicalHistoryComposer.compose(listOf(replacement, first)).single()

		entry.identity shouldBe PressureHistoryEntryIdentity.Logical("logical")
		entry.physicalMembers.map { it.segment.serviceRunId } shouldBe listOf("run-a", "run-b")
		entry.windows.map(PressureHistoryWindow::logicalFactId) shouldBe listOf("fact-a", "fact-b")
		entry.zoneAuthorities shouldBe linkedSetOf("Europe/Prague", "America/New_York")
		entry.qualifiedSources shouldBe setOf(TrackingSourceComponent.PRESSURE)
		entry.isOrdinarilyDiscoverable shouldBe true
		entry.hasExactPressureOnlyIntent shouldBe true
		entry.windows.first().sampleVarianceHectopascalsSquared shouldBe 0.5
		entry.windows.first().actualToExpectedSampleRatio shouldBe 1.0
		entry.summary shouldBe PressureHistorySummary(
			firstHectopascals = 1001f,
			latestHectopascals = 998f,
			minimumHectopascals = 997.5f,
			maximumHectopascals = 1001.5f,
			windowCount = 2,
		)
	}

	@Test
	fun overlappingWallTimeDoesNotGroupDifferentLogicalEntries() {
		val first = available(
			segment = segment(id = 1L, logicalId = "logical-a", runId = "run-a", startMs = 100L),
			window = window("fact-a", "run-a", "UTC", 10L, 1000f, 1000f),
		)
		val overlapping = available(
			segment = segment(id = 2L, logicalId = "logical-b", runId = "run-b", startMs = 100L),
			window = window("fact-b", "run-b", "UTC", 20L, 1000f, 1000f),
		)

		PressureLogicalHistoryComposer.compose(listOf(first, overlapping)).map { it.identity } shouldBe
			listOf(
				PressureHistoryEntryIdentity.Logical("logical-a"),
				PressureHistoryEntryIdentity.Logical("logical-b"),
			)
	}

	@Test
	fun incompleteMembershipStaysPhysicalAndMissingPressureNeverBecomesZero() {
		val incomplete = PressurePhysicalHistory(
			segment = segment(id = 3L, logicalId = "logical", runId = null, startMs = 300L),
			captureAuthority = HistoricalCaptureAuthority.Unverifiable(
				HistoricalCaptureFailure.SEGMENT_MEMBERSHIP_INCOMPLETE,
			),
			windows = emptyList(),
			availability = PressureHistoryAvailability.UNAVAILABLE,
			evidence = PressureHistoryEvidence.NO_OBSERVATION,
			materialization = PressureHistoryMaterialization.FAILED,
			coverage = PressureHistoryCoverage.UNKNOWN,
			reasons = setOf(PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE),
		)

		val entry = PressureLogicalHistoryComposer.compose(listOf(incomplete)).single()

		entry.identity shouldBe PressureHistoryEntryIdentity.Physical(3L)
		entry.summary shouldBe null
		entry.qualifiedSources shouldBe emptySet()
		entry.isOrdinarilyDiscoverable shouldBe false
	}

	private fun available(
		segment: SessionSegment,
		window: PressureHistoryWindow,
	): PressurePhysicalHistory {
		val attributedWindow = window.copy(
			logicalTrackingId = requireNotNull(segment.logicalTrackingId),
		)
		return PressurePhysicalHistory(
			segment = segment,
			captureAuthority = HistoricalCaptureAuthority.Exact(
				listOf(
					HistoricalCaptureRevision(
						manifestRevision = window.manifestRevision,
						effectiveWallTimeMs = segment.startTimeMs,
						capturedSources = setOf(TrackingSourceComponent.PRESSURE),
						controlSources = emptySet(),
					),
				),
			),
			windows = listOf(attributedWindow),
			availability = PressureHistoryAvailability.AVAILABLE,
			evidence = PressureHistoryEvidence.RECORDED,
			materialization = PressureHistoryMaterialization.READY,
			coverage = PressureHistoryCoverage.COMPLETE,
			reasons = emptySet(),
		)
	}

	@Suppress("LongParameterList")
	private fun window(
		factId: String,
		runId: String,
		zoneId: String,
		startElapsedNanos: Long,
		first: Float,
		last: Float,
		manifestRevision: Long = 1L,
	) = PressureHistoryWindow(
		logicalFactId = factId,
		semanticRevision = 1L,
		sourceAdmissionOrdinal = startElapsedNanos,
		logicalTrackingId = "logical",
		serviceRunId = runId,
		manifestRevision = manifestRevision,
		zoneId = zoneId,
		writerProjectionId = "pressure-session-facts",
		writerProjectionVersion = 1,
		writerBindingGeneration = 1L,
		intervalStartTimeMs = startElapsedNanos,
		intervalEndTimeMs = startElapsedNanos + 1L,
		wallTimeUncertaintyMs = 0L,
		windowStartElapsedRealtimeNanos = startElapsedNanos,
		windowEndElapsedRealtimeNanos = startElapsedNanos + 1L,
		sampleCount = 2,
		meanHectopascals = (first.toDouble() + last.toDouble()) / 2.0,
		sumSquaredDeviations = 0.5,
		minimumHectopascals = minOf(first, last) - 0.5f,
		maximumHectopascals = maxOf(first, last) + 0.5f,
		firstHectopascals = first,
		lastHectopascals = last,
		slopeHectopascalsPerSecond = 0.0,
		rSquared = 1.0,
		sensorAccuracy = "HIGH",
		effectiveSamplePeriodMicros = 1,
		effectiveMaximumReportLatencyMicros = 0,
		targetWindowDurationNanos = 2_000L,
		expectedSampleCount = 2,
		maximumInterSampleGapNanos = 1L,
		closureKind = "TARGET_ELAPSED",
		qualification = "COMPLETE",
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
	)

	private fun segment(
		id: Long,
		logicalId: String?,
		runId: String?,
		startMs: Long,
	) = SessionSegment(
		id = id,
		startTimeMs = startMs,
		endTimeMs = startMs + 100L,
		distanceM = 0f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = "test",
		createdAt = startMs + 100L,
		logicalTrackingId = logicalId,
		serviceRunId = runId,
	)
}
