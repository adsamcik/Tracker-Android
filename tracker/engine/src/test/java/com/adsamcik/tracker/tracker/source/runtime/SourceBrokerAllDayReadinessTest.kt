package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityReader
import com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Source-only regression authorship; execution is deferred to final convergence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceBrokerAllDayReadinessTest {
	private lateinit var database: AppDatabase
	private lateinit var broker: SourceBroker
	private lateinit var rolloutStore: RoomTrackingRolloutStateStore

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `Location reserves all-day collection without an ambient mode or session`() = runTest {
		val (identity, retention, prepared) = prepare(SourceKind.LOCATION)
		val active = assertIs<AmbientSourceDemandResult.Active>(
			broker.replaceAmbientSourceDemand(identity, "boot-1", 100L, 100L, retention, prepared),
		)
		assertSessionlessAuthority(SourceKind.LOCATION, active.demand, prepared)
		assertEquals(identity, broker.currentAmbientSourceIdentity())
		assertEquals(false, broker.retireAmbientSourceDemand(
			identity.copy(ownerCasToken = "stale-owner"), "boot-1", 120L, 120L,
		))
		assertEquals(listOf(active.demand), broker.authorizationDemands(SourceKind.LOCATION))
	}

	@Test
	fun `Steps reserves all-day collection without an ambient mode or session`() = runTest {
		val (identity, retention, prepared) = prepare(SourceKind.STEPS)
		val active = assertIs<AmbientStepsDemandResult.Active>(
			broker.replaceAmbientStepsDemand(
				consumerId = "app:ambient:steps",
				mechanism = AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
				leaseIdentity = identity,
				bootId = "boot-1",
				elapsedRealtimeNanos = 100L,
				wallTimeMs = 100L,
				retentionSnapshot = retention,
				preparedCallerAcceptance = prepared,
			),
		)
		assertSessionlessAuthority(SourceKind.STEPS, active.demand, prepared)
		assertEquals("privacy:steps:ambient:v1", active.demand.liveAmbientRetentionPolicyId)
		assertEquals(1L, active.demand.liveAmbientRetentionApprovalRevision)
	}

	@Test
	fun `Activity reserves independent all-day collection under global retention without a session`() = runTest {
		assertNativeAllDayReservation(SourceKind.ACTIVITY)
	}

	@Test
	fun `Pressure reserves independent all-day collection under global retention without a session`() = runTest {
		assertNativeAllDayReservation(SourceKind.PRESSURE)
	}

	private suspend fun assertNativeAllDayReservation(source: SourceKind) {
		val (identity, retention, prepared) = prepare(source)
		val active = assertIs<AmbientSourceDemandResult.Active>(
			broker.replaceAmbientSourceDemand(identity, "boot-1", 100L, 100L, retention, prepared),
		)
		assertSessionlessAuthority(source, active.demand, prepared)
		assertEquals("global-retention-test", active.demand.liveAmbientRetentionPolicyId)
		assertEquals(identity, broker.currentAmbientSourceIdentity(source))
		assertEquals(false, broker.retireAmbientSourceDemand(
			identity.copy(ownerCasToken = "stale-owner"), "boot-1", 120L, 120L,
		))
		assertEquals(listOf(active.demand), broker.authorizationDemands(source))
	}

	private suspend fun assertSessionlessAuthority(
		source: SourceKind,
		demand: SourceDemandEntity,
		prepared: PreparedSourceCallerAcceptance,
	) {
		assertEquals(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE.mask or
			CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE.mask,
			rolloutStore.load().captureModeMasks.getValue(source))
		assertEquals(null, database.sourceSessionDao().activeSession())
		assertEquals(SourceBrokerPurpose.AMBIENT_PRODUCT, demand.purpose)
		assertEquals(null, demand.logicalTrackingId)
		assertEquals(null, demand.serviceRunId)
		assertEquals(null, demand.manifestRevision)
		assertEquals(null, demand.lifecycleLeaseGeneration)
		assertEquals(true, demand.persistenceEligible)
		assertEquals(prepared.receipt.reference.value, demand.sourceCallerAuthorityReference)
		assertEquals(StoredSourceCallerAuthorityLoadResult.Available(prepared.authority),
			RoomSourceCallerAcceptedAuthorityRepository(database).load(prepared.receipt.reference))
		assertEquals(listOf(demand), broker.authorizationDemands(source))
		SourceKind.entries.filter { it != source }.forEach {
			assertTrue(broker.authorizationDemands(it).isEmpty())
		}
	}

	// Keep each all-day source preparation with its writer, retention and exact accepted caller fixture
	// together.
	@Suppress("LongMethod")
	private suspend fun prepare(source: SourceKind): Triple<AmbientReconciliationIdentity,
		LiveAmbientRetentionSnapshot, PreparedSourceCallerAcceptance> {
		val location = source == SourceKind.LOCATION
		val (binding, destination, owner) = when (source) {
			SourceKind.LOCATION -> Triple(ExecutableSourceLaneCatalog.PREFERRED_LOCATION_CANONICAL_HANDOFF,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
				SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE)
			SourceKind.STEPS -> Triple(ExecutableSourceLaneCatalog.PREFERRED_STEPS_SESSION_FACTS,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS)
			SourceKind.ACTIVITY -> Triple(ExecutableSourceLaneCatalog.PREFERRED_ACTIVITY_SESSION_FACTS,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
				SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS)
			SourceKind.PRESSURE -> Triple(ExecutableSourceLaneCatalog.PREFERRED_PRESSURE_SESSION_FACTS,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
				SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS)
			else -> error("This fixture covers direct all-day source owners")
		}
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 3L, updatedAtMs = 1L))
		database.sourceDestinationOwnerDao().insertIfAbsent(SourceDestinationOwnerEntity(
			sourceKind = source.stableCode,
			destination = destination,
			owner = owner,
			ownerGeneration = if (location) {
				SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION
			}
				else {
				SourceWriterGenerationContract.canonicalOwnerGeneration(binding.bindingGeneration)
			},
			updatedAtMs = 1L,
		))
		rolloutStore = installCanonicalProductLanesForTest(database, listOf(binding), 1L)
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", 10L, 10L)
		}.bootstrapFromLegacy(TrackingParamsState(
			locationEnabled = false,
			activityEnabled = source == SourceKind.ACTIVITY,
			stepsEnabled = source == SourceKind.STEPS,
			barometerEnabled = source == SourceKind.PRESSURE,
			ambientLocationEnabled = location,
			locationProfileVersion = 1,
			locationBalanceLevel = 3,
			locationBackgroundMode = LocationBackgroundMode.ADAPTIVE,
			legacySettingsMigrationCompleted = true,
		))
		val component = TrackingSourceComponent.valueOf(source.name)
		val consent = requireNotNull(policy[component].ambientConsentEpoch)
		val retention = prepareRetention(source, component, policy.revision, consent)
		val grant = retention.grants.getValue(source)
		val identity = AmbientReconciliationIdentity(
			source = AmbientTrackingSource.valueOf(source.name),
			policyRevision = policy.revision,
			consentEpoch = grant.ambientConsentEpoch,
			collectedDataEpoch = 3L,
			rolloutRevision = rolloutStore.load().revision,
			ownerCasToken = "all-day-${source.name.lowercase()}-owner",
			executionRevision = 1L,
			retainedFromMs = grant.retainedFromMs,
			retentionPolicyId = grant.opaquePolicyId,
			retentionApprovalRevision = grant.approvalRevision,
		)
		val caller = SourceCallerDemandIdentity(identity.purposeLeaseIdentity, null)
		val prepared = PreparedSourceCallerAcceptance(
			SourceCallerAcceptanceReceipt(SourceCallerReplayReference("all-day-${source.name.lowercase()}-caller"),
				setOf(caller)),
			StoredSourceCallerAuthority(StoredSourceCallerOrigin.PURPOSE_OWNER,
				TrackingPurpose.AMBIENT_PRODUCT, setOf(caller)),
			95L,
		)
		return Triple(identity, retention, prepared)
	}

	private suspend fun prepareRetention(
		source: SourceKind,
		component: TrackingSourceComponent,
		policyRevision: Long,
		consentEpoch: Long,
	): LiveAmbientRetentionSnapshot {
		if (source == SourceKind.ACTIVITY || source == SourceKind.PRESSURE) {
			val approval = CurrentRetentionAuthority.Approved("global-retention-test", 1L,
				"boot-1", 10L, 10L, 3L, null)
			val reader = mockk<RetentionAuthorityReader>()
			coEvery { reader.currentLiveAmbient(component, policyRevision, consentEpoch, 3L, null) } returns approval
			coEvery { reader.isCurrentLiveAmbientAt(component, policyRevision, consentEpoch, 3L, null,
				approval.opaquePolicyId, approval.approvalRevision, "boot-1", 100L, 100L) } returns true
			broker = SourceBroker(database, rolloutStore, reader)
			return requireNotNull(broker.captureLiveAmbientRetentionSnapshot(source, policyRevision,
				consentEpoch, 3L, "boot-1", 100L, 100L))
		}
		val reader = TestLiveAmbientRetentionAuthorityReader()
		broker = SourceBroker(database, rolloutStore, reader)
		return reader.installCurrent(database, component, policyRevision, consentEpoch, 3L, "boot-1")
	}
}
