package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent

/** One effective, checksum-verified Pressure fact with its immutable physical ownership. */
@Suppress("LongParameterList")
internal data class PressureHistoryWindow(
	val logicalFactId: String,
	val semanticRevision: Long,
	val sourceAdmissionOrdinal: Long,
	val logicalTrackingId: String,
	val serviceRunId: String,
	val manifestRevision: Long,
	val zoneId: String,
	val writerProjectionId: String,
	val writerProjectionVersion: Int,
	val writerBindingGeneration: Long,
	val intervalStartTimeMs: Long,
	val intervalEndTimeMs: Long,
	val windowStartElapsedRealtimeNanos: Long,
	val windowEndElapsedRealtimeNanos: Long,
	val sampleCount: Int,
	val meanHectopascals: Double,
	/** Welford M2 retained so stability/variance is not reconstructed from rounded extrema. */
	val sumSquaredDeviations: Double,
	val minimumHectopascals: Float,
	val maximumHectopascals: Float,
	val firstHectopascals: Float,
	val lastHectopascals: Float,
	val slopeHectopascalsPerSecond: Double?,
	val rSquared: Double?,
	val sensorAccuracy: String,
	val effectiveSamplePeriodMicros: Int,
	val effectiveMaximumReportLatencyMicros: Int,
	val targetWindowDurationNanos: Long,
	val expectedSampleCount: Int,
	val maximumInterSampleGapNanos: Long,
	val closureKind: String,
	val qualification: String,
	val sourceQualityFlags: Long,
	val sourceQualityConfidence: Float?,
) {
	init {
		require(logicalFactId.isNotBlank())
		require(semanticRevision > 0L)
		require(sourceAdmissionOrdinal > 0L)
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(manifestRevision > 0L)
		require(zoneId.isNotBlank())
		require(writerProjectionId.isNotBlank())
		require(writerProjectionVersion > 0)
		require(writerBindingGeneration > 0L)
		require(intervalStartTimeMs >= 0L && intervalEndTimeMs >= intervalStartTimeMs)
		require(
			windowStartElapsedRealtimeNanos >= 0L &&
				windowEndElapsedRealtimeNanos >= windowStartElapsedRealtimeNanos,
		)
		require(sampleCount > 0)
		require(meanHectopascals.isFinite() && meanHectopascals > 0.0)
		require(sumSquaredDeviations.isFinite() && sumSquaredDeviations >= 0.0)
		require(minimumHectopascals.isFinite() && minimumHectopascals > 0f)
		require(maximumHectopascals.isFinite() && maximumHectopascals >= minimumHectopascals)
		require(firstHectopascals in minimumHectopascals..maximumHectopascals)
		require(lastHectopascals in minimumHectopascals..maximumHectopascals)
		require(slopeHectopascalsPerSecond == null || slopeHectopascalsPerSecond.isFinite())
		require(rSquared == null || rSquared.isFinite() && rSquared in 0.0..1.0)
		require(sensorAccuracy.isNotBlank())
		require(effectiveSamplePeriodMicros > 0)
		require(effectiveMaximumReportLatencyMicros >= 0)
		require(targetWindowDurationNanos > 0L)
		require(expectedSampleCount > 0)
		require(maximumInterSampleGapNanos >= 0L)
		require(closureKind.isNotBlank())
		require(qualification.isNotBlank())
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence == null || sourceQualityConfidence in 0f..1f)
	}

	/** Sample variance derived from retained Welford M2; one observation has no sample variance. */
	val sampleVarianceHectopascalsSquared: Double?
		get() = if (sampleCount > 1) sumSquaredDeviations / (sampleCount - 1).toDouble() else null

	/** Unclamped actual/expected ratio preserves both missing samples and legitimate oversampling. */
	val actualToExpectedSampleRatio: Double
		get() = sampleCount.toDouble() / expectedSampleCount.toDouble()
}

/** Truthful source-local state for one presentation segment and its exact physical run. */
@Suppress("LongParameterList")
internal data class PressurePhysicalHistory(
	val segment: SessionSegment,
	val captureAuthority: HistoricalCaptureAuthority,
	val windows: List<PressureHistoryWindow>,
	val availability: PressureHistoryAvailability,
	val evidence: PressureHistoryEvidence,
	val materialization: PressureHistoryMaterialization,
	val coverage: PressureHistoryCoverage,
	val reasons: Set<PressureHistoryReason>,
) {
	init {
		require(windows == windows.sortedWith(pressureWindowOrder))
		require(windows.map(PressureHistoryWindow::logicalFactId).distinct().size == windows.size)
		val serviceRunId = segment.serviceRunId
		val logicalTrackingId = segment.logicalTrackingId
		require(serviceRunId == null || windows.all { it.serviceRunId == serviceRunId })
		require(
			logicalTrackingId == null || windows.all { it.logicalTrackingId == logicalTrackingId },
		)
		when (evidence) {
			PressureHistoryEvidence.NO_OBSERVATION -> require(windows.isEmpty())
			PressureHistoryEvidence.RECORDED -> require(windows.isNotEmpty())
		}
		if (availability != PressureHistoryAvailability.AVAILABLE) require(windows.isEmpty())
	}

	/** Pressure is qualified only by retained Pressure facts, never a generic segment count. */
	val qualifiedSources: Set<TrackingSourceComponent>
		get() = if (
			availability == PressureHistoryAvailability.AVAILABLE &&
			evidence == PressureHistoryEvidence.RECORDED
		) {
			setOf(TrackingSourceComponent.PRESSURE)
		} else {
			emptySet()
		}
}

