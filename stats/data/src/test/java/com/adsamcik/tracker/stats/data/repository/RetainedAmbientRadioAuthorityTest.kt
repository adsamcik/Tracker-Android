package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RetainedAmbientRadioAuthorityTest {
    @Test fun `provider discovery overflow is distinct from absent authority`() = runTest {
        val database = mockk<AppDatabase>()
        val inputs = (0..MAX_CONTEXT_POINTS).map { input().copy(registration = it.toLong()) }
        assertFailsWith<RetainedRadioDependencyLimitExceeded> {
            RetainedAmbientRadioAuthority.load(database, SOURCE, inputs, "boot")
        }
    }

    @Test fun `authorization dependency overflow is distinct from absent authority`() = runTest {
        val row = rows(1, listOf(demand()), 0).single()
        assertFailsWith<RetainedRadioDependencyLimitExceeded> {
            load(List(MAX_CONTEXT_DEPENDENCIES + 1) { row })
        }
    }

    @Test fun `later deny callback keeps independently authorized earlier ambient reading`() = runTest {
        val granted = rows(1, listOf(demand()), 0)
        val denied = rows(2, emptyList(), 11)
        val authority = load(granted + denied)
        val input = input().copy(authorizationRevision = 2,
            authorizationFingerprint = denied.first().authorizationFingerprint, purposeMask = 0)
        assertTrue(requireNotNull(authority).qualifies(input))
        assertFalse(authority.qualifies(input.copy(fromNanos = 11, toNanos = 11)))
    }

    @Test fun `callback snapshot after interval is authenticated against immutable demands too`() = runTest {
        val original = rows(1, listOf(demand()), 0)
        val changed = rows(2, listOf(demand().copy(qosCode = 2)), 11)
        val authority = requireNotNull(load(original + changed))
        assertFalse(authority.qualifies(input().copy(authorizationRevision = 2,
            authorizationFingerprint = changed.first().authorizationFingerprint)))
    }

    @Test fun `wrong provider boot lifetime purpose or consent cannot lend retained quality`() = runTest {
        val authority = requireNotNull(load(rows(1, listOf(demand()), 0)))
        assertTrue(authority.qualifies(input()))
        assertFalse(authority.qualifies(input().copy(clock = "other")))
        assertFalse(authority.qualifies(input().copy(instance = "other")))
        assertFalse(authority.qualifies(input().copy(toNanos = 100)))
        assertFalse(authority.qualifies(input().copy(consentEpoch = 1)))
        assertFalse(authority.qualifies(input().copy(demandId = "capture")))
        assertFalse(authority.qualifies(input().copy(retentionPolicyId = "other")))
    }

    private suspend fun load(rows: List<SourceAuthorizationEntity>): RetainedAmbientRadioAuthority? {
        val database = mockk<AppDatabase>(relaxed = true)
        coEvery { database.wifiCapturedFactDao().historyProviderRegistrations(SOURCE, any(), any()) } returns listOf(registration())
        coEvery { database.wifiCapturedFactDao().historyAuthorizations(SOURCE, any(), any()) } returns rows
        coEvery { database.sourceBrokerDao().currentPhysicalRegistrationsBounded(SOURCE, any()) } returns emptyList()
        coEvery { database.wifiCapturedFactDao().historyDemands(any(), any()) } returns listOf(demand())
        coEvery { database.sourcePolicyDao().policiesAtRevisions(SOURCE, any()) } returns listOf(policy())
        coEvery { database.sourcePolicyDao().consentEpochs(SOURCE, SourceBrokerPurpose.AMBIENT_PRODUCT, any()) } returns listOf(
            SourceConsentEpochEntity(SOURCE, SourceBrokerPurpose.AMBIENT_PRODUCT, 0, true, true, 1, "boot", 0, 0, "USER"))
        return RetainedAmbientRadioAuthority.load(database, SOURCE, listOf(input()), "boot")
    }

    private fun input() = RetainedRadioAuthorityInput(1, "instance", "boot", "physical", 0,
        1, SourceBrokerAuthorization.fingerprint(listOf(demand())), SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
        10, 10, 12, 1_000, 1, 0, "ambient", "retention", 1)
    private fun rows(revision: Long, demands: List<SourceDemandEntity>, nanos: Long) =
        SourceBrokerAuthorization.rows(SOURCE, 1, revision, demands, "boot", nanos, 0)
    private fun registration() = ProviderRegistrationGenerationEntity(SOURCE, 1, "instance", "owner", "boot", "physical",
        0, ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND, "process", ProviderRegistrationGenerationEntity.STATUS_RETIRED,
        0, 0, 0, 0, 2_000, 100, null)
    private fun demand() = SourceDemandEntity(demandId = "ambient", consumerId = "ambient-owner", sourceKind = SOURCE,
        purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, logicalTrackingId = null, serviceRunId = null, manifestRevision = null,
        lifecycleLeaseGeneration = null, sourcePolicyRevision = 1, consentEpoch = 0, persistenceEligible = true, qosCode = 1,
        maximumAgeMs = 60_000, desiredLatencyMs = 0, requestedBootId = "boot", requestedElapsedRealtimeNanos = 0,
        requestedAtMs = 0, status = "ACTIVE", retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null,
        liveAmbientRetentionPolicyId = "retention", liveAmbientRetentionApprovalRevision = 1)
    private fun policy() = SourcePolicyEntity(1, SOURCE, true, 1, null, null, null, false, false, true,
        null, null, 0, "boot", 0, 0, "USER")
    private companion object { const val SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI }
}
