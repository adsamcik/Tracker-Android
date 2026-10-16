package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.api.registration.ActivityProviderCleanupResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationDemand
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationFailureCode
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationSnapshot
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryResult
import com.adsamcik.tracker.tracker.api.AutomaticControlContainmentAttemptResult
import com.adsamcik.tracker.tracker.api.AutomaticControlContainmentLoopResult
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AutomaticTrackingUnavailableReason
import com.adsamcik.tracker.tracker.api.reconcileUnavailableAutomaticControl
import com.adsamcik.tracker.tracker.api.runAutomaticControlContainmentRetryLoop
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjectionLane
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceBrokerAutomaticControlTest : SourceBrokerRoomTestFixture() {
	@Test
	fun `Activity automatic control enable disable and revoke rotate an independent epoch`() = runTest {
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}

		val initial = policy.bootstrapFromLegacy(
			TrackingParamsState(legacySettingsMigrationCompleted = true),
		)
		val granted = policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_ACTIVITY_CONTROL_GRANT",
		)

		suspend fun replace(enabled: Boolean, at: Long) = subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:activity",
			source = SourceKind.ACTIVITY,
			enabled = enabled,
			bootId = "boot-1",
			elapsedRealtimeNanos = at,
			wallTimeMs = at,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		)

		requireNotNull(replace(true, 100L))
		database.activityAutomationEpochDao().current()?.let { it.epoch to it.automaticControlEnabled } shouldBe
			(2L to true)
		requireNotNull(replace(true, 150L))
		database.activityAutomationEpochDao().current()?.epoch shouldBe 2L
		replace(false, 200L) shouldBe null
		database.activityAutomationEpochDao().current()?.let { it.epoch to it.automaticControlEnabled } shouldBe
			(3L to false)
		requireNotNull(replace(true, 300L))
		database.activityAutomationEpochDao().current()?.let { it.epoch to it.automaticControlEnabled } shouldBe
			(4L to true)

		// The policy boundary shares the same monotonic clock as provider demand changes.
		elapsed = 400L
		policy.setNonCaptureConsent(
			expectedPolicyRevision = granted.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_ACTIVITY_CONTROL_REVOKE",
		)
		replace(true, 400L) shouldBe null
		database.activityAutomationEpochDao().current()?.let { it.epoch to it.automaticControlEnabled } shouldBe
			(5L to false)
	}

	// Keep retryable automatic containment and the surviving manual Activity owner in one composition.
	@Suppress("LongMethod")
	@Test
	fun `ordinary coherent initialization retires old automatic owner and preserves manual Activity`() =
		runTest {
			val policyRepository = RoomSourcePolicyRepository(database) {
				SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
			}
			val policy = policyRepository.bootstrapFromLegacy(
				TrackingParamsState(legacySettingsMigrationCompleted = true),
			)
			val automatic = requireNotNull(subject.replaceAutomaticControlDemand(
				consumerId = "app:automatic-start:activity",
				source = SourceKind.ACTIVITY,
				enabled = true,
				bootId = "boot-1",
				elapsedRealtimeNanos = 100L,
				wallTimeMs = 100L,
				maximumAgeMs = 30_000L,
				desiredLatencyMs = 5_000L,
			))
			val activityPolicy = policy[TrackingSourceComponent.ACTIVITY]
			val manual = automatic.copy(
				demandId = "manual-activity-capture",
				consumerId = "session:manual-activity",
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = "manual-activity",
				serviceRunId = "manual-activity-run",
				manifestRevision = 1L,
				lifecycleLeaseGeneration = 1L,
				consentEpoch = requireNotNull(activityPolicy.captureConsentEpoch),
				persistenceEligible = true,
			)
			database.sourceBrokerDao().insertDemands(listOf(manual))
			database.sourceBrokerDao().insertRegistration(
				ProviderRegistrationGenerationEntity(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					registrationGeneration = 1L,
					sourceInstanceId = "activity-provider-1",
					ownerScope = "source-broker:${SourceKind.ACTIVITY.stableCode}",
					providerResidency =
						ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
					providerProcessIncarnationId = null,
					clockDomainId = "boot-1",
					physicalConfigurationFingerprint = "activity-transitions",
					collectedDataEpoch = 3L,
					status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
					reservedAtMs = 100L,
					reservedElapsedRealtimeNanos = 100L,
					acceptedAtMs = 101L,
					acceptedElapsedRealtimeNanos = 101L,
					retiredAtMs = null,
					retiredElapsedRealtimeNanos = null,
					failureCode = null,
				),
			)
			database.sourceBrokerDao().insertAuthorizations(
				SourceBrokerAuthorization.rows(
					sourceKind = SourceKind.ACTIVITY.stableCode,
					registrationGeneration = 1L,
					authorizationRevision = 1L,
					demands = listOf(automatic, manual),
					effectiveBootId = "boot-1",
					effectiveElapsedRealtimeNanos = 101L,
					effectiveWallTimeMs = 101L,
				),
			)
			val arbiter = RecordingAutomaticContainmentArbiter(
				setOf(
					ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
					ActivityRegistrationOwner.ACTIVE_SESSION,
				),
				retryableClearCount = 1,
			)
			val monitor = AutomaticStartTransitionMonitor(
				arbiter = arbiter,
				sourceCallerDemandDispatcher =
					TestPurposeSourceCallerDemandDispatcher(subject),
				clockDomainProvider = BootClockDomainProvider { "boot-1" },
				activityProjectionLane = mockk<ActivityAutomationProjectionLane>(relaxed = true),
			)

			var schedulerCalls = 0
			val attempts = mutableListOf<AutomaticControlRecoveryResult?>()
			val unavailable = AutomaticTrackingOperationalAvailability.Unavailable(
				AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
			)
			val result = runAutomaticControlContainmentRetryLoop(
				isCurrent = { true },
				startImmediateCleanup = {
					async {
						requireNotNull(reconcileUnavailableAutomaticControl(
							availability = unavailable,
							reconcileDisabled = {
								monitor.reconcile(
									enabled = false,
									useTransitionApi = false,
									continuousIntervalSeconds = 10,
									transitions = emptySet(),
								)
							},
						))
					}
				},
				establishRetryOwnership = {
					schedulerCalls++
					if (schedulerCalls == 1) {
						throw IllegalStateException("scheduler unavailable")
					}
				},
				waitBeforeRetry = {},
				onAttemptCompleted = { attempt -> attempts += attempt.cleanupResult },
				initialRetryDelayMillis = 0L,
				maxRetryDelayMillis = 0L,
			)

			result shouldBe AutomaticControlContainmentLoopResult.Handled(
				attempts = 2,
				lastAttempt = AutomaticControlContainmentAttemptResult(
					cleanupResult =
						AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED,
					retryOwnershipEstablished = true,
					cleanupFailure = null,
					retryOwnershipFailure = null,
				),
			)
			attempts shouldBe listOf(
				AutomaticControlRecoveryResult.RETRYABLE,
				AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED,
			)
			database.sourceBrokerDao()
				.currentDemands("app:automatic-start:activity")
				.shouldBeEmpty()
			database.sourceBrokerDao()
				.demandHistory("app:automatic-start:activity")
				.single()
				.status shouldBe SourceDemandEntity.STATUS_RETIRED
			database.sourceBrokerDao()
				.currentDemands("session:manual-activity") shouldBe listOf(manual)
			database.sourceBrokerDao().latestAuthorization(
				SourceKind.ACTIVITY.stableCode,
				1L,
			).toAuthorizationSnapshotOrNull()
				?.authorizedMembers
				?.map { member -> member.demandId } shouldBe listOf(manual.demandId)
			arbiter.snapshot().owners shouldBe setOf(ActivityRegistrationOwner.ACTIVE_SESSION)
			(policyRepository.currentState() as SourcePolicyAuthorityState.Active)
				.snapshot[TrackingSourceComponent.ACTIVITY]
				.controlConsentEpoch shouldBe activityPolicy.controlConsentEpoch
			schedulerCalls shouldBe 2
			arbiter.snapshot().owners shouldBe setOf(ActivityRegistrationOwner.ACTIVE_SESSION)
		}

	@Test
	fun `automatic Steps capture rollout admits Activity control without admitting Activity capture`() = runTest {
		val rollout = TrackingRolloutState.eventCanonical(
			sources = setOf(SourceKind.STEPS),
			revision = 7L,
			controlSources = setOf(SourceKind.ACTIVITY),
			captureModes = mapOf(
				SourceKind.STEPS to ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V2.captureModes,
			),
		)
		rolloutStore.save(rollout, updatedAtMs = 7L)
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val initial = policy.bootstrapFromLegacy(
			TrackingParamsState(
				locationEnabled = false,
				activityEnabled = false,
				stepsEnabled = true,
				barometerEnabled = false,
				sourceCollectionSettings = SourceCollectionSettings(
					location = SourceCollectionFrequency.OFF,
					activity = SourceCollectionFrequency.OFF,
					steps = SourceCollectionFrequency.BALANCED,
					pressure = SourceCollectionFrequency.OFF,
					wifi = SourceCollectionFrequency.OFF,
					cell = SourceCollectionFrequency.OFF,
				),
				legacySettingsMigrationCompleted = true,
			),
		)
		policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_STEPS_ACTIVITY_CONTROL_GRANT",
		)

		val demand = requireNotNull(subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:activity",
			source = SourceKind.ACTIVITY,
			enabled = true,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		))

		rollout.isAcquisitionReachable(SourceKind.STEPS) shouldBe true
		rollout.isControlAcquisitionReachable(SourceKind.ACTIVITY) shouldBe true
		rollout.isAcquisitionReachable(SourceKind.ACTIVITY) shouldBe false
		demand.purpose shouldBe SourceBrokerPurpose.CONTROL_AUTOSTART
		demand.persistenceEligible shouldBe false
		database.sourceBrokerDao().currentDemands("app:automation:activity") shouldBe listOf(demand)
	}

	@Test
	fun `manual-only Steps rollout cannot admit Activity automatic control`() = runTest {
		resetBroker(setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE))
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val initial = policy.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_MANUAL_ONLY_ACTIVITY_CONTROL",
		)

		subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:activity",
			source = SourceKind.ACTIVITY,
			enabled = true,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		) shouldBe null
		database.sourceBrokerDao().currentDemands("app:automation:activity") shouldBe emptyList()
		database.activityAutomationEpochDao().current()?.automaticControlEnabled shouldBe false
	}

	@Test
	fun `contained rollout retires stale automatic control and authorizes no replacement`() = runTest {
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val initial = policy.bootstrapFromLegacy(
			TrackingParamsState(legacySettingsMigrationCompleted = true),
		)
		policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_ACTIVITY_CONTROL_GRANT",
		)
		requireNotNull(subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:activity",
			source = SourceKind.ACTIVITY,
			enabled = true,
			bootId = "boot-1",
			elapsedRealtimeNanos = 50L,
			wallTimeMs = 50L,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		))
		rolloutStore.save(
			TrackingRolloutState.contained(revision = 7L),
			updatedAtMs = 7L,
		)

		subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:activity",
			source = SourceKind.ACTIVITY,
			enabled = true,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		) shouldBe null

		database.sourceBrokerDao().currentDemands("app:automation:activity") shouldBe emptyList()
	}

	// Keep automatic disable/reenable, both registrations and consent revocation in one authority history.
	@Suppress("LongMethod")
	@Test
	fun `automatic control reenable creates a fresh auditable demand and revoke fails closed`() = runTest {
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val initial = policy.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
		val granted = policy.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_CONTROL_GRANT",
		)

		val first = requireNotNull(subject.replaceAutomaticControlDemand(
			consumerId = "app:automation:activity",
			source = SourceKind.ACTIVITY,
			enabled = true,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
		))
		subject.authorizationDemands(SourceKind.ACTIVITY) shouldBe listOf(first)
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "activity-provider-1",
				ownerScope = "source-broker:${SourceKind.ACTIVITY.stableCode}",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "test-process",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "activity-transitions-config",
				collectedDataEpoch = 3L,
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
				listOf(first),
				"boot-1",
				100L,
				100L,
			),
		)
		subject.replaceAutomaticControlDemand(
			"app:automation:activity",
			SourceKind.ACTIVITY,
			false,
			"boot-1",
			200L,
			200L,
			30_000L,
			5_000L,
		)
		val second = requireNotNull(subject.replaceAutomaticControlDemand(
			"app:automation:activity",
			SourceKind.ACTIVITY,
			true,
			"boot-1",
			300L,
			300L,
			30_000L,
			5_000L,
		))
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				registrationGeneration = 2L,
				sourceInstanceId = "activity-provider-1",
				ownerScope = "source-broker:${SourceKind.ACTIVITY.stableCode}",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "test-process",
				clockDomainId = "boot-1",
				physicalConfigurationFingerprint = "activity-transitions-config-v2",
				collectedDataEpoch = 3L,
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
				SourceKind.ACTIVITY.stableCode,
				2L,
				4L,
				listOf(second),
				"boot-1",
				350L,
				350L,
			),
		)

		(first.demandId == second.demandId) shouldBe false
		database.sourceBrokerDao().demandHistory("app:automation:activity").map { it.status } shouldBe
			listOf(SourceDemandEntity.STATUS_RETIRED, SourceDemandEntity.STATUS_ACTIVE)

		// The policy boundary shares the same monotonic clock as provider demand changes.
		elapsed = 400L
		policy.setNonCaptureConsent(
			expectedPolicyRevision = granted.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_CONTROL_REVOKE",
		)
		subject.replaceAutomaticControlDemand(
			"app:automation:activity",
			SourceKind.ACTIVITY,
			true,
			"boot-1",
			400L,
			400L,
			30_000L,
			5_000L,
		) shouldBe null
		database.sourceBrokerDao().currentDemands("app:automation:activity") shouldBe emptyList()
		subject.authorizationDemands(SourceKind.ACTIVITY) shouldBe emptyList()
		database.sourceBrokerDao().authorizationAt(
			SourceKind.ACTIVITY.stableCode,
			1L,
			"boot-1",
			399L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe false
		database.sourceBrokerDao().authorizationAt(
			SourceKind.ACTIVITY.stableCode,
			1L,
			"boot-1",
			400L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
		database.sourceBrokerDao().authorizationAt(
			SourceKind.ACTIVITY.stableCode,
			2L,
			"boot-1",
			400L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
	}
}

private class RecordingAutomaticContainmentArbiter(
	initialOwners: Set<ActivityRegistrationOwner>,
	private var retryableClearCount: Int = 0,
) : ActivityRegistrationArbiter {
	private val owners = initialOwners.toMutableSet()

	override suspend fun setDemand(
		owner: ActivityRegistrationOwner,
		demand: ActivityRegistrationDemand,
	): ActivityRegistrationResult {
		if (demand.enabled) {
			owners += owner
		} else {
			owners -= owner
		}
		return result()
	}

	override suspend fun clearDemand(
		owner: ActivityRegistrationOwner,
	): ActivityRegistrationResult {
		owners -= owner
		return if (retryableClearCount > 0) {
			retryableClearCount--
			ActivityRegistrationResult(
				status = ActivityRegistrationStatus.DEGRADED,
				snapshot = snapshotValue(),
				failureCode = ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED,
				retryable = true,
			)
		} else {
			result()
		}
	}

	override suspend fun reconcileDurableDemands(): ActivityRegistrationResult = result()

	override suspend fun closeForCollectedDataDeletion(): ActivityRegistrationResult = result()

	override suspend fun resumeAfterCollectedDataDeletion(): ActivityRegistrationResult = result()

	override suspend fun retryPendingProviderCleanup(): ActivityProviderCleanupResult =
		ActivityProviderCleanupResult.COMPLETE

	override fun snapshot(): ActivityRegistrationSnapshot = snapshotValue()

	private fun result() = ActivityRegistrationResult(
		status = ActivityRegistrationStatus.APPLIED,
		snapshot = snapshotValue(),
	)

	private fun snapshotValue() = ActivityRegistrationSnapshot(
		active = owners.isNotEmpty(),
		identity = ActivityRegistrationIdentity(
			sourceInstanceId = "activity-provider-1",
			registrationGeneration = 1L,
			collectedDataEpoch = 3L,
			clockDomainId = "boot-1",
			physicalConfigurationFingerprint = "activity-transitions",
		),
		owners = owners.toSet(),
		continuousRecognitionIntervalSeconds = 5.takeIf {
			ActivityRegistrationOwner.ACTIVE_SESSION in owners
		},
		transitions = emptySet<ActivityTransitionData>(),
	)
}
