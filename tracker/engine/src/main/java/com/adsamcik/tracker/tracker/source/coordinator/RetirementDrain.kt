package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.PressureWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.PressureWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthenticator
import com.adsamcik.tracker.tracker.source.model.SourceKind

/** Settles source-specific terminal coverage after authenticating the exact retired provider. */
internal suspend fun retirementDrainSettlementFailure(
	database: AppDatabase,
	request: SourceProductDrainRequest,
): String? = when (request.source) {
	SourceKind.ACTIVITY -> interruptedActivitySettlementFailure(database, request)
	SourceKind.LOCATION, SourceKind.PRESSURE, SourceKind.WIFI, SourceKind.CELL -> if (
		!hasSettledRetirements(database, request)
	) {
		"SOURCE_DRAIN_SETTLEMENT_INCOMPLETE"
	} else {
		null
	}
	else -> null
}

/** A removed Activity predecessor has no live work to retry, but its saved readings must still drain. */
private suspend fun interruptedActivitySettlementFailure(
	database: AppDatabase,
	request: SourceProductDrainRequest,
): String? = request.memberships.filterNot(SourceDrainMembership::isExactCompleteSettlement)
	.firstNotNullOfOrNull { membership ->
		val receipt = database.sourceSessionDao().rawRunRetirement(
			request.logicalTrackingId, request.serviceRunId, request.source.stableCode,
			membership.sourceInstanceId, membership.registrationGeneration,
		).singleOrNull()?.validatedOrNull()
		val interrupted = receipt?.hasRemovedInterruptedRetirement(database, request) == true
		val savedMembership = receipt?.let {
			SourceDrainMembership(it.sourceInstanceId, it.registrationGeneration, it.lastAdmissionOrdinal,
				it.lastSourceSequence, it.appDrainComplete == true, requireNotNull(it.providerCoverage),
				requireNotNull(it.stopStatus), it.unresolvedSequenceStart, it.unresolvedSequenceEnd)
		}
		"SOURCE_DRAIN_SETTLEMENT_INCOMPLETE".takeUnless {
			interrupted && savedMembership == membership
		}
	}

private fun SourceRunRetirementEntity.isInterruptedSettlement(request: SourceProductDrainRequest): Boolean =
	state == SourceRunRetirementEntity.STATE_INTERRUPTED && stopStatus == "PROCESS_RESTARTED" &&
		registrationRemovalOutcome in setOf("REMOVED", "NOT_REGISTERED") &&
		providerCoverage == "PROVIDER_COMPLETENESS_UNOBSERVABLE" &&
		cutoffElapsedRealtimeNanos == request.cutoffElapsedRealtimeNanos && cutoffWallTimeMs == request.cutoffWallTimeMs

private suspend fun SourceRunRetirementEntity.hasRemovedInterruptedRetirement(
	database: AppDatabase,
	request: SourceProductDrainRequest,
): Boolean {
	if (!isInterruptedSettlement(request)) {
		return false
	}
	val registration = database.sourceBrokerDao().registration(sourceKind, registrationGeneration) ?: return false
	return registration.sourceInstanceId == sourceInstanceId &&
		registration.status == ProviderRegistrationGenerationEntity.STATUS_RETIRED
}

/** A drained, retired actor cannot repair recorded loss; every admitted row must still drain. */
private suspend fun hasSettledRetirements(
	database: AppDatabase,
	request: SourceProductDrainRequest,
): Boolean = database.withTransaction {
	request.memberships.all { membership ->
		membership.isExactCompleteSettlement() || hasSettledRetirement(database, request, membership)
	}
}

private suspend fun hasSettledRetirement(
	database: AppDatabase,
	request: SourceProductDrainRequest,
	membership: SourceDrainMembership,
): Boolean {
	val claim = request.retirementClaims.singleOrNull {
		!it.cleanupOnly && it.sourceInstanceId == membership.sourceInstanceId &&
			it.registrationGeneration == membership.registrationGeneration
	}
	val receipt = database.sourceSessionDao().rawRunRetirement(
		request.logicalTrackingId, request.serviceRunId, request.source.stableCode,
		membership.sourceInstanceId, membership.registrationGeneration,
	).singleOrNull()?.validatedOrNull()
	if (claim == null || receipt == null) {
		return false
	}
	if (receipt.doesNotMatchDrainClaim(request.logicalTrackingId, request.serviceRunId,
			request.source.stableCode, claim)) {
		return false
	}
	return receipt.matchesRetirementMembership(membership) &&
		receipt.matchesRetirementCutoff(request) &&
		hasSettledRetirementEndpoint(database, request, receipt)
}

