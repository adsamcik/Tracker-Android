package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent

/** One checksum-verified immutable capture/control set within a physical service run. */
internal data class HistoricalCaptureRevision(
	val manifestRevision: Long,
	val effectiveWallTimeMs: Long,
	val capturedSources: Set<TrackingSourceComponent>,
	val controlSources: Set<TrackingSourceComponent>,
) {
	init {
		require(manifestRevision > 0L)
		require(effectiveWallTimeMs >= 0L)
	}
}

/** Exact manifest authority or a typed reason why it cannot be reconstructed. */
internal sealed interface HistoricalCaptureAuthority {
	data class Exact(
		val revisions: List<HistoricalCaptureRevision>,
	) : HistoricalCaptureAuthority {
		init {
			require(revisions.isNotEmpty())
			require(revisions.zipWithNext().all { (left, right) ->
				left.manifestRevision < right.manifestRevision
			})
			require(revisions.any { it.capturedSources.isNotEmpty() })
		}

		val capturedInAnyRevision: Set<TrackingSourceComponent> =
			revisions.flatMapTo(linkedSetOf()) { it.capturedSources }

		val capturedForWholeRun: Set<TrackingSourceComponent> =
			revisions.drop(1).fold(revisions.first().capturedSources) { common, revision ->
				common intersect revision.capturedSources
			}
	}

	data class Unverifiable(
		val reason: HistoricalCaptureFailure,
	) : HistoricalCaptureAuthority
}

internal enum class HistoricalCaptureFailure {
	LEGACY_UNATTRIBUTED,
	SEGMENT_MEMBERSHIP_INCOMPLETE,
	SERVICE_RUN_MISSING,
	SERVICE_RUN_MEMBERSHIP_MISMATCH,
	SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
	SERVICE_RUN_SEGMENT_BINDING_MISMATCH,
	MANIFEST_MISSING,
	MANIFEST_MEMBERSHIP_MISMATCH,
	MANIFEST_INTEGRITY_FAILED,
	BATCH_DEPENDENCY_OVERFLOW,
	UNKNOWN_PURPOSE,
	UNKNOWN_SOURCE_KIND,
	NO_CAPTURE_SOURCE,
}

/** One physical presentation row plus its exact historical intent and Steps product evidence. */
internal data class HistoricalSegmentEvidence(
	val segment: SessionSegment,
	val captureAuthority: HistoricalCaptureAuthority,
	val steps: StepsSegmentHistoryResult,
) {
	/** Sources with exact capture intent and source-local product evidence suitable for discovery. */
	val qualifiedSources: Set<TrackingSourceComponent>
		get() = buildSet {
			val capture = captureAuthority as? HistoricalCaptureAuthority.Exact ?: return@buildSet
			if (
				TrackingSourceComponent.STEPS in capture.capturedInAnyRevision &&
				steps.availability == StepsHistoryAvailability.AVAILABLE &&
				steps.evidence in qualifiedStepsEvidence
			) {
				add(TrackingSourceComponent.STEPS)
			}
		}

	/**
	 * Only genuinely migrated/unattributed rows may use the positive-sample compatibility path, and
	 * even those qualify no source. Attributed v28 rows need exact source-local evidence.
	 */
	val isOrdinarilyDiscoverable: Boolean
		get() = isLegacyCompatibilityRow || qualifiedSources.isNotEmpty() ||
			hasAuthenticatedRetentionTruncationEvidence

	/** Authenticated loss evidence keeps the entry visible but never qualifies a numeric source. */
	val hasAuthenticatedRetentionTruncationEvidence: Boolean
		get() {
			val capture = captureAuthority as? HistoricalCaptureAuthority.Exact ?: return false
			return TrackingSourceComponent.STEPS in capture.capturedInAnyRevision &&
				steps.availability == StepsHistoryAvailability.UNAVAILABLE &&
				StepsHistoryReason.RETENTION_TRUNCATED_RUN in steps.reasons
		}

	private val isLegacyCompatibilityRow: Boolean
		get() = segment.sampleCount > 0 && (
			(segment.logicalTrackingId == null && segment.serviceRunId == null) ||
			captureAuthority == HistoricalCaptureAuthority.Unverifiable(
				HistoricalCaptureFailure.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
			)
		)

	private companion object {
		val qualifiedStepsEvidence = setOf(
			StepsHistoryEvidence.COVERED_ZERO,
			StepsHistoryEvidence.RECORDED,
			StepsHistoryEvidence.LEGACY_RECORDED,
		)
	}
}

/** Stable product identity for a logical tracking entry or one unattributed compatibility row. */
internal sealed interface HistoricalEntryIdentity {
	data class Logical(
		val logicalTrackingId: String,
	) : HistoricalEntryIdentity {
		init {
			require(logicalTrackingId.isNotBlank())
		}
	}

	data class LegacyPhysical(
		val segmentId: Long,
	) : HistoricalEntryIdentity {
		init {
			require(segmentId > 0L)
		}
	}
}

/**
 * One product entry composed from explicitly identified physical members without inventing totals.
 *
 * Physical members remain ordered oldest-first and retain their segment, service-run, manifest,
 * capture, fence, completeness, and source-product state independently. Logical identity alone
 * grants neither source qualification nor mutation authority to a typed legacy-unverifiable member.
 */
