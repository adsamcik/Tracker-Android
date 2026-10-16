package com.adsamcik.tracker.tracker.source.runtime

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
		val result = subject.replaceAmbientLocationDemand(identity, "boot-1", 100L, 100L, retention, prepared)
		val demand = (result as AmbientLocationDemandResult.Active).demand
		demand.logicalTrackingId shouldBe null
		demand.serviceRunId shouldBe null
		demand.manifestRevision shouldBe null
		demand.persistenceEligible shouldBe true
		demand.maximumAgeMs shouldBe 120_000L
		demand.desiredLatencyMs shouldBe 10_000L
		subject.currentAmbientLocationIdentity() shouldBe identity
		subject.replaceAmbientLocationDemand(identity, "boot-1", 110L, 110L, retention, prepared) shouldBe result
		subject.authorizationDemands(SourceKind.LOCATION) shouldBe listOf(demand)
		SourceKind.entries.filter { it != SourceKind.LOCATION }.forEach {
			subject.authorizationDemands(it).shouldBeEmpty()
		}
		subject.retireAmbientLocationDemand(identity.copy(ownerCasToken = "stale"), "boot-1", 120L, 120L) shouldBe false
		subject.authorizationDemands(SourceKind.LOCATION) shouldBe listOf(demand)
		subject.retireAmbientLocationDemand(identity, "boot-1", 130L, 130L) shouldBe true
		subject.authorizationDemands(SourceKind.LOCATION).shouldBeEmpty()
	}

	@Test
	fun `background Location cannot use a stale retention snapshot`() = runTest {
		val (identity, retention, prepared) = ambientLocationFixture()
		database.openHelper.writableDatabase.execSQL("UPDATE ambient_location_retention_authority SET effect_checksum = 'corrupt'")
		subject.replaceAmbientLocationDemand(identity, "boot-1", 100L, 100L, retention, prepared) shouldBe
			AmbientLocationDemandResult.Inactive("LOCATION_RETENTION_CHANGED")
		subject.authorizationDemands(SourceKind.LOCATION).shouldBeEmpty()
		RoomSourceCallerAcceptedAuthorityRepository(database).load(prepared.receipt.reference) shouldBe
			StoredSourceCallerAuthorityLoadResult.Missing
	}

	@Test
	fun `persisted background consent survives reboot with a fresh physical retention boundary`() = runTest {
		val (identity, retention, prepared) = ambientLocationFixture(policyBoot = "previous-boot")
		val accepted = subject.replaceAmbientLocationDemand(identity, "boot-1", 100L, 100L, retention, prepared)
		(accepted is AmbientLocationDemandResult.Active) shouldBe true
		val wrongBoot = subject.replaceAmbientLocationDemand(identity, "unacknowledged-boot", 100L, 100L, retention, prepared)
		wrongBoot shouldBe AmbientLocationDemandResult.Inactive("LOCATION_RETENTION_CHANGED")
	}

	private suspend fun ambientLocationFixture(policyBoot: String = "boot-1"): Triple<AmbientReconciliationIdentity, LiveAmbientRetentionSnapshot, PreparedSourceCallerAcceptance> {
		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime(policyBoot, elapsed++, elapsed)
		}.bootstrapFromLegacy(TrackingParamsState(locationEnabled = false, ambientLocationEnabled = true,
			locationProfileVersion = 1, locationBalanceLevel = 3,
			locationBackgroundMode = com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode.ADAPTIVE,
			legacySettingsMigrationCompleted = true))
		val consent = requireNotNull(policy[TrackingSourceComponent.LOCATION].ambientConsentEpoch)
		val retention = retentionReader.installCurrent(database, TrackingSourceComponent.LOCATION,
			policy.revision, consent, 3L, "boot-1")
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
