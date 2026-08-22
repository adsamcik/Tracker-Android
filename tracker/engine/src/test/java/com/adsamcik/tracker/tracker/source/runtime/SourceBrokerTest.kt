package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceBrokerTest {
	private lateinit var database: AppDatabase
	private lateinit var subject: SourceBroker
	private var elapsed = 10L

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		subject = SourceBroker(database)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `manifest bindings become explicit capture and continuation control demands`() {
		val demands = subject.buildSessionDemands(
			logicalTrackingId = "session-1",
			serviceRunId = "run-1",
			manifestRevision = 4L,
			lifecycleLeaseGeneration = 2L,
			policyRevision = 9L,
			bindings = listOf(
				binding(SourceKind.LOCATION, SourceBrokerPurpose.SESSION_CAPTURE, 12L, true),
				binding(SourceKind.ACTIVITY, "CONTROL", 15L, false),
			),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 200L,
		)

		demands.map { it.sourceKind to it.purpose } shouldBe listOf(
			SourceKind.ACTIVITY.stableCode to SourceBrokerPurpose.CONTROL_CONTINUATION,
			SourceKind.LOCATION.stableCode to SourceBrokerPurpose.SESSION_CAPTURE,
		)
		demands.single { it.purpose == SourceBrokerPurpose.CONTROL_CONTINUATION }
			.persistenceEligible shouldBe false
		demands.forEach { it.demandId.shouldNotBeBlank() }
	}

	@Test
	fun `automatic control reenable creates a fresh auditable demand and revoke fails closed`() = runTest {
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val initial = policy.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
		val granted = policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.STEPS,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_CONTROL_GRANT",
		)

		val first = requireNotNull(subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:steps",
			source = SourceKind.STEPS,
			enabled = true,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		))
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "steps-provider-1",
				ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "steps-physical-config",
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 90L,
				reservedElapsedRealtimeNanos = 90L,
				acceptedAtMs = 100L,
				acceptedElapsedRealtimeNanos = 100L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.STEPS.stableCode,
				1L,
				1L,
				listOf(first),
				"boot-1",
				100L,
				100L,
			),
		)
		subject.replaceAutomaticControlDemand(
			"app:automation:steps",
			SourceKind.STEPS,
			false,
			"boot-1",
			200L,
			200L,
			30_000L,
			5_000L,
		)
		val second = requireNotNull(subject.replaceAutomaticControlDemand(
			"app:automation:steps",
			SourceKind.STEPS,
			true,
			"boot-1",
			300L,
			300L,
			30_000L,
			5_000L,
		))
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 2L,
				sourceInstanceId = "steps-provider-1",
				ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "steps-physical-config-v2",
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
				reservedAtMs = 350L,
				reservedElapsedRealtimeNanos = 350L,
				acceptedAtMs = null,
				acceptedElapsedRealtimeNanos = null,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.STEPS.stableCode,
				2L,
				4L,
				listOf(second),
				"boot-1",
				350L,
				350L,
			),
		)

		(first.demandId == second.demandId) shouldBe false
		database.sourceBrokerDao().demandHistory("app:automation:steps").map { it.status } shouldBe
			listOf(SourceDemandEntity.STATUS_RETIRED, SourceDemandEntity.STATUS_ACTIVE)

		policy.setNonCaptureConsent(
			expectedPolicyRevision = granted.revision,
			source = TrackingSourceComponent.STEPS,
			purpose = SourcePurpose.CONTROL,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_CONTROL_REVOKE",
		)
		subject.replaceAutomaticControlDemand(
			"app:automation:steps",
			SourceKind.STEPS,
			true,
			"boot-1",
			400L,
			400L,
			30_000L,
			5_000L,
		) shouldBe null
		database.sourceBrokerDao().currentDemands("app:automation:steps") shouldBe emptyList()
		database.sourceBrokerDao().authorizationAt(
			SourceKind.STEPS.stableCode,
			1L,
			"boot-1",
			399L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe false
		database.sourceBrokerDao().authorizationAt(
			SourceKind.STEPS.stableCode,
			1L,
			"boot-1",
			400L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
		database.sourceBrokerDao().authorizationAt(
			SourceKind.STEPS.stableCode,
			2L,
			"boot-1",
			400L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
	}

	@Test
	fun `retiring session demand remains registration eligible until drain completes`() = runTest {
		val demand = subject.buildSessionDemands(
			logicalTrackingId = "session-1",
			serviceRunId = "run-1",
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 2L,
			policyRevision = 9L,
			bindings = listOf(
				binding(SourceKind.ACTIVITY, SourceBrokerPurpose.SESSION_CAPTURE, 15L, true)
					.copy(manifestRevision = 1L),
			),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 200L,
		).single()
		database.sourceBrokerDao().insertDemands(listOf(demand))
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "activity-provider-1",
				ownerScope = "source-broker:${SourceKind.ACTIVITY.stableCode}",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "physical-config",
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 90L,
				reservedElapsedRealtimeNanos = 90L,
				acceptedAtMs = 100L,
				acceptedElapsedRealtimeNanos = 100L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceKind.ACTIVITY.stableCode,
				1L,
				1L,
				listOf(demand),
				"boot-1",
				100L,
				200L,
			),
		)

		subject.markSessionDemandsRetiring("session-1", "boot-1", 300L, 300L)

		database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode)
			.map { it.status } shouldBe listOf(SourceDemandEntity.STATUS_RETIRING)
		database.sourceBrokerDao().authorizationAt(
			SourceKind.ACTIVITY.stableCode,
			1L,
			"boot-1",
			299L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe false
		database.sourceBrokerDao().authorizationAt(
			SourceKind.ACTIVITY.stableCode,
			1L,
			"boot-1",
			300L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
		database.sourceBrokerDao().registration(
			SourceKind.ACTIVITY.stableCode,
			1L,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE

		subject.retireSessionDemands("session-1", "boot-1", 400L, 400L)
		database.sourceBrokerDao().activeDemands(SourceKind.ACTIVITY.stableCode) shouldBe emptyList()
	}

	private fun binding(
		source: SourceKind,
		purpose: String,
		consentEpoch: Long,
		persistenceEligible: Boolean,
	) = SessionManifestSourceEntity(
		logicalTrackingId = "session-1",
		manifestRevision = 4L,
		sourceKind = source.stableCode,
		purpose = purpose,
		consentEpoch = consentEpoch,
		persistenceEligible = persistenceEligible,
		qosCode = 2,
	)
}
