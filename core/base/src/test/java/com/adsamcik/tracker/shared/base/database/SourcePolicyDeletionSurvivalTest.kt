package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourcePolicyDeletionSurvivalTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
			.allowMainThreadQueries()
			.build()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `collected data deletion preserves policy and consent fences`() = runTest {
		val dao = database.sourcePolicyDao()
		dao.ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_UNINITIALIZED,
				currentPolicyRevision = 0,
				legacySettingsFingerprint = null,
				updatedAtMs = 0,
			),
		)
		dao.insertConsentEpochs(
			buildList {
				(1..6).forEach { sourceKind ->
					listOf("SESSION_CAPTURE", "CONTROL", "AMBIENT_PRODUCT").forEach { purpose ->
						add(consent(sourceKind, purpose, 0, false, 1))
					}
				}
				add(consent(1, "SESSION_CAPTURE", 1, true, 1))
				add(consent(1, "SESSION_CAPTURE", 2, false, 2))
			},
		)
		dao.insertPolicies(
			buildList {
				(1..6).forEach { sourceKind -> add(policy(1, sourceKind, sourceKind == 1)) }
				(1..6).forEach { sourceKind -> add(policy(2, sourceKind, false)) }
			},
		)
		dao.compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_UNINITIALIZED,
			expectedRevision = 0,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = 2,
			legacySettingsFingerprint = "fingerprint",
			updatedAtMs = 20,
		) shouldBe 1
		val demand = SourceDemandEntity(
			demandId = "session-1:1:location:capture",
			consumerId = "session:session-1",
			sourceKind = 1,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = "session-1",
			serviceRunId = "run-1",
			manifestRevision = 1,
			lifecycleLeaseGeneration = 1,
			sourcePolicyRevision = 1,
			consentEpoch = 1,
			persistenceEligible = true,
			qosCode = 2,
			maximumAgeMs = 30_000,
			desiredLatencyMs = 1_000,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = 10,
			requestedAtMs = 20,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
		)
		database.sourceBrokerDao().insertDemands(listOf(demand))
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = 1,
				registrationGeneration = 1,
				sourceInstanceId = "location-provider-1",
				ownerScope = "source-broker:1",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "physical-config",
				collectedDataEpoch = 1,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 20,
				reservedElapsedRealtimeNanos = 9,
				acceptedAtMs = 21,
				acceptedElapsedRealtimeNanos = 10,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(1, 1, 1, listOf(demand), "boot-1", 10, 20),
		)

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = 2,
			retainedFromMs = 30,
			updatedAtMs = 40,
		)

		dao.authority()?.bootstrapState shouldBe SourcePolicyAuthorityEntity.STATE_ACTIVE
		dao.authority()?.currentPolicyRevision shouldBe 2
		dao.policiesAtRevision(1).size shouldBe 6
		dao.policiesAtRevision(2).also { policies ->
			policies.size shouldBe 6
			policies.all { !it.enabled } shouldBe true
		}
		dao.consentHistory(1, "SESSION_CAPTURE").map { it.epoch to it.eligible } shouldBe
			listOf(0L to false, 1L to true, 2L to false)
		database.sourceBrokerDao().demandHistory(demand.consumerId).size shouldBe 0
		database.sourceBrokerDao().registration(1, 1) shouldBe null
		database.sourceBrokerDao().latestAuthorization(1, 1).size shouldBe 0
		database.sourceEvidenceStateDao().get()?.also { state ->
			state.collectedDataEpoch shouldBe 2L
			state.retainedFromMs shouldBe 30L
		}
	}

	private fun consent(
		sourceKind: Int,
		purpose: String,
		epoch: Long,
		eligible: Boolean,
		policyRevision: Long,
	) = SourceConsentEpochEntity(
		sourceKind = sourceKind,
		purpose = purpose,
		epoch = epoch,
		eligible = eligible,
		persistenceEligible = eligible,
		policyRevision = policyRevision,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = policyRevision * 10,
		effectiveWallTimeMs = policyRevision * 20,
		changeReason = "TEST",
	)

	private fun policy(revision: Long, sourceKind: Int, enabled: Boolean) = SourcePolicyEntity(
		policyRevision = revision,
		sourceKind = sourceKind,
		enabled = enabled,
		qosCode = if (enabled) 2 else 0,
		locationMinTimeSeconds = 2.takeIf { sourceKind == 1 },
		locationMinDistanceMeters = 10.takeIf { sourceKind == 1 },
		locationRequiredAccuracyMeters = 50.takeIf { sourceKind == 1 },
		capturePersistenceEligible = enabled,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = 1.takeIf { enabled }?.toLong(),
		controlConsentEpoch = null,
		ambientConsentEpoch = null,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = revision * 10,
		effectiveWallTimeMs = revision * 20,
		changeReason = "TEST",
	)
}
