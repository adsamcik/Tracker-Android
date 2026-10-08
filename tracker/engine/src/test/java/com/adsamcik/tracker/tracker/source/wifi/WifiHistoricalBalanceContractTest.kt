package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.SavedSessionCutoff
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContract
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Test

/** Exercises the actual portable historical reader, without provider activation. */
class WifiHistoricalBalanceContractTest {
    @Test
    fun `adopted responsive legacy QoS reconstructs fixed age while old contract stays strict`() {
        val legacy = policy()
        val originalContract = SourceDemandContractFactory.forQos(SourceKind.WIFI, 3, DirectSourceDemandPurpose.SESSION_CAPTURE)
        val original = demand(originalContract)
        authenticates(legacy, original) shouldBe true
        original.maximumAgeMs shouldBe 60_000L
        for (level in 1..5) {
            val adopted = legacy.copy(balanceProfileVersion = 1, balanceLevel = level, overallBalanceLevel = level)
            val newContract = SourceDemandContractFactory.forProfile(SourceKind.WIFI, 1, maxOf(level, 4), DirectSourceDemandPurpose.SESSION_CAPTURE)
            val current = demand(newContract)
            current.maximumAgeMs shouldBe 600_000L
            authenticates(adopted, current) shouldBe true
            authenticates(adopted, original) shouldBe false
            authenticates(legacy, current) shouldBe false
            authenticates(adopted.copy(balanceProfileVersion = 2), current) shouldBe false
            authenticates(adopted.copy(balanceLevel = null), current) shouldBe false
            authenticates(adopted.copy(balanceLevel = 0), current) shouldBe false
            authenticates(adopted.copy(overallBalanceLevel = null), current) shouldBe false
            authenticates(adopted.copy(captureConsentEpoch = 2), current) shouldBe false
        }
    }

    @Test
    fun `adopted ambient callback contract is baseline and separately consented even beside capture`() {
        val adopted = policy().copy(balanceProfileVersion = 1, balanceLevel = 2, overallBalanceLevel = 2,
            ambientPersistenceEligible = true, ambientConsentEpoch = 1)
        val contract = SourceDemandContractFactory.forProfile(SourceKind.WIFI, 1, 2, DirectSourceDemandPurpose.AMBIENT_PRODUCT)
        val ambient = demand(contract).copy(purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, qosCode = 0,
            logicalTrackingId = null, serviceRunId = null, manifestRevision = null, lifecycleLeaseGeneration = null)
        authenticates(adopted, ambient) shouldBe true
        authenticates(adopted.copy(ambientConsentEpoch = null, ambientPersistenceEligible = false), ambient) shouldBe false
        ambient.maximumAgeMs shouldBe 600_000L
    }

    private fun authenticates(policy: SourcePolicyEntity, demand: SourceDemandEntity): Boolean {
        val consent = SourceConsentEpochEntity(sourceKind = SourceKind.WIFI.stableCode,
            purpose = demand.purpose, epoch = 1, eligible = true, persistenceEligible = true,
            policyRevision = 1, effectiveBootId = "boot", effectiveElapsedRealtimeNanos = 0, effectiveWallTimeMs = 0,
            changeReason = "CAPTURE")
        val authorization = requireNotNull(SourceBrokerAuthorization.rows(SourceKind.WIFI.stableCode, 1, 1,
            listOf(demand), "boot", 20, 20).toAuthorizationSnapshotOrNull())
        val snapshot = WifiPortableAuthoritySnapshot(session = mockk(), savedSessionCutoff = SavedSessionCutoff(null, null),
            runsById = emptyMap(),
            manifestsByRun = emptyMap(), sourcesByManifest = emptyMap(), segmentsById = emptyMap(),
            planHeadersByRevision = emptyMap(), desiredPlansByRevision = emptyMap(), plansByRevision = emptyMap(),
            registrationsByGeneration = emptyMap(), authorizationsByKey = emptyMap(), authorizationsByGeneration = emptyMap(),
            demandsById = mapOf(demand.demandId to demand), policiesByRevision = mapOf(1L to policy),
            consentsByIdentity = mapOf("${demand.purpose.length}:${demand.purpose}:1" to consent), actionsByRun = emptyMap(),
            sequenceProofsByIdentity = emptyMap(), deliveryProofsByIdentity = emptyMap(), queryCount = 0)
        return snapshot.authenticatesHistoricalDemand(demand, authorization)
    }

    private fun policy() = SourcePolicyEntity(policyRevision = 1, sourceKind = SourceKind.WIFI.stableCode,
        enabled = true, qosCode = 3, locationMinTimeSeconds = null, locationMinDistanceMeters = null,
        locationRequiredAccuracyMeters = null, capturePersistenceEligible = true, controlPersistenceEligible = false,
        ambientPersistenceEligible = false, captureConsentEpoch = 1, controlConsentEpoch = null, ambientConsentEpoch = null,
        effectiveBootId = "boot", effectiveElapsedRealtimeNanos = 0, effectiveWallTimeMs = 0, changeReason = "CAPTURE")

    private fun demand(contract: SourceDemandContract) = SourceDemandEntity(demandId = "wifi-capture",
        consumerId = "session:session", sourceKind = SourceKind.WIFI.stableCode, purpose = SourceBrokerPurpose.SESSION_CAPTURE,
        logicalTrackingId = "session", serviceRunId = "run", manifestRevision = 1, lifecycleLeaseGeneration = 1,
        sourcePolicyRevision = 1, consentEpoch = 1, persistenceEligible = true, qosCode = 3,
        minimumAcquisitionSpec = contract.encodeFloor(), adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
        maximumAgeMs = contract.maximumProviderItemAgeMs, desiredLatencyMs = contract.targetPlanningLatencyMs,
        requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
        requestedBootId = "boot", requestedElapsedRealtimeNanos = 10, requestedAtMs = 10,
        status = SourceDemandEntity.STATUS_ACTIVE, retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null)
}
