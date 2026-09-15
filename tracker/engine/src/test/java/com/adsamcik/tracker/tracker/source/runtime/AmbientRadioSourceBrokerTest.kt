package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientCellRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientWifiRetentionDecision
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room-backed broker/authority contract. Execution remains deferred to final convergence. */
@RunWith(RobolectricTestRunner::class)
class AmbientRadioSourceBrokerTest {
	private lateinit var database: AppDatabase
	private lateinit var policyRepository: RoomSourcePolicyRepository
	private lateinit var broker: SourceBroker
	private lateinit var rolloutStore: RoomTrackingRolloutStateStore
	private var elapsed = 10L

	@BeforeTest
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 3L, updatedAtMs = 1L),
		)
		rolloutStore = installCanonicalProductLanesForTest(
			database,
			listOf(
				ExecutableSourceLaneBinding(
					SourceKind.WIFI,
					1L,
					"ambient-wifi-test",
					1,
					setOf(CaptureReachabilityMode.AMBIENT),
				),
				ExecutableSourceLaneBinding(
					SourceKind.CELL,
					1L,
					"ambient-cell-test",
					1,
					setOf(CaptureReachabilityMode.AMBIENT),
				),
			),
			1L,
		)
		policyRepository = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		policyRepository.bootstrapFromLegacy(
			TrackingParamsState(legacySettingsMigrationCompleted = true),
		)
		broker = SourceBroker(database, rolloutStore, PermissiveLeaseGuard)
	}

	@AfterTest
	fun tearDown() {
		database.close()
	}

	@Test
	fun `Wi-Fi ambient grant binds demand retention and source authority atomically`() = runTest {
		val policy = grant(TrackingSourceComponent.WIFI)
		val consentEpoch = policy[TrackingSourceComponent.WIFI].ambientConsentEpoch!!
		database.applyAmbientWifiRetentionDecision(
			AmbientRadioRetentionDecision.GrantLiveAmbient(
				opaquePolicyId = "privacy:wifi:ambient:v1",
				expectedCollectedDataEpoch = 3L,
				expectedSourcePolicyRevision = policy.revision,
				expectedAmbientConsentEpoch = consentEpoch,
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = 90L,
				effectiveWallTimeMs = 90L,
			),
		)

		val result = assertIs<AmbientRadioDemandResult.Active>(
			broker.replaceAmbientWifiDemand(
				"app:ambient:wifi",
				true,
				lease(AmbientTrackingSource.WIFI, policy.revision, consentEpoch, "wifi-owner-1"),
				1L,
				"boot-1",
				100L,
				100L,
			),
		)

		val demand = database.sourceBrokerDao().currentDemands("app:ambient:wifi").single()
		assertEquals(SourceBrokerPurpose.AMBIENT_PRODUCT, demand.purpose)
		assertEquals(result.demand, demand)
		assertEquals(policy.revision, result.reconciliationAuthority.policyRevision)
		assertEquals(
			policy[TrackingSourceComponent.WIFI].ambientConsentEpoch,
			result.reconciliationAuthority.ambientConsentEpoch,
		)
		assertEquals(3L, result.reconciliationAuthority.collectedDataEpoch)
		assertEquals(1L, result.reconciliationAuthority.rolloutRevision)
		assertEquals(1L, result.reconciliationAuthority.executionGeneration)
		assertEquals(result.authorityRevision, result.reconciliationAuthority.authorityRevision)
		val authority = database.ambientWifiFactDao().latestAuthority()
		assertEquals(AmbientWifiAuthorityEntity.STATE_ACTIVE, authority?.state)
		assertEquals("privacy:wifi:ambient:v1", authority?.retentionPolicyId)
		assertEquals(3L, authority?.collectedDataEpoch)
		assertEquals("wifi-owner-1", authority?.ownerCasToken)
		assertEquals(result.demand.demandId, authority?.demandId)
	}

	@Test
	fun `Cell revoke retires only ambient demand and appends durable revoked authority`() = runTest {
		val policy = grant(TrackingSourceComponent.CELL)
		val consentEpoch = policy[TrackingSourceComponent.CELL].ambientConsentEpoch!!
		database.applyAmbientCellRetentionDecision(
			AmbientRadioRetentionDecision.GrantLiveAmbient(
				opaquePolicyId = "privacy:cell:ambient:v1",
				expectedCollectedDataEpoch = 3L,
				expectedSourcePolicyRevision = policy.revision,
				expectedAmbientConsentEpoch = consentEpoch,
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = 90L,
				effectiveWallTimeMs = 90L,
			),
		)
		broker.replaceAmbientCellDemand(
			"app:ambient:cell",
			true,
			lease(AmbientTrackingSource.CELL, policy.revision, consentEpoch, "cell-owner-1"),
			1L,
			"boot-1",
			100L,
			100L,
		)

		assertIs<AmbientRadioDemandResult.Inactive>(
			broker.replaceAmbientCellDemand(
				"app:ambient:cell",
				false,
				lease(AmbientTrackingSource.CELL, policy.revision, consentEpoch, "cell-owner-2"),
				2L,
				"boot-1",
				200L,
				200L,
			),
		)

		assertTrue(database.sourceBrokerDao().currentDemands("app:ambient:cell").isEmpty())
		assertEquals(
			AmbientCellAuthorityEntity.STATE_REVOKED,
			database.ambientCellFactDao().latestAuthority()?.state,
		)
	}

	@Test
	fun `missing retention approval creates no ambient demand`() = runTest {
		val policy = grant(TrackingSourceComponent.WIFI)
		val consentEpoch = policy[TrackingSourceComponent.WIFI].ambientConsentEpoch!!

		val result = assertIs<AmbientRadioDemandResult.Inactive>(
			broker.replaceAmbientWifiDemand(
				"app:ambient:wifi",
				true,
				lease(AmbientTrackingSource.WIFI, policy.revision, consentEpoch, "wifi-owner-1"),
				1L,
				"boot-1",
				100L,
				100L,
			),
		)

		assertEquals(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISSING, result.reason)
		assertTrue(database.sourceBrokerDao().currentDemands("app:ambient:wifi").isEmpty())
	}

	@Test
	fun `stale policy lease cannot retire the exact newer ambient demand`() = runTest {
		val firstPolicy = grant(TrackingSourceComponent.WIFI)
		val firstConsent = firstPolicy[TrackingSourceComponent.WIFI].ambientConsentEpoch!!
		database.applyAmbientWifiRetentionDecision(
			AmbientRadioRetentionDecision.GrantLiveAmbient(
				"privacy:wifi:ambient:v1",
				3L,
				firstPolicy.revision,
				firstConsent,
				"boot-1",
				90L,
				90L,
			),
		)
		val active = assertIs<AmbientRadioDemandResult.Active>(
			broker.replaceAmbientWifiDemand(
				"app:ambient:wifi",
				true,
				lease(AmbientTrackingSource.WIFI, firstPolicy.revision, firstConsent, "owner-1"),
				1L,
				"boot-1",
				100L,
				100L,
			),
		)
		val newerPolicy = policyRepository.setNonCaptureConsent(
			firstPolicy.revision,
			TrackingSourceComponent.WIFI,
			SourcePurpose.AMBIENT_PRODUCT,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_AMBIENT_RADIO_REVOKE",
		)

		val stale = assertIs<AmbientRadioDemandResult.Inactive>(
			broker.replaceAmbientWifiDemand(
				"app:ambient:wifi",
				false,
				lease(AmbientTrackingSource.WIFI, firstPolicy.revision, firstConsent, "owner-old"),
				2L,
				"boot-1",
				200L,
				200L,
			),
		)

		assertEquals(AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE, stale.reason)
		assertEquals(
			active.demand.demandId,
			database.sourceBrokerDao().currentDemands("app:ambient:wifi").single().demandId,
		)
		assertTrue(newerPolicy.revision > firstPolicy.revision)
	}

	@Test
	fun `replaced owner token cannot enter the Room mutation`() = runTest {
		val policy = grant(TrackingSourceComponent.WIFI)
		val consent = policy[TrackingSourceComponent.WIFI].ambientConsentEpoch!!
		database.applyAmbientWifiRetentionDecision(
			AmbientRadioRetentionDecision.GrantLiveAmbient(
				"privacy:wifi:ambient:v1",
				3L,
				policy.revision,
				consent,
				"boot-1",
				90L,
				90L,
			),
		)
		val active = assertIs<AmbientRadioDemandResult.Active>(
			broker.replaceAmbientWifiDemand(
				"app:ambient:wifi",
				true,
				lease(AmbientTrackingSource.WIFI, policy.revision, consent, "owner-new"),
				1L,
				"boot-1",
				100L,
				100L,
			),
		)
		val guarded = SourceBroker(
			database,
			rolloutStore,
			SingleTokenLeaseGuard("owner-new"),
		)

		val stale = assertIs<AmbientRadioDemandResult.Inactive>(
			guarded.replaceAmbientWifiDemand(
				"app:ambient:wifi",
				false,
				lease(AmbientTrackingSource.WIFI, policy.revision, consent, "owner-old"),
				2L,
				"boot-1",
				200L,
				200L,
			),
		)

		assertEquals(AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE, stale.reason)
		assertEquals(
			active.demand.demandId,
			database.sourceBrokerDao().currentDemands("app:ambient:wifi").single().demandId,
		)
	}

	@Test
	fun `older attempt for the same owner cannot retire its newer exact demand`() = runTest {
		val policy = grant(TrackingSourceComponent.CELL)
		val consent = policy[TrackingSourceComponent.CELL].ambientConsentEpoch!!
		database.applyAmbientCellRetentionDecision(
			AmbientRadioRetentionDecision.GrantLiveAmbient(
				"privacy:cell:ambient:v1",
				3L,
				policy.revision,
				consent,
				"boot-1",
				90L,
				90L,
			),
		)
		val active = assertIs<AmbientRadioDemandResult.Active>(
			broker.replaceAmbientCellDemand(
				"app:ambient:cell",
				true,
				lease(AmbientTrackingSource.CELL, policy.revision, consent, "cell-owner"),
				2L,
				"boot-1",
				100L,
				100L,
			),
		)

		val stale = assertIs<AmbientRadioDemandResult.Inactive>(
			broker.replaceAmbientCellDemand(
				"app:ambient:cell",
				false,
				lease(AmbientTrackingSource.CELL, policy.revision, consent, "cell-owner"),
				1L,
				"boot-1",
				200L,
				200L,
			),
		)

		assertEquals(AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_ATTEMPT, stale.reason)
		assertEquals(
			active.demand.demandId,
			database.sourceBrokerDao().currentDemands("app:ambient:cell").single().demandId,
		)
	}

	private suspend fun grant(source: TrackingSourceComponent) =
		policyRepository.setNonCaptureConsent(
			expectedPolicyRevision =
				(policyRepository.currentState() as SourcePolicyAuthorityState.Active).snapshot.revision,
			source = source,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_AMBIENT_RADIO_GRANT",
		)

	private fun lease(
		source: AmbientTrackingSource,
		policyRevision: Long,
		consentEpoch: Long,
		ownerCasToken: String,
	) = AmbientReconciliationIdentity(
		source,
		policyRevision,
		consentEpoch,
		3L,
		1L,
		ownerCasToken,
	)

	private object PermissiveLeaseGuard : AmbientRadioMutationLeaseGuard {
		override suspend fun <T> mutateIfCurrent(
			identity: AmbientReconciliationIdentity,
			mutation: suspend () -> T,
		): AmbientRadioLeaseMutation<T> = AmbientRadioLeaseMutation.Applied(mutation())
	}

	private class SingleTokenLeaseGuard(
		private val token: String,
	) : AmbientRadioMutationLeaseGuard {
		override suspend fun <T> mutateIfCurrent(
			identity: AmbientReconciliationIdentity,
			mutation: suspend () -> T,
		): AmbientRadioLeaseMutation<T> =
			if (identity.ownerCasToken == token) {
				AmbientRadioLeaseMutation.Applied(mutation())
			} else {
				AmbientRadioLeaseMutation.Stale
			}
	}
}
