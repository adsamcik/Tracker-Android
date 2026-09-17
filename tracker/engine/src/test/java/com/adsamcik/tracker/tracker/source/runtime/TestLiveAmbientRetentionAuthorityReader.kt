package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityReader
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent

internal class TestLiveAmbientRetentionAuthorityReader(
	var current: Boolean = true,
	var approvalRevision: Long = 1L,
) : RetentionAuthorityReader {
	private val observed = mutableMapOf<TrackingSourceComponent, Triple<Long, Long, Long>>()

	override suspend fun currentLiveAmbient(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
	): CurrentRetentionAuthority = if (current) {
		observed[source] = Triple(
			expectedSourcePolicyRevision,
			expectedAmbientConsentEpoch,
			expectedCollectedDataEpoch,
		)
		CurrentRetentionAuthority.Approved(
			opaquePolicyId = source.liveAmbientPolicyId(),
			approvalRevision = approvalRevision,
			effectiveBootId = "test-retention-boot",
			effectiveElapsedRealtimeNanos = 0L,
			effectiveWallTimeMs = 0L,
		)
	} else {
		CurrentRetentionAuthority.Unavailable(
			RetentionAuthorityUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE,
		)
	}

	override suspend fun isCurrentLiveAmbientAt(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedOpaquePolicyId: String,
		expectedApprovalRevision: Long,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
	): Boolean {
		val expected = observed[source]
		return current &&
			(expected == null || expected == Triple(
				expectedSourcePolicyRevision,
				expectedAmbientConsentEpoch,
				expectedCollectedDataEpoch,
			)) &&
			expectedOpaquePolicyId == source.liveAmbientPolicyId() &&
			expectedApprovalRevision == approvalRevision
	}
}

private fun TrackingSourceComponent.liveAmbientPolicyId(): String =
	"privacy:${name.lowercase()}:ambient:v1"