internal enum class PressureHistoryAvailability { DISABLED, AVAILABLE, DELETED, UNAVAILABLE }
internal enum class PressureHistoryEvidence { NO_OBSERVATION, RECORDED }
internal enum class PressureHistoryMaterialization { NOT_APPLICABLE, MATERIALIZING, READY, FAILED }
internal enum class PressureHistoryCoverage { NONE, COMPLETE, PARTIAL, UNKNOWN }

internal enum class PressureHistoryReason {
	SOURCE_NOT_CAPTURED,
	LEGACY_UNATTRIBUTED,
	SEGMENT_MEMBERSHIP_INCOMPLETE,
	LOGICAL_MANIFEST_REVISION_UNION_INVALID,
	SERVICE_RUN_MISSING,
	SERVICE_RUN_MEMBERSHIP_MISMATCH,
	SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
	SERVICE_RUN_SEGMENT_BINDING_MISMATCH,
	MANIFEST_MISSING,
	MANIFEST_MEMBERSHIP_MISMATCH,
	MANIFEST_INTEGRITY_FAILED,
	SOURCE_POLICY_ATTRIBUTION_INVALID,
	MIXED_WRITER_WITHIN_SERVICE_RUN,
	UNKNOWN_WRITER,
	PRODUCT_LANE_MISSING,
	PRODUCT_LANE_INVALID,
	SOURCE_EVIDENCE_STATE_MISSING,
	OUTSIDE_RETAINED_FLOOR,
	RETENTION_CROSSES_SEGMENT,
	RETENTION_TRUNCATED,
	RETENTION_TRUNCATION_MARKER_INVALID,
	COMPLETENESS_MISSING,
	COMPLETENESS_INVALID,
	APP_DRAIN_INCOMPLETE,
	STOP_INCOMPLETE,
	UNRESOLVED_PROVIDER_SEQUENCE,
	PROVIDER_COMPLETENESS_UNOBSERVABLE,
	PROVIDER_UNAVAILABLE,
	UNAVAILABLE_SENTINEL_WITH_RETAINED_FACTS,
	CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN,
	SERVICE_RUN_ACTIVE,
	FACTS_MISSING_FOR_ADMITTED_RUN,
	PRESSURE_FACT_INTEGRITY_FAILED,
	PRESSURE_FACT_CORRECTION_INCOMPLETE,
	STALE_COLLECTED_DATA_EPOCH,
	PARTIAL_FACT,
	TARGET_BEFORE_LANE_ACTIVATION,
	TERMINAL_PROJECTION_FAILURE,
	PRODUCT_LANE_CUTOFF_BEFORE_TARGET,
	PRODUCT_LANE_RETIRED_BEFORE_TARGET,
	PRODUCT_LANE_BEHIND,
	DELETED_FACTS,
	BATCH_DEPENDENCY_OVERFLOW,
}

/** Stable grouping key; incomplete or migrated ownership remains a singleton physical entry. */
internal sealed interface PressureHistoryEntryIdentity {
	data class Logical(val logicalTrackingId: String) : PressureHistoryEntryIdentity {
		init {
			require(logicalTrackingId.isNotBlank())
		}
	}

	data class Physical(val segmentId: Long) : PressureHistoryEntryIdentity {
		init {
			require(segmentId > 0L)
		}
	}
}

/** A contained summary that exists only when one or more real Pressure windows exist. */
internal data class PressureHistorySummary(
	val firstHectopascals: Float,
	val latestHectopascals: Float,
	val minimumHectopascals: Float,
	val maximumHectopascals: Float,
	val windowCount: Int,
) {
	init {
		require(firstHectopascals.isFinite() && firstHectopascals > 0f)
		require(latestHectopascals.isFinite() && latestHectopascals > 0f)
		require(minimumHectopascals.isFinite() && minimumHectopascals > 0f)
		require(maximumHectopascals.isFinite() && maximumHectopascals >= minimumHectopascals)
		require(windowCount > 0)
	}
}

/**
 * One logical tracking entry composed only from explicitly identified physical replacement runs.
 *
 * Physical members and their per-window stored zones remain authoritative. The convenience summary
 * never invents an aggregate zone, interpolates gaps, or derives ownership from wall-time overlap.
 */
