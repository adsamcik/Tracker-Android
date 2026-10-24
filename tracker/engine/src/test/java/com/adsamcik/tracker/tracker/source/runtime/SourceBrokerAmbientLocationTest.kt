package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceBrokerAmbientLocationTest : SourceBrokerRoomTestFixture() {
	@Test
	fun `background Location reserves only its exact sessionless authority and preserves retries`() = runTest {
		val (identity, retention, prepared) = ambientLocationFixture()
		val result = subject.replaceAmbientSourceDemand(identity, "boot-1", 100L, 100L, retention, prepared)
		val demand = (result as AmbientSourceDemandResult.Active).demand
		demand.logicalTrackingId shouldBe null
		demand.serviceRunId shouldBe null
		demand.manifestRevision shouldBe null
		demand.persistenceEligible shouldBe true
		demand.maximumAgeMs shouldBe 120_000L
		demand.desiredLatencyMs shouldBe 10_000L
		subject.currentAmbientSourceIdentity() shouldBe identity
		subject.replaceAmbientSourceDemand(identity, "boot-1", 110L, 110L, retention, prepared) shouldBe result
		subject.authorizationDemands(SourceKind.LOCATION) shouldBe listOf(demand)
		SourceKind.entries.filter { it != SourceKind.LOCATION }.forEach {
			subject.authorizationDemands(it).shouldBeEmpty()
		}
		subject.retireAmbientSourceDemand(identity.copy(ownerCasToken = "stale"), "boot-1", 120L, 120L) shouldBe false
		subject.authorizationDemands(SourceKind.LOCATION) shouldBe listOf(demand)
		subject.retireAmbientSourceDemand(identity, "boot-1", 130L, 130L) shouldBe true
		subject.authorizationDemands(SourceKind.LOCATION).shouldBeEmpty()
	}

	@Test
	fun `background Location cannot use a stale retention snapshot`() = runTest {
		val (identity, retention, prepared) = ambientLocationFixture()
		database.openHelper.writableDatabase.execSQL("UPDATE ambient_location_retention_authority SET effect_checksum = 'corrupt'")
		subject.replaceAmbientSourceDemand(identity, "boot-1", 100L, 100L, retention, prepared) shouldBe
			AmbientSourceDemandResult.Inactive("LOCATION_RETENTION_CHANGED")
		subject.authorizationDemands(SourceKind.LOCATION).shouldBeEmpty()
		RoomSourceCallerAcceptedAuthorityRepository(database).load(prepared.receipt.reference) shouldBe
			StoredSourceCallerAuthorityLoadResult.Missing
	}

	@Test
	fun `persisted background consent survives reboot with a fresh physical retention boundary`() = runTest {
		val (identity, retention, prepared) = ambientLocationFixture(policyBoot = "previous-boot")
		val accepted = subject.replaceAmbientSourceDemand(identity, "boot-1", 100L, 100L, retention, prepared)
		(accepted is AmbientSourceDemandResult.Active) shouldBe true
		val wrongBoot = subject.replaceAmbientSourceDemand(identity, "unacknowledged-boot", 100L, 100L, retention, prepared)
		wrongBoot shouldBe AmbientSourceDemandResult.Inactive("LOCATION_RETENTION_CHANGED")
	}

	@Test
	fun `delayed all-day preparation cannot backdate a newer session membership`() = runTest {
		val (identity, retention, prepared) = ambientLocationFixture()
		val clock = FixedClock(100L, 100L)
		useClock(clock)
		val original = (subject.replaceAmbientSourceDemand(identity, "boot-1", 100L, 100L,
			retention, prepared) as AmbientSourceDemandResult.Active).demand
		val session = original.copy(demandId = "session-after-preparation", consumerId = "session:logical",
			purpose = SourceBrokerPurpose.SESSION_CAPTURE, logicalTrackingId = "logical", serviceRunId = "run",
			manifestRevision = 2L, lifecycleLeaseGeneration = 2L, requestedElapsedRealtimeNanos = 200L,
			requestedAtMs = 200L, sourceCallerAuthorityReference = "session-caller",
			liveAmbientRetentionPolicyId = null, liveAmbientRetentionApprovalRevision = null)
		database.sourceBrokerDao().insertDemands(listOf(session))
		installLocationRegistration()
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(
			SourceKind.LOCATION.stableCode, 1L, 1L, listOf(original, session), "boot-1", 200L, 200L))
		clock.setTime(300L, 300L)
		val next = prepared.copy(receipt = SourceCallerAcceptanceReceipt(
			SourceCallerReplayReference("next-location-caller"), prepared.receipt.permittedDemandIdentities))

		val replacement = (subject.replaceAmbientSourceDemand(identity, "boot-1", 150L, 150L,
			retention, next) as AmbientSourceDemandResult.Active).demand

		replacement.requestedElapsedRealtimeNanos shouldBe 300L
		replacement.requestedAtMs shouldBe 300L
		database.sourceBrokerDao().demandsByIds(listOf(original.demandId)).single().retireElapsedRealtimeNanos shouldBe 300L
		val previous = requireNotNull(database.sourceBrokerDao().authorizationAt(1, 1L, "boot-1", 250L)
			.toAuthorizationSnapshotOrNull())
		previous.authorizationRevision shouldBe 1L
		val latest = requireNotNull(database.sourceBrokerDao().latestAuthorization(1, 1L).toAuthorizationSnapshotOrNull())
		latest.effectiveElapsedRealtimeNanos shouldBe 300L
		latest.members.mapNotNull { it.demandId }.toSet() shouldBe setOf(session.demandId, replacement.demandId)
	}

	@Test
	fun `delayed all-day preparation checks retention at its fresh application boundary`() = runTest {
		val (identity, retention, prepared) = ambientLocationFixture(retentionStart = 200L)
		useClock(FixedClock(300L, 300L))

		val result = subject.replaceAmbientSourceDemand(identity, "boot-1", 100L, 100L, retention, prepared)

		(result as AmbientSourceDemandResult.Active).demand.requestedElapsedRealtimeNanos shouldBe 300L
	}

	private fun useClock(clock: FixedClock) {
		subject = SourceBroker(database, rolloutStore, RejectingAmbientRadioMutationLeaseGuard,
			RoomSourceCallerAcceptedAuthorityRepository(database), retentionReader, clock)
	}

	private suspend fun installLocationRegistration() {
		database.sourceBrokerDao().insertRegistration(ProviderRegistrationGenerationEntity(
			sourceKind = SourceKind.LOCATION.stableCode, registrationGeneration = 1L, sourceInstanceId = "location-provider",
			ownerScope = "source-broker:1", clockDomainId = "boot-1", physicalConfigurationFingerprint = "physical-config",
			collectedDataEpoch = 3L, providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			providerProcessIncarnationId = "process-1", status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
			reservedAtMs = 90L, reservedElapsedRealtimeNanos = 90L, acceptedAtMs = 100L, acceptedElapsedRealtimeNanos = 100L,
			retiredAtMs = null, retiredElapsedRealtimeNanos = null, failureCode = null))
	}

	private suspend fun ambientLocationFixture(
		policyBoot: String = "boot-1",
		retentionStart: Long = 0L,
	): Triple<AmbientReconciliationIdentity, LiveAmbientRetentionSnapshot, PreparedSourceCallerAcceptance> {
		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime(policyBoot, elapsed++, elapsed)
		}.bootstrapFromLegacy(TrackingParamsState(locationEnabled = false, ambientLocationEnabled = true,
			locationProfileVersion = 1, locationBalanceLevel = 3,
			locationBackgroundMode = com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode.ADAPTIVE,
			legacySettingsMigrationCompleted = true))
		val consent = requireNotNull(policy[TrackingSourceComponent.LOCATION].ambientConsentEpoch)
		val retention = retentionReader.installCurrent(database, TrackingSourceComponent.LOCATION,
			policy.revision, consent, 3L, "boot-1", retentionStart, retentionStart)
		val grant = retention.grants.getValue(SourceKind.LOCATION)
		val identity = AmbientReconciliationIdentity(AmbientTrackingSource.LOCATION, policy.revision,
			consent, 3L, rolloutStore.load().revision, "location-owner", 1L, grant.retainedFromMs,
			grant.opaquePolicyId, grant.approvalRevision)
		val caller = SourceCallerDemandIdentity(identity.purposeLeaseIdentity, null)
		val prepared = PreparedSourceCallerAcceptance(
			SourceCallerAcceptanceReceipt(SourceCallerReplayReference("location-caller"), setOf(caller)),
			StoredSourceCallerAuthority(StoredSourceCallerOrigin.PURPOSE_OWNER, TrackingPurpose.AMBIENT_PRODUCT, setOf(caller)),
			95L)
		return Triple(identity, retention, prepared)
	}
}
