package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityReader
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent

internal class TestLiveAmbientRetentionAuthorityReader(
	var current: Boolean = true,
	var approvalRevision: Long = 1L,
	var effectiveBootId: String = "boot-1",
	var effectiveElapsedRealtimeNanos: Long = 0L,
	var effectiveWallTimeMs: Long = 0L,
	var retainedFromMs: Long? = null,
) : RetentionAuthorityReader {
	private val observed = mutableMapOf<TrackingSourceComponent, Triple<Long, Long, Long>>()

	override suspend fun currentLiveAmbient(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
	): CurrentRetentionAuthority = if (current) {
		observed[source] = Triple(
			expectedSourcePolicyRevision,
			expectedAmbientConsentEpoch,
			expectedCollectedDataEpoch,
		)
		CurrentRetentionAuthority.Approved(
			opaquePolicyId = source.liveAmbientPolicyId(),
			approvalRevision = approvalRevision,
			effectiveBootId = effectiveBootId,
			effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = effectiveWallTimeMs,
			collectedDataEpoch = expectedCollectedDataEpoch,
			retainedFromMs = retainedFromMs,
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
		expectedRetainedFromMs: Long?,
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
			expectedRetainedFromMs == retainedFromMs &&
			expectedOpaquePolicyId == source.liveAmbientPolicyId() &&
			expectedApprovalRevision == approvalRevision
	}

	suspend fun installCurrent(
		database: AppDatabase,
		source: TrackingSourceComponent,
		sourcePolicyRevision: Long,
		ambientConsentEpoch: Long,
		collectedDataEpoch: Long,
		bootId: String,
		effectiveElapsedRealtimeNanos: Long = 0L,
		effectiveWallTimeMs: Long = 0L,
	): LiveAmbientRetentionSnapshot {
		this.effectiveBootId = bootId
		this.effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos
		this.effectiveWallTimeMs = effectiveWallTimeMs
		val policyId = source.liveAmbientPolicyId()
		when (source) {
			TrackingSourceComponent.STEPS -> database.ambientStepsFactRevisionDao()
				.insertRetentionAuthority(
					AmbientStepsRetentionAuthorityIntegrity.create(
						scope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
						approvalRevision = approvalRevision,
						state = AmbientStepsRetentionAuthorityEntity.STATE_ACTIVE,
						opaquePolicyId = policyId,
						sourcePolicyRevision = sourcePolicyRevision,
						ambientConsentEpoch = ambientConsentEpoch,
						collectedDataEpoch = collectedDataEpoch,
						retainedFromMs = retainedFromMs,
						effectiveBootId = bootId,
						effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
						effectiveWallTimeMs = effectiveWallTimeMs,
					),
				)
			TrackingSourceComponent.WIFI -> database.ambientWifiFactDao()
				.insertRetentionAuthority(
					AmbientWifiRetentionAuthorityIntegrity.create(
						scope = AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
						approvalRevision = approvalRevision,
						state = AmbientWifiRetentionAuthorityEntity.STATE_ACTIVE,
						opaquePolicyId = policyId,
						sourcePolicyRevision = sourcePolicyRevision,
						ambientConsentEpoch = ambientConsentEpoch,
						collectedDataEpoch = collectedDataEpoch,
						retainedFromMs = retainedFromMs,
						effectiveBootId = bootId,
						effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
						effectiveWallTimeMs = effectiveWallTimeMs,
					),
				)
			TrackingSourceComponent.CELL -> database.ambientCellFactDao()
				.insertRetentionAuthority(
					AmbientCellRetentionAuthorityIntegrity.create(
						scope = AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
						approvalRevision = approvalRevision,
						state = AmbientCellRetentionAuthorityEntity.STATE_ACTIVE,
						opaquePolicyId = policyId,
						sourcePolicyRevision = sourcePolicyRevision,
						ambientConsentEpoch = ambientConsentEpoch,
						collectedDataEpoch = collectedDataEpoch,
						retainedFromMs = retainedFromMs,
						effectiveBootId = bootId,
						effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
						effectiveWallTimeMs = effectiveWallTimeMs,
					),
				)
			else -> error("$source has no durable LIVE_AMBIENT retention row")
		}
		val sourceKind = com.adsamcik.tracker.tracker.source.model.SourceKind.valueOf(source.name)
		return LiveAmbientRetentionSnapshot(
			mapOf(
				sourceKind to LiveAmbientRetentionGrant(
					sourceKind,
					sourcePolicyRevision,
					ambientConsentEpoch,
					collectedDataEpoch,
					retainedFromMs,
					policyId,
					approvalRevision,
					bootId,
					effectiveElapsedRealtimeNanos,
					effectiveWallTimeMs,
				),
			),
		)
	}
}

private fun TrackingSourceComponent.liveAmbientPolicyId(): String =
	"privacy:${name.lowercase()}:ambient:v1"