private suspend fun hasSettledRetirementEndpoint(
	database: AppDatabase,
	request: SourceProductDrainRequest,
	receipt: SourceRunRetirementEntity,
): Boolean = if (
	request.source in setOf(SourceKind.LOCATION, SourceKind.PRESSURE) &&
		receipt.hasRemovedInterruptedRetirement(database, request)
) {
	hasExactInterruptedSensorEndpoint(database, request, receipt)
} else {
	receipt.hasSettledRetiredGap() && hasExactRetirementEndpoint(database, request, receipt)
}

private suspend fun hasExactInterruptedSensorEndpoint(
	database: AppDatabase,
	request: SourceProductDrainRequest,
	receipt: SourceRunRetirementEntity,
): Boolean {
	val highWater = database.sourceBrokerDao().runCaptureAdmissionHighWater(
		request.source.stableCode, receipt.sourceInstanceId, receipt.registrationGeneration,
		request.logicalTrackingId, request.serviceRunId, SourceBrokerPurpose.MASK_SESSION_CAPTURE)
	// Interrupted receipts reconstruct broker WAL order; they do not claim a provider callback barrier.
	if (receipt.lastSourceSequence != highWater.lastSourceSequence ||
		receipt.callbackEntryBarrierSequence != highWater.lastSourceSequence
	) {
		return false
	}
	if (highWater.lastAdmissionOrdinal == 0L) {
		return receipt.lastAdmissionOrdinal == null || receipt.lastAdmissionOrdinal == 0L
	}
	val endpoint = receipt.lastAdmissionOrdinal?.takeIf { it == highWater.lastAdmissionOrdinal }
		?.let { database.sourceEventWalDao().getByAdmissionOrdinal(it) } ?: return false
	return endpoint.sourceSequence == receipt.lastSourceSequence &&
		(request.source == SourceKind.LOCATION ||
			PressureWalCarrierAuthenticator.authenticate(endpoint) is PressureWalCarrierAuthentication.Authenticated)
}

private fun SourceRunRetirementEntity.matchesRetirementCutoff(request: SourceProductDrainRequest): Boolean =
	cutoffElapsedRealtimeNanos == request.cutoffElapsedRealtimeNanos &&
		cutoffWallTimeMs == request.cutoffWallTimeMs

private fun SourceRunRetirementEntity.matchesRetirementMembership(membership: SourceDrainMembership): Boolean =
	SourceDrainMembership(
		sourceInstanceId, registrationGeneration, lastAdmissionOrdinal, lastSourceSequence,
		appDrainComplete == true, requireNotNull(providerCoverage), requireNotNull(stopStatus),
		unresolvedSequenceStart, unresolvedSequenceEnd,
	) == membership

private fun SourceRunRetirementEntity.hasSettledRetiredGap(): Boolean =
	hasSettledActor() && stopStatus in setOf("COMPLETE", "PARTIAL_UNOBSERVABLE") &&
		providerCoverage in setOf("CALLBACKS_ENTERED_BEFORE_BARRIER", "PROVIDER_COMPLETENESS_UNOBSERVABLE") &&
		hasBoundedRecordedGap()

private fun SourceRunRetirementEntity.hasSettledActor(): Boolean =
	state == SourceRunRetirementEntity.STATE_ACKNOWLEDGED && appDrainComplete == true &&
		registrationRemovalOutcome in setOf("REMOVED", "NOT_REGISTERED") &&
		providerFlushOutcome in setOf("COMPLETE", "NOT_SUPPORTED", "NOT_REQUESTED")