internal data class HistoricalTrackingEntryEvidence(
	val identity: HistoricalEntryIdentity,
	val physicalMembers: List<HistoricalSegmentEvidence>,
) {
	init {
		require(physicalMembers.isNotEmpty())
		require(physicalMembers.all { it.entryIdentity == identity })
		require(physicalMembers == physicalMembers.sortedWith(physicalMemberOrder))
		if (identity is HistoricalEntryIdentity.LegacyPhysical) {
			require(physicalMembers.size == 1)
		}
	}

	/** Union of sources qualified by at least one exact physical member. */
	val qualifiedSources: Set<TrackingSourceComponent>
		get() = physicalMembers.flatMapTo(linkedSetOf()) { it.qualifiedSources }

	/** A logical entry is visible only when at least one member has real discovery evidence. */
	val isOrdinarilyDiscoverable: Boolean
		get() = physicalMembers.any(HistoricalSegmentEvidence::isOrdinarilyDiscoverable)

	/** Every retained capture revision requested Steps and no other persisted capture source. */
	val isExactStepsOnlyCapture: Boolean
		get() = TrackingSourceComponent.STEPS in qualifiedSources && hasExactStepsOnlyIntent

	/** Exact Steps-only rows retained for the list even when retention removed their numeric facts. */
	val isContainedStepsOnlyEntry: Boolean
		get() = hasExactStepsOnlyIntent && (
			isExactStepsOnlyCapture ||
				physicalMembers.any(HistoricalSegmentEvidence::hasAuthenticatedRetentionTruncationEvidence)
			)

	/** Exact Steps-only historical intent, independent of current product qualification. */
	val hasExactStepsOnlyIntent: Boolean
		get() {
			val exactCaptures = physicalMembers.map { member ->
				member.captureAuthority as? HistoricalCaptureAuthority.Exact ?: return false
			}
			if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
					exactCaptures.map { capture ->
						capture.revisions.map(HistoricalCaptureRevision::manifestRevision)
					},
				)
			) {
				return false
			}
			return exactCaptures.all { capture ->
				capture.revisions.all { revision ->
					revision.capturedSources == setOf(TrackingSourceComponent.STEPS)
				}
			}
		}

	/** Newest authoritative physical member used only for stable product recency ordering. */
	val newestPhysicalMember: HistoricalSegmentEvidence
		get() = physicalMembers.maxWith(physicalMemberOrder)
}

/** Internal finite-page composition; only the public mapper may expose these rows. */
internal sealed interface HistoricalStepsAwarePageEntry {
	val recencyStartTimeMs: Long
	val recencySegmentId: Long

	data class Physical(
		val segment: SessionSegment,
	) : HistoricalStepsAwarePageEntry {
		override val recencyStartTimeMs: Long get() = segment.startTimeMs
		override val recencySegmentId: Long get() = segment.id
	}

	data class StepsOnly(
		val history: HistoricalTrackingEntryEvidence,
	) : HistoricalStepsAwarePageEntry {
		override val recencyStartTimeMs: Long
			get() = history.newestPhysicalMember.segment.startTimeMs
		override val recencySegmentId: Long
			get() = history.newestPhysicalMember.segment.id
	}
}

/** Explicit identity only; wall-time overlap is never membership authority. */
internal val HistoricalSegmentEvidence.entryIdentity: HistoricalEntryIdentity?
	get() = segment.logicalTrackingId?.takeIf(String::isNotBlank)?.let {
		HistoricalEntryIdentity.Logical(it)
	} ?: if (segment.logicalTrackingId == null && segment.serviceRunId == null) {
		HistoricalEntryIdentity.LegacyPhysical(segment.id)
	} else {
		null
	}

internal val physicalMemberOrder = compareBy<HistoricalSegmentEvidence>(
	{ it.segment.startTimeMs },
	{ it.segment.id },
)

internal const val HISTORY_SEGMENT_BATCH_CAP = 64

/** Reconstructs checksum-verified manifest intent without consulting current source policy. */
internal fun historicalCaptureAuthority(
	manifests: List<SessionManifestVersionEntity>,
	sourcesByRevision: Map<Long, List<SessionManifestSourceEntity>>,
): HistoricalCaptureAuthority {
	val revisions = manifests.map { manifest ->
		val sources = sourcesByRevision.getValue(manifest.manifestRevision)
		if (sources.any { it.purpose !in SessionManifestPurposeCode.ALL }) {
			return HistoricalCaptureAuthority.Unverifiable(HistoricalCaptureFailure.UNKNOWN_PURPOSE)
		}
		val decodedSources = sources.associateWith { source ->
			runCatching { TrackingSourceComponent.fromStableCode(source.sourceKind) }.getOrNull()
		}
		if (decodedSources.values.any { it == null }) {
			return HistoricalCaptureAuthority.Unverifiable(HistoricalCaptureFailure.UNKNOWN_SOURCE_KIND)
		}
		HistoricalCaptureRevision(
			manifestRevision = manifest.manifestRevision,
			effectiveWallTimeMs = manifest.effectiveWallTimeMs,
			capturedSources = decodedSources.entries.filter { (source, _) ->
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					source.persistenceEligible
			}.mapTo(linkedSetOf()) { (_, decoded) -> requireNotNull(decoded) },
			controlSources = decodedSources.entries.filter { (source, _) ->
				source.purpose == SessionManifestPurposeCode.CONTROL
			}.mapTo(linkedSetOf()) { (_, decoded) -> requireNotNull(decoded) },
		)
	}
	return if (revisions.none { it.capturedSources.isNotEmpty() }) {
		HistoricalCaptureAuthority.Unverifiable(HistoricalCaptureFailure.NO_CAPTURE_SOURCE)
	} else {
		HistoricalCaptureAuthority.Exact(revisions)
	}
}
