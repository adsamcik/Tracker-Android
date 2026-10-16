package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity

internal fun ActivityCapturedPersistedRevision.hasCanonicalFragmentsAndEvidence(): Boolean {
	if (!hasCanonicalActivityFragments()) {
		return false
	}
	val bands = fragments.filter { fragment -> fragment.fragmentKind == ActivityCapturedFragmentEntity.KIND_BAND }
	if (bands.withIndex().any { (index, band) -> band.bandOrdinal != index }) {
		return false
	}
	return hasCanonicalActivityEvidence(bands)
}

private fun ActivityCapturedPersistedRevision.hasCanonicalActivityFragments(): Boolean {
	val revision = revision
	if (fragments.isEmpty() || fragments.withIndex().any { (index, fragment) ->
		fragment.logicalWindowId != revision.logicalWindowId ||
			fragment.semanticRevision != revision.semanticRevision ||
			fragment.fragmentOrdinal != index
	}) {
		return false
	}
	if (fragments.first().intervalStartElapsedRealtimeNanos !=
		revision.windowStartElapsedRealtimeNanos ||
		fragments.last().intervalEndElapsedRealtimeNanos !=
		revision.windowEndElapsedRealtimeNanos ||
		fragments.zipWithNext().any { (left, right) ->
			left.intervalEndElapsedRealtimeNanos != right.intervalStartElapsedRealtimeNanos
		}
	) {
		return false
	}
	return true
}

private fun ActivityCapturedPersistedRevision.hasCanonicalActivityEvidence(
	bands: List<ActivityCapturedFragmentEntity>,
): Boolean {
	val revision = revision
	val bandsByFragment = bands.associateBy(ActivityCapturedFragmentEntity::fragmentOrdinal)
	if (evidence.any { item ->
		item.logicalWindowId != revision.logicalWindowId ||
			item.semanticRevision != revision.semanticRevision ||
			item.fragmentOrdinal !in bandsByFragment
	}) {
		return false
	}
	val evidenceByFragment = evidence.groupBy(ActivityCapturedEvidenceEntity::fragmentOrdinal)
	if (bands.any { band -> evidenceByFragment[band.fragmentOrdinal].isNullOrEmpty() }) {
		return false
	}
	return evidenceByFragment.all { (fragmentOrdinal, items) ->
		val band = bandsByFragment.getValue(fragmentOrdinal)
		items.withIndex().all { (index, item) ->
			item.matchesCapturedActivityBandEvidence(index, band, revision.windowEndElapsedRealtimeNanos)
		}
	}
}

private fun ActivityCapturedEvidenceEntity.matchesCapturedActivityBandEvidence(
	index: Int, band: ActivityCapturedFragmentEntity, windowEndElapsedRealtimeNanos: Long,
): Boolean = evidenceOrdinal == index && providerElapsedRealtimeNanos >= band.intervalStartElapsedRealtimeNanos &&
	providerElapsedRealtimeNanos <= band.intervalEndElapsedRealtimeNanos &&
	coverageEndExclusiveElapsedRealtimeNanos?.let { end -> end <= windowEndElapsedRealtimeNanos } != false

internal fun ActivityCapturedWindowCursorEntity.matchesCapturedActivityScope(
	latest: ActivityCapturedWindowRevisionEntity,
): Boolean = logicalTrackingId == latest.logicalTrackingId && serviceRunId == latest.serviceRunId &&
	sessionSegmentId == latest.sessionSegmentId && writerOwnerGeneration == latest.writerOwnerGeneration

internal fun ActivityCapturedWindowCursorEntity.matchesCapturedActivityPublication(
	latest: ActivityCapturedWindowRevisionEntity,
): Boolean = latestSemanticRevision == latest.semanticRevision && latestMutationId == latest.mutationId &&
	latestEffectChecksum == latest.effectChecksum && cursorRevision == latest.semanticRevision &&
	collectedDataEpoch == latest.collectedDataEpoch && updatedAtMs == latest.appliedAtMs