private fun SourceRunRetirementEntity.hasBoundedRecordedGap(): Boolean {
	val start = unresolvedSequenceStart ?: -1L
	val end = unresolvedSequenceEnd ?: -1L
	val barrier = callbackEntryBarrierSequence ?: -1L
	val failed = failedAdmissionCount ?: -1L
	// Bounds enclose known sparse loss; they do not assert every sequence in the envelope was lost.
	return start > 0L && end >= start && end <= barrier && failed in 1L..(end - start + 1L)
}

private suspend fun hasExactRetirementEndpoint(
	database: AppDatabase,
	request: SourceProductDrainRequest,
	receipt: SourceRunRetirementEntity,
): Boolean = when (request.source) {
	SourceKind.WIFI, SourceKind.CELL -> hasExactRadioRetirementEndpoint(database, request, receipt)
	else -> hasExactSensorRetirementEndpoint(database, request, receipt)
}

private suspend fun hasExactSensorRetirementEndpoint(
	database: AppDatabase,
	request: SourceProductDrainRequest,
	receipt: SourceRunRetirementEntity,
): Boolean {
	val dao = database.sourceEventWalDao()
	val latest = dao.latestContinuityEventAtOrBefore(request.source.stableCode,
		receipt.sourceInstanceId, receipt.registrationGeneration, Long.MAX_VALUE)
	if (latest == null) {
		return (receipt.lastAdmissionOrdinal == null || receipt.lastAdmissionOrdinal == 0L) &&
			(receipt.lastSourceSequence == null || receipt.lastSourceSequence == 0L)
	}
	val endpoint = receipt.lastAdmissionOrdinal?.takeIf { it > 0L && it == latest.admissionOrdinal }
		?.let { dao.getByAdmissionOrdinal(it) } ?: return false
	return endpoint.logicalTrackingId == request.logicalTrackingId && endpoint.serviceRunId == request.serviceRunId &&
		endpoint.matchesRetirementSourceSequence(request.source, receipt.lastSourceSequence)
}

private suspend fun hasExactRadioRetirementEndpoint(
	database: AppDatabase,
	request: SourceProductDrainRequest,
	receipt: SourceRunRetirementEntity,
): Boolean {
	val highWater = database.sourceBrokerDao().runCaptureAdmissionHighWater(
		request.source.stableCode, receipt.sourceInstanceId, receipt.registrationGeneration,
		request.logicalTrackingId, request.serviceRunId, SourceBrokerPurpose.MASK_SESSION_CAPTURE)
	if (highWater.lastAdmissionOrdinal == 0L) {
		return (receipt.lastAdmissionOrdinal == null || receipt.lastAdmissionOrdinal == 0L) &&
			(receipt.lastSourceSequence == null || receipt.lastSourceSequence == 0L)
	}
	val endpoint = receipt.lastAdmissionOrdinal?.takeIf { it == highWater.lastAdmissionOrdinal }
		?.let { database.sourceEventWalDao().getByAdmissionOrdinal(it) } ?: return false
	// Radio callback order and broker-assigned WAL order are distinct domains.
	val callback = receipt.lastSourceSequence ?: return false
	return callback in 1L..requireNotNull(receipt.callbackEntryBarrierSequence) &&
		endpoint.hasAuthenticatedRadioCarrier(request.source)
}

private fun SourceEventWalEntity.hasAuthenticatedRadioCarrier(source: SourceKind): Boolean = when (source) {
	SourceKind.WIFI -> WifiWalCarrierAuthenticator.authenticate(this) is WifiWalCarrierAuthentication.Authenticated
	SourceKind.CELL -> CellWalCarrierAuthenticator.authenticate(this) is CellWalCarrierAuthentication.Authenticated
	else -> false
}

private fun SourceEventWalEntity.matchesRetirementSourceSequence(
	source: SourceKind,
	lastSourceSequence: Long?,
): Boolean = when (source) {
		SourceKind.LOCATION -> sourceSequence == lastSourceSequence
		SourceKind.PRESSURE -> {
			val authentication = PressureWalCarrierAuthenticator.authenticate(this)
			authentication is PressureWalCarrierAuthentication.Authenticated &&
				authentication.carrier.payload.lastProviderSequence == lastSourceSequence
		}
		else -> false
}