internal data class PressureLogicalHistoryEntry(
	val identity: PressureHistoryEntryIdentity,
	val physicalMembers: List<PressurePhysicalHistory>,
) {
	init {
		require(physicalMembers.isNotEmpty())
		require(physicalMembers.all { it.pressureEntryIdentity == identity })
		require(physicalMembers == physicalMembers.sortedWith(pressurePhysicalMemberOrder))
		if (identity is PressureHistoryEntryIdentity.Physical) require(physicalMembers.size == 1)
	}

	/** Effective windows remain partitioned by physical run and ordered by elapsed time within it. */
	val windows: List<PressureHistoryWindow>
		get() = physicalMembers.flatMap(PressurePhysicalHistory::windows)

	/** Every stored manifest zone encountered; no synthetic logical-entry zone is selected. */
	val zoneAuthorities: Set<String>
		get() = windows.mapTo(linkedSetOf(), PressureHistoryWindow::zoneId)

	val summary: PressureHistorySummary?
		get() {
			val facts = windows
			if (facts.isEmpty()) return null
			return PressureHistorySummary(
				firstHectopascals = facts.first().firstHectopascals,
				latestHectopascals = facts.last().lastHectopascals,
				minimumHectopascals = facts.minOf(PressureHistoryWindow::minimumHectopascals),
				maximumHectopascals = facts.maxOf(PressureHistoryWindow::maximumHectopascals),
				windowCount = facts.size,
			)
		}

	val qualifiedSources: Set<TrackingSourceComponent>
		get() = physicalMembers.flatMapTo(linkedSetOf(), PressurePhysicalHistory::qualifiedSources)

	/**
	 * A current-epoch, source-local retention marker can keep a pruned entry discoverable, but is
	 * never a qualified observation. Any failed physical sibling prevents the marker from serving as
	 * discovery authority for the logical group.
	 */
	val hasAuthenticatedRetentionLoss: Boolean
		get() = physicalMembers.any { member ->
			PressureHistoryReason.RETENTION_TRUNCATED in member.reasons
		} && physicalMembers.none { member ->
			member.materialization == PressureHistoryMaterialization.FAILED ||
				PressureHistoryReason.RETENTION_TRUNCATION_MARKER_INVALID in member.reasons
		}

	val isOrdinarilyDiscoverable: Boolean
		get() = TrackingSourceComponent.PRESSURE in qualifiedSources || hasAuthenticatedRetentionLoss

	/** One physical member owns both recency fields; independently maximizing them is not a tuple. */
	val recencyMember: PressurePhysicalHistory
		get() = physicalMembers.maxWith(pressurePhysicalRecencyOrder)

	/** Exact Pressure-only intent is independent of current fact/materialization availability. */
	val hasExactPressureOnlyIntent: Boolean
		get() {
			val captures = physicalMembers.map { member ->
				member.captureAuthority as? HistoricalCaptureAuthority.Exact ?: return false
			}
			if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
					captures.map { capture ->
						capture.revisions.map(HistoricalCaptureRevision::manifestRevision)
					},
				)
			) return false
			return captures.all { capture -> capture.revisions.all { revision ->
				revision.capturedSources == setOf(TrackingSourceComponent.PRESSURE)
			} }
		}
}

/** Explicit logical identity only; wall-time proximity never groups replacement runs. */
internal object PressureLogicalHistoryComposer {
	fun compose(physical: List<PressurePhysicalHistory>): List<PressureLogicalHistoryEntry> =
		physical.groupBy(PressurePhysicalHistory::pressureEntryIdentity)
			.map { (identity, members) ->
				PressureLogicalHistoryEntry(
					identity = identity,
					physicalMembers = members.sortedWith(pressurePhysicalMemberOrder),
				)
			}.sortedWith(compareBy(
				{ it.physicalMembers.first().segment.startTimeMs },
				{ it.physicalMembers.first().segment.id },
			))
}

private val PressurePhysicalHistory.pressureEntryIdentity: PressureHistoryEntryIdentity
	get() {
		val logicalTrackingId = segment.logicalTrackingId?.takeIf(String::isNotBlank)
		val serviceRunId = segment.serviceRunId?.takeIf(String::isNotBlank)
		return if (
			logicalTrackingId != null && serviceRunId != null &&
			captureAuthority is HistoricalCaptureAuthority.Exact
		) {
			PressureHistoryEntryIdentity.Logical(logicalTrackingId)
		} else {
			PressureHistoryEntryIdentity.Physical(segment.id)
		}
	}

private val pressureWindowOrder = compareBy<PressureHistoryWindow>(
	{ it.windowStartElapsedRealtimeNanos },
	{ it.windowEndElapsedRealtimeNanos },
	PressureHistoryWindow::logicalFactId,
)

private val pressurePhysicalMemberOrder = compareBy<PressurePhysicalHistory>(
	{ history ->
		(history.captureAuthority as? HistoricalCaptureAuthority.Exact)
			?.revisions?.firstOrNull()?.manifestRevision ?: Long.MAX_VALUE
	},
	{ it.segment.startTimeMs },
	{ it.segment.id },
)

private val pressurePhysicalRecencyOrder = compareBy<PressurePhysicalHistory>(
	{ it.segment.startTimeMs },
	{ it.segment.id },
)
