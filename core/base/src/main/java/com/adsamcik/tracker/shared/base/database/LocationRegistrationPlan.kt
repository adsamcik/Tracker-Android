package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.database.data.LocationRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity

/** Persist after reserving the exact generation and before registering the platform callback. */
suspend fun AppDatabase.recordLocationRegistrationPlan(value: LocationRegistrationPlanEntity) = withTransaction {
	val registration = requireNotNull(sourceBrokerDao().registration(
		SourceDestinationOwnerEntity.SOURCE_LOCATION, value.registrationGeneration))
	check(registration.sourceInstanceId == value.sourceInstanceId &&
		registration.physicalConfigurationFingerprint == value.physicalConfigurationFingerprint)
	val state = sourceRegistrationStateDao().get(registration.sourceKind, registration.ownerScope)
	when (registration.status) {
		ProviderRegistrationGenerationEntity.STATUS_RESERVED -> {
			// The pointer is deliberately published only after provider acceptance. A reservation
			// may publish its immutable request first, but never after a newer reservation won.
			check(sourceBrokerDao().maximumRegistrationGeneration(registration.sourceKind) == value.registrationGeneration)
			check(registration.acceptedAtMs == null && registration.acceptedElapsedRealtimeNanos == null &&
				registration.retiredAtMs == null && registration.retiredElapsedRealtimeNanos == null)
			check(state == null || state.registrationGeneration <= value.registrationGeneration)
		}
		ProviderRegistrationGenerationEntity.STATUS_ACTIVE -> {
			check(state != null && state.sourceInstanceId == value.sourceInstanceId &&
				state.registrationGeneration == value.registrationGeneration &&
				state.clockDomainId == registration.clockDomainId && state.collectedDataEpoch == registration.collectedDataEpoch &&
				state.appliedRevision == value.configurationRevision)
		}
		else -> error("A terminal or retiring Location registration cannot publish a request")
	}
	check(sourceBrokerDao().maximumAuthorizationRevision(registration.sourceKind) == value.authorizationRevision)
	check(sourceEvidenceStateDao().get()?.collectedDataEpoch == registration.collectedDataEpoch)
	val rows = sourceBrokerDao().authorizationRevisionBounded(registration.sourceKind,
		value.registrationGeneration, value.authorizationRevision, 17)
	check(rows.size in 1..16)
	val authorization = requireNotNull(rows.toAuthorizationSnapshotOrNull())
	check(!authorization.isDenied && authorization.effectiveBootId == registration.clockDomainId)
	val ids = authorization.authorizedMembers.map { requireNotNull(it.demandId) }
	val demands = sourceBrokerDao().demandsByIds(ids)
	check(ids.distinct().size == ids.size && demands.size == ids.size)
	val reconstructed = SourceBrokerAuthorization.rows(registration.sourceKind, value.registrationGeneration,
		value.authorizationRevision, demands, authorization.effectiveBootId, authorization.effectiveElapsedRealtimeNanos,
		authorization.members.first().effectiveWallTimeMs)
	check(rows.sortedBy { it.memberId } == reconstructed.sortedBy { it.memberId })
	val dao = locationPurposeDao()
	if (dao.insertRegistrationPlanIfAbsent(value) == -1L) {
		check(dao.registrationPlan(value.registrationGeneration, value.configurationRevision, value.authorizationRevision)?.hasSameValue(value) == true) {
			"An acknowledged Location generation cannot change its physical plan"
		}
	}
}
