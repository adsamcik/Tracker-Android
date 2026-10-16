package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
import com.adsamcik.tracker.shared.preferences.tracking.SourceBalanceSettings
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContract
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiBroadcastAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURE_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CONTROL_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.NANOS_PER_MILLISECOND
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.PLAN_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_SOURCE

internal fun WifiPlan.hasSupportedHistoricalShape(): Boolean =
	mode != WifiMode.OFF && minimumAttemptIntervalMs >= 0L &&
		maximumAcceptableResultAgeMs >= 0L && unchangedResultDedupeWindowMs >= 0L &&
		backoff.initialDelayMs >= 0L && backoff.maximumDelayMs >= backoff.initialDelayMs &&
		backoff.multiplier.isFinite() && backoff.multiplier >= 1.0

internal fun WifiPlan.satisfiesExactWifiContract(contract: SourceDemandContract): Boolean =
	contract.floor === WifiBroadcastAcquisitionFloor &&
		mode in setOf(WifiMode.BROADCAST_DRIVEN, WifiMode.ACTIVE_ATTEMPTS) &&
		maximumAcceptableResultAgeMs <= contract.maximumProviderItemAgeMs &&
		contract.requestedDeliveryLatencyMs == null

internal fun SourceDemandEntity.nonCaptureDirectPurpose(): DirectSourceDemandPurpose? = when (purpose) {
	SourceBrokerPurpose.CONTROL_AUTOSTART -> DirectSourceDemandPurpose.CONTROL_AUTOSTART
	SourceBrokerPurpose.CONTROL_CONTINUATION -> DirectSourceDemandPurpose.CONTROL_CONTINUATION
	SourceBrokerPurpose.AMBIENT_PRODUCT -> DirectSourceDemandPurpose.AMBIENT_PRODUCT
	else -> null
}

internal fun SourceDemandEntity.policyPurpose(): String? = when (purpose) {
	SourceBrokerPurpose.CONTROL_AUTOSTART,
	SourceBrokerPurpose.CONTROL_CONTINUATION,
	-> CONTROL_PURPOSE
	SourceBrokerPurpose.AMBIENT_PRODUCT -> SourceBrokerPurpose.AMBIENT_PRODUCT
	else -> null
}

internal fun SourcePolicyEntity.authorityFor(purpose: String): Pair<Long?, Boolean> = when (purpose) {
	CONTROL_PURPOSE -> controlConsentEpoch to controlPersistenceEligible
	SourceBrokerPurpose.AMBIENT_PRODUCT -> ambientConsentEpoch to ambientPersistenceEligible
	else -> null to false
}

internal fun WifiPlan.maximumObservationAgeNanos(): Long =
	if (maximumAcceptableResultAgeMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
		Long.MAX_VALUE
	} else {
		maximumAcceptableResultAgeMs * NANOS_PER_MILLISECOND
	}

/** Ambient radio keeps its independent QoS-zero callback demand for every policy format. */
internal fun SourcePolicyEntity.historicalWifiQosCode(purpose: DirectSourceDemandPurpose): Int =
    if (purpose == DirectSourceDemandPurpose.AMBIENT_PRODUCT) {
        0
    } else {
        qosCode
    }

internal fun SourcePolicyEntity.historicalWifiDemandContract(purpose: DirectSourceDemandPurpose): SourceDemandContract {
    if (balanceProfileVersion != null && purpose in setOf(
            DirectSourceDemandPurpose.SESSION_CAPTURE, DirectSourceDemandPurpose.AMBIENT_PRODUCT)) {
        require(balanceProfileVersion == 1)
        val baseline = requireNotNull(balanceLevel)
        require(baseline in 1..5 && overallBalanceLevel?.let { it in 1..5 } == true)
        require(baseline == (sourceBalanceOverride ?: overallBalanceLevel))
        return SourceDemandContractFactory.forProfile(SourceKind.WIFI, 1,
            if (purpose == DirectSourceDemandPurpose.SESSION_CAPTURE) {
                maxOf(baseline, SourceBalanceSettings.LIVE_LEVEL)
            } else {
                baseline
            }, purpose)
    }
    if (balanceProfileVersion == null) {
        require(balanceLevel == null && overallBalanceLevel == null && sourceBalanceOverride == null)
    }
    return SourceDemandContractFactory.forQos(SourceKind.WIFI, historicalWifiQosCode(purpose), purpose)
}

internal fun SourceDemandEntity.historicalWifiPurposes(): Pair<DirectSourceDemandPurpose, String>? {
	val directPurpose = directPurposeIncludingCapture() ?: return null
	val policyPurpose = policyPurposeIncludingCapture() ?: return null
	return directPurpose to policyPurpose
}

