package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientCellRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientWifiRetentionDecision
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
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
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
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
	private lateinit var retentionReader: TestLiveAmbientRetentionAuthorityReader
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
		retentionReader = TestLiveAmbientRetentionAuthorityReader()
		broker = SourceBroker(
			database,
			rolloutStore,
			PermissiveLeaseGuard,
			retentionReader,
		)
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
				retentionSnapshot = retentionSnapshot(
					SourceKind.WIFI,
					policy.revision,
					consentEpoch,
					100L,
				),
			),
		)

		val demand = database.sourceBrokerDao().currentDemands("app:ambient:wifi").single()
		assertEquals(SourceBrokerPurpose.AMBIENT_PRODUCT, demand.purpose)
		assertEquals(result.demand, demand)
		assertEquals("privacy:wifi:ambient:v1", demand.liveAmbientRetentionPolicyId)
		assertEquals(1L, demand.liveAmbientRetentionApprovalRevision)
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
			retentionSnapshot = retentionSnapshot(
				SourceKind.CELL,
				policy.revision,
				consentEpoch,
				100L,
			),
		)

		assertIs<AmbientRadioDemandResult.Inactive>(
			broker.replaceAmbientCellDemand(
				"app:ambient:cell",
				false,
				lease(AmbientTrackingSource.CELL, policy.revision, consentEpoch, "cell-owner-1"),
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
		retentionReader.current = false

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
				retentionSnapshot = retentionSnapshot(
					SourceKind.WIFI,
					firstPolicy.revision,
					firstConsent,
					100L,
				),
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
				retentionSnapshot = retentionSnapshot(
					SourceKind.WIFI,
					policy.revision,
					consent,
					100L,
				),
			),
		)
		val guarded = SourceBroker(
			database,
			rolloutStore,
			SingleTokenLeaseGuard("owner-new"),
			TestLiveAmbientRetentionAuthorityReader(),
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
				retentionSnapshot = retentionSnapshot(
					SourceKind.CELL,
					policy.revision,
					consent,
					100L,
				),
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

	@Test
	fun `exact attempt compensation preserves another consumer session demand`() = runTest {
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
		val identity =
			lease(AmbientTrackingSource.WIFI, policy.revision, consent, "wifi-owner")
		val active = assertIs<AmbientRadioDemandResult.Active>(
			broker.replaceAmbientWifiDemand(
				"app:ambient:wifi",
				true,
				identity,
				1L,
				"boot-1",
				100L,
				100L,
				retentionSnapshot = retentionSnapshot(
					SourceKind.WIFI,
					policy.revision,
					consent,
					100L,
				),
			),
		)
		val sessionDemand = active.demand.copy(
			demandId = "session-wifi-demand",
			consumerId = "session:logical-1",
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = "logical-1",
			serviceRunId = "run-1",
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			qosCode = 2,
		)
		database.sourceBrokerDao().insertDemands(listOf(sessionDemand))

		val compensated = broker.compensateAmbientWifiDemandUnderHeldLease(
			"app:ambient:wifi",
			identity,
			1L,
			active.demand.demandId,
			"boot-1",
			200L,
			200L,
		)

		assertTrue(compensated != null)
		assertTrue(database.sourceBrokerDao().currentDemands("app:ambient:wifi").isEmpty())
		assertEquals(
			sessionDemand.demandId,
			database.sourceBrokerDao().currentDemands("session:logical-1").single().demandId,
		)
	}

	@Test
	fun `partial radio compensation retry retires exact caller authority idempotently`() = runTest {
		val policy = grant(TrackingSourceComponent.WIFI)
		val consent = requireNotNull(policy[TrackingSourceComponent.WIFI].ambientConsentEpoch)
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
		val identity = lease(
			AmbientTrackingSource.WIFI,
			policy.revision,
			consent,
			"wifi-partial-owner",
		)
		val reference = SourceCallerReplayReference("wifi-partial-caller")
		val authorityRepository = RoomSourceCallerAcceptedAuthorityRepository(database)
		assertTrue(authorityRepository.insertIfAbsent(
			reference,
			StoredSourceCallerAuthority(
				origin = StoredSourceCallerOrigin.PURPOSE_OWNER,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				permittedDemandIdentities = setOf(
					SourceCallerDemandIdentity(identity.purposeLeaseIdentity, null),
				),
			),
			createdAtMs = 95L,
		))
		val active = assertIs<AmbientRadioDemandResult.Active>(
			broker.replaceAmbientWifiDemand(
				consumerId = "app:ambient:wifi",
				requested = true,
				leaseIdentity = identity,
				reconciliationAttempt = 1L,
				bootId = "boot-1",
				elapsedRealtimeNanos = 100L,
				wallTimeMs = 100L,
				sourceCallerAuthorityReference = reference.value,
				retentionSnapshot = retentionSnapshot(
					SourceKind.WIFI,
					policy.revision,
					consent,
					100L,
				),
			),
		)
		assertEquals(
			1,
			database.ambientWifiFactDao().retireExactAmbientDemand(
				demandId = active.demand.demandId,
				consumerId = active.demand.consumerId,
				sourceKind = active.demand.sourceKind,
				sourcePolicyRevision = active.demand.sourcePolicyRevision,
				consentEpoch = active.demand.consentEpoch,
				bootId = "boot-1",
				elapsedRealtimeNanos = 150L,
				wallTimeMs = 150L,
			),
		)

		val firstRetry = broker.compensateAmbientWifiDemandUnderHeldLease(
			consumerId = "app:ambient:wifi",
			leaseIdentity = identity,
			reconciliationAttempt = 1L,
			expectedDemandId = active.demand.demandId,
			bootId = "boot-1",
			elapsedRealtimeNanos = 200L,
			wallTimeMs = 200L,
		)
		val secondRetry = broker.compensateAmbientWifiDemandUnderHeldLease(
			consumerId = "app:ambient:wifi",
			leaseIdentity = identity,
			reconciliationAttempt = 1L,
			expectedDemandId = active.demand.demandId,
			bootId = "boot-1",
			elapsedRealtimeNanos = 201L,
			wallTimeMs = 201L,
		)

		assertTrue(firstRetry != null)
		assertEquals(firstRetry, secondRetry)
		assertEquals(
			StoredSourceCallerAuthorityLoadResult.Retired,
			authorityRepository.load(reference),
		)
		assertEquals(
			AmbientWifiAuthorityEntity.STATE_REVOKED,
			database.ambientWifiFactDao().latestAuthority()?.state,
		)
	}

	@Test
	fun `cold start retires exact Room reconstructed Wi-Fi authority without an in-memory lease`() =
		runTest {
			val policy = grant(TrackingSourceComponent.WIFI)
			val consent = requireNotNull(policy[TrackingSourceComponent.WIFI].ambientConsentEpoch)
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
			val identity = lease(
				AmbientTrackingSource.WIFI,
				policy.revision,
				consent,
				"wifi-cold-start-owner",
			)
			val caller = insertPurposeOwnerAuthority(identity, "wifi-cold-start-caller")
			val active = assertIs<AmbientRadioDemandResult.Active>(
				broker.replaceAmbientWifiDemand(
					consumerId = "app:ambient:wifi",
					requested = true,
					leaseIdentity = identity,
					reconciliationAttempt = 1L,
					bootId = "boot-1",
					elapsedRealtimeNanos = 100L,
					wallTimeMs = 100L,
					sourceCallerAuthorityReference = caller.value,
					retentionSnapshot = retentionSnapshot(
						SourceKind.WIFI,
						policy.revision,
						consent,
						100L,
					),
				),
			)
			val plan = assertIs<AmbientRadioRetirementPlan.Required>(
				broker.ambientRadioRetirementPlan(
					SourceKind.WIFI,
					"app:ambient:wifi",
				),
			)
			val coldStartBroker = SourceBroker(
				database,
				rolloutStore,
				RejectingAmbientRadioMutationLeaseGuard,
				retentionReader,
			)

			val retired = assertIs<AmbientRadioDemandResult.Inactive>(
				coldStartBroker.reduceAmbientWifiDemandForRecovery(
					consumerId = "app:ambient:wifi",
					plan = plan,
					reconciliationAttempt = 2L,
					bootId = "boot-1",
					elapsedRealtimeNanos = 200L,
					wallTimeMs = 200L,
				),
			)

			assertEquals(AmbientRadioDemandInactiveReason.REQUEST_DISABLED, retired.reason)
			assertEquals(active.demand.demandId, retired.retiredDemandId)
			assertTrue(database.sourceBrokerDao().currentDemands("app:ambient:wifi").isEmpty())
			assertEquals(
				AmbientWifiAuthorityEntity.STATE_REVOKED,
				database.ambientWifiFactDao().latestAuthority()?.state,
			)
			assertEquals(
				StoredSourceCallerAuthorityLoadResult.Retired,
				RoomSourceCallerAcceptedAuthorityRepository(database).load(caller),
			)
		}

	@Test
	fun `cold start retires exact Room reconstructed Cell authority without an in-memory lease`() =
		runTest {
			val policy = grant(TrackingSourceComponent.CELL)
			val consent = requireNotNull(policy[TrackingSourceComponent.CELL].ambientConsentEpoch)
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
			val identity = lease(
				AmbientTrackingSource.CELL,
				policy.revision,
				consent,
				"cell-cold-start-owner",
			)
			val caller = insertPurposeOwnerAuthority(identity, "cell-cold-start-caller")
			broker.replaceAmbientCellDemand(
				consumerId = "app:ambient:cell",
				requested = true,
				leaseIdentity = identity,
				reconciliationAttempt = 1L,
				bootId = "boot-1",
				elapsedRealtimeNanos = 100L,
				wallTimeMs = 100L,
				sourceCallerAuthorityReference = caller.value,
				retentionSnapshot = retentionSnapshot(
					SourceKind.CELL,
					policy.revision,
					consent,
					100L,
				),
			)
			val plan = assertIs<AmbientRadioRetirementPlan.Required>(
				broker.ambientRadioRetirementPlan(
					SourceKind.CELL,
					"app:ambient:cell",
				),
			)
			val coldStartBroker = SourceBroker(
				database,
				rolloutStore,
				RejectingAmbientRadioMutationLeaseGuard,
				retentionReader,
			)

			val retired = assertIs<AmbientRadioDemandResult.Inactive>(
				coldStartBroker.reduceAmbientCellDemandForRecovery(
					consumerId = "app:ambient:cell",
					plan = plan,
					reconciliationAttempt = 2L,
					bootId = "boot-1",
					elapsedRealtimeNanos = 200L,
					wallTimeMs = 200L,
				),
			)

			assertEquals(AmbientRadioDemandInactiveReason.REQUEST_DISABLED, retired.reason)
			assertTrue(database.sourceBrokerDao().currentDemands("app:ambient:cell").isEmpty())
			assertEquals(
				AmbientCellAuthorityEntity.STATE_REVOKED,
				database.ambientCellFactDao().latestAuthority()?.state,
			)
			assertEquals(
				StoredSourceCallerAuthorityLoadResult.Retired,
				RoomSourceCallerAcceptedAuthorityRepository(database).load(caller),
			)
		}

	@Test
	fun `stale reconstructed Wi-Fi lease cannot retire a durable successor`() = runTest {
		val policy = grant(TrackingSourceComponent.WIFI)
		val consent = requireNotNull(policy[TrackingSourceComponent.WIFI].ambientConsentEpoch)
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
		val oldIdentity = lease(
			AmbientTrackingSource.WIFI,
			policy.revision,
			consent,
			"wifi-reconstructed-old",
		)
		val oldCaller = insertPurposeOwnerAuthority(oldIdentity, "wifi-reconstructed-old-caller")
		broker.replaceAmbientWifiDemand(
			consumerId = "app:ambient:wifi",
			requested = true,
			leaseIdentity = oldIdentity,
			reconciliationAttempt = 1L,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			sourceCallerAuthorityReference = oldCaller.value,
			retentionSnapshot = retentionSnapshot(
				SourceKind.WIFI,
				policy.revision,
				consent,
				100L,
			),
		)
		val stalePlan = assertIs<AmbientRadioRetirementPlan.Required>(
			broker.ambientRadioRetirementPlan(
				SourceKind.WIFI,
				"app:ambient:wifi",
			),
		)
		val successorIdentity = oldIdentity.copy(ownerCasToken = "wifi-reconstructed-successor")
		val successorCaller = insertPurposeOwnerAuthority(
			successorIdentity,
			"wifi-reconstructed-successor-caller",
		)
		val successor = assertIs<AmbientRadioDemandResult.Active>(
			broker.replaceAmbientWifiDemand(
				consumerId = "app:ambient:wifi",
				requested = true,
				leaseIdentity = successorIdentity,
				reconciliationAttempt = 2L,
				bootId = "boot-1",
				elapsedRealtimeNanos = 150L,
				wallTimeMs = 150L,
				sourceCallerAuthorityReference = successorCaller.value,
				retentionSnapshot = retentionSnapshot(
					SourceKind.WIFI,
					policy.revision,
					consent,
					150L,
				),
			),
		)
		val coldStartBroker = SourceBroker(
			database,
			rolloutStore,
			RejectingAmbientRadioMutationLeaseGuard,
			retentionReader,
		)

		val stale = assertIs<AmbientRadioDemandResult.Inactive>(
			coldStartBroker.reduceAmbientWifiDemandForRecovery(
				consumerId = "app:ambient:wifi",
				plan = stalePlan,
				reconciliationAttempt = 2L,
				bootId = "boot-1",
				elapsedRealtimeNanos = 200L,
				wallTimeMs = 200L,
			),
		)

		assertEquals(AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE, stale.reason)
		assertEquals(
			successor.demand.demandId,
			database.sourceBrokerDao().currentDemands("app:ambient:wifi").single().demandId,
		)
		assertEquals(
			AmbientWifiAuthorityEntity.STATE_ACTIVE,
			database.ambientWifiFactDao().latestAuthority()?.state,
		)
		assertEquals(
			StoredSourceCallerAuthorityLoadResult.Available(
				StoredSourceCallerAuthority(
					StoredSourceCallerOrigin.PURPOSE_OWNER,
					TrackingPurpose.AMBIENT_PRODUCT,
					setOf(SourceCallerDemandIdentity(successorIdentity.purposeLeaseIdentity, null)),
				),
			),
			RoomSourceCallerAcceptedAuthorityRepository(database).load(successorCaller),
		)
	}

	private suspend fun insertPurposeOwnerAuthority(
		identity: AmbientReconciliationIdentity,
		reference: String,
	): SourceCallerReplayReference {
		val replayReference = SourceCallerReplayReference(reference)
		assertTrue(
			RoomSourceCallerAcceptedAuthorityRepository(database).insertIfAbsent(
				replayReference,
				StoredSourceCallerAuthority(
					origin = StoredSourceCallerOrigin.PURPOSE_OWNER,
					purpose = TrackingPurpose.AMBIENT_PRODUCT,
					permittedDemandIdentities = setOf(
						SourceCallerDemandIdentity(identity.purposeLeaseIdentity, null),
					),
				),
				createdAtMs = 95L,
			),
		)
		return replayReference
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

	private suspend fun retentionSnapshot(
		source: SourceKind,
		policyRevision: Long,
		consentEpoch: Long,
		at: Long,
	): LiveAmbientRetentionSnapshot {
		retentionReader.effectiveBootId = "boot-1"
		retentionReader.effectiveElapsedRealtimeNanos = 90L
		retentionReader.effectiveWallTimeMs = 90L
		return requireNotNull(broker.captureLiveAmbientRetentionSnapshot(
			source = source,
			sourcePolicyRevision = policyRevision,
			ambientConsentEpoch = consentEpoch,
			collectedDataEpoch = 3L,
			currentBootId = "boot-1",
			currentElapsedRealtimeNanos = at,
			currentWallTimeMs = at,
		))
	}

	private fun lease(
		source: AmbientTrackingSource,
		policyRevision: Long,
		consentEpoch: Long,
		ownerCasToken: String,
	) = AmbientReconciliationIdentity(
		source = source,
		policyRevision = policyRevision,
		consentEpoch = consentEpoch,
		collectedDataEpoch = 3L,
		rolloutRevision = 1L,
		ownerCasToken = ownerCasToken,
		executionRevision = 1L,
		retainedFromMs = null,
		retentionPolicyId = "privacy:${source.name.lowercase()}:ambient:v1",
		retentionApprovalRevision = 1L,
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
