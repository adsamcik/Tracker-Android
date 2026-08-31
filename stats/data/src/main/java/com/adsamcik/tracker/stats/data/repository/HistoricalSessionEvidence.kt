package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
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
		get() = isLegacyCompatibilityRow || qualifiedSources.isNotEmpty()

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