internal fun SourceDemandEntity.matchesPortableHistoricalWifiAuthority(
	policy: SourcePolicyEntity,
	consent: SourceConsentEpochEntity,
	purposes: Pair<DirectSourceDemandPurpose, String>,
	authorization: SourceAuthorizationSnapshot,
): Boolean {
	val (directPurpose, policyPurpose) = purposes
	val (policyEpoch, policyPersistence) = policy.portableAuthorityFor(policyPurpose)
	val expectedContract = runCatching {
		policy.historicalWifiDemandContract(directPurpose)
	}.getOrNull() ?: return false
	val actualContract = runCatching { toSourceDemandContract() }.getOrNull() ?: return false
	// Custom capture freshness is checked by the saved plan; policy defines its acquisition floor.
	val contractMatches = if (purpose == CAPTURE_PURPOSE) {
		actualContract.floor == expectedContract.floor
	} else {
		actualContract == expectedContract
	}
	val lifecycleValid = hasHistoricalWifiDemandLifecycle()
	val policyPrecedesDemand = policy.isEffectiveAtOrBefore(
		requestedBootId, requestedElapsedRealtimeNanos, requestedAtMs)
	val consentPrecedesDemand = consent.isEffectiveAtOrBefore(
		requestedBootId, requestedElapsedRealtimeNanos, requestedAtMs)
	val demandPrecedesAuthorization = precedesAuthorization(authorization)
	return lifecycleValid && sourceKind == WIFI_SOURCE && matchesPortableWifiPolicy(
		policy, directPurpose, policyEpoch, policyPersistence, contractMatches,
	) && consent.matchesPortableWifiConsent(
		this, policyPurpose, policy, policyPrecedesDemand, consentPrecedesDemand, demandPrecedesAuthorization,
	)
}

private fun SourceDemandEntity.hasHistoricalWifiDemandLifecycle(): Boolean = when (status) {
	SourceDemandEntity.STATUS_ACTIVE, SourceDemandEntity.STATUS_BLOCKED ->
		retireBootId == null && retireElapsedRealtimeNanos == null && retiredAtMs == null
	SourceDemandEntity.STATUS_RETIRING, SourceDemandEntity.STATUS_RETIRED ->
		retireBootId != null && retireElapsedRealtimeNanos?.let { it >= requestedElapsedRealtimeNanos } == true &&
			retiredAtMs?.let { it >= requestedAtMs } == true
	else -> false
}

private fun SourceDemandEntity.matchesPortableWifiPolicy(
	policy: SourcePolicyEntity,
	directPurpose: DirectSourceDemandPurpose,
	policyEpoch: Long?,
	policyPersistence: Boolean,
	contractMatches: Boolean,
): Boolean = (purpose != CAPTURE_PURPOSE || policy.enabled) && policy.sourceKind == WIFI_SOURCE &&
	policy.policyRevision == sourcePolicyRevision && policy.historicalWifiQosCode(directPurpose) == qosCode &&
	policyEpoch == consentEpoch && policyPersistence == persistenceEligible && contractMatches

private fun SourceConsentEpochEntity.matchesPortableWifiConsent(
	demand: SourceDemandEntity,
	policyPurpose: String,
	policy: SourcePolicyEntity,
	policyPrecedesDemand: Boolean,
	consentPrecedesDemand: Boolean,
	demandPrecedesAuthorization: Boolean,
): Boolean = sourceKind == WIFI_SOURCE && purpose == policyPurpose && epoch == demand.consentEpoch && eligible &&
	persistenceEligible == demand.persistenceEligible && policyRevision <= policy.policyRevision &&
	policyPrecedesDemand && consentPrecedesDemand && demandPrecedesAuthorization

internal fun SourceDesiredPlanEntity.hasCurrentWifiReplacementPlanIdentity(
	planHeader: AcquisitionPlanRevisionEntity,
	manifest: SessionManifestVersionEntity,
	plan: WifiPlan,
): Boolean = planHeader.sourcePolicyRevision == manifest.sourcePolicyRevision &&
	payloadVersion == PLAN_PAYLOAD_VERSION && revision == manifest.acquisitionPlanRevision && sourceKind == WIFI_SOURCE &&
	plan.revision == manifest.acquisitionPlanRevision

internal fun SourcePolicyEntity.authorizesWifiReplacement(
	manifest: SessionManifestVersionEntity,
	wifiSource: SessionManifestSourceEntity,
): Boolean = enabled && capturePersistenceEligible && captureConsentEpoch == wifiSource.consentEpoch &&
	qosCode == wifiSource.qosCode && effectiveBootId == manifest.effectiveBootId &&
	effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos

internal fun SourceConsentEpochEntity.authorizesWifiReplacement(
	policy: SourcePolicyEntity,
	manifest: SessionManifestVersionEntity,
): Boolean = eligible && persistenceEligible && policyRevision <= policy.policyRevision &&
	effectiveBootId == manifest.effectiveBootId && effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos
