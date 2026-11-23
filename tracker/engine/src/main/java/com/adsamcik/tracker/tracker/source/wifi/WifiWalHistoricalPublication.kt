package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthenticator
import com.adsamcik.tracker.tracker.source.model.SourceKind

/** Rejected input may settle only after its original application publication is no longer pending. */
internal suspend fun AppDatabase.hasSettledWifiHistoricalPublication(
	eventId: String, reason: WifiWalAdapterRejection,
): Boolean {
	if (reason != WifiWalAdapterRejection.HISTORICAL_PLAN_APPLICATION_UNVERIFIABLE) {
		return true
	}
	val wal = sourceEventWalDao().getByEventId(eventId) ?: return false
	return if (wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
		val members = historicalCaptureMembers(wal)
		members.isNotEmpty() && members.all { member ->
			hasSettledCapturePublication(wal, member.logicalTrackingId, member.serviceRunId,
				member.manifestRevision, member.lifecycleLeaseGeneration)
		}
	} else {
		hasSettledCapturePublication(wal, wal.logicalTrackingId, wal.serviceRunId,
			wal.sessionManifestRevision, wal.lifecycleLeaseGeneration)
	}
}

private suspend fun AppDatabase.historicalCaptureMembers(wal: SourceEventWalEntity): List<SourceAuthorizationEntity> {
	val carrier = WifiWalCarrierAuthenticator.authenticate(wal) as? WifiWalCarrierAuthentication.Authenticated
	val registration = sourceBrokerDao().registration(
		SourceKind.WIFI.stableCode, wal.registrationGeneration,
	)
	val start = registration?.acceptedElapsedRealtimeNanos
	val items = if (carrier == null || start == null) { null } else {
		qualifyWifiPurposeItems(wal, carrier.carrier.payload, start,
			registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE)
	} ?: return emptyList()
	return items.historicalMembers().filter { member ->
		member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
			member.demandId?.let { items.forDemand(it).isNotEmpty() } == true
	}
}

private suspend fun AppDatabase.hasSettledCapturePublication(
	wal: SourceEventWalEntity, logical: String?, run: String?, manifest: Long?, lease: Long?,
): Boolean = if (logical == null || run == null || manifest == null) {
	false
} else {
	val original = sourceSessionDao().manifest(logical, manifest)
	original != null && sourceSessionDao().sourceStartActionsForManifestBounded(
		logical, run, manifest, SourceKind.WIFI.stableCode, 2,
	).singleOrNull()?.hasSettledWifiPublication(wal, lease, original.acquisitionPlanRevision) == true
}


private fun LifecycleDesiredActionEntity.hasSettledWifiPublication(
	wal: SourceEventWalEntity, originalLease: Long?, originalPlanRevision: Long,
): Boolean {
	val exactOriginal = desiredPlanRevision == originalPlanRevision &&
		leaseGeneration == originalLease && bootId == wal.clockDomainId
	return exactOriginal && when (status) {
		"TERMINAL_FAILURE", "SUPERSEDED" -> true
		"START_ACCEPTED" -> acknowledgedAtMs != null && acknowledgedElapsedRealtimeNanos != null
		else -> false
	}
}
