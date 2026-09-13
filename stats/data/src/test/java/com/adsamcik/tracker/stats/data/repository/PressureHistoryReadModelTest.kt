package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import io.kotest.matchers.shouldBe
import org.junit.Test

class PressureHistoryReadModelTest {
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
		windowStartElapsedRealtimeNanos = startElapsedNanos,
		windowEndElapsedRealtimeNanos = startElapsedNanos + 1L,
		sampleCount = 2,
		meanHectopascals = (first.toDouble() + last.toDouble()) / 2.0,
		minimumHectopascals = minOf(first, last) - 0.5f,
		maximumHectopascals = maxOf(first, last) + 0.5f,
		firstHectopascals = first,
		lastHectopascals = last,
		slopeHectopascalsPerSecond = 0.0,
		rSquared = 1.0,
		qualification = "COMPLETE",
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
