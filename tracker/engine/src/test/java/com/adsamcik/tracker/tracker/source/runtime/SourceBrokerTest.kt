package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
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
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEffectChecksum
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryResult
import com.adsamcik.tracker.tracker.api.AutomaticControlContainmentAttemptResult
import com.adsamcik.tracker.tracker.api.AutomaticControlContainmentLoopResult
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AutomaticTrackingUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.reconcileUnavailableAutomaticControl
import com.adsamcik.tracker.tracker.api.runAutomaticControlContainmentRetryLoop
import com.adsamcik.tracker.tracker.source.model.ActivityAcquisitionCapability
import com.adsamcik.tracker.tracker.source.model.ActivityAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjectionLane
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.mockk
import io.mockk.coEvery
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
	private lateinit var rolloutStore: RoomTrackingRolloutStateStore
	private lateinit var retentionReader: TestLiveAmbientRetentionAuthorityReader
	private var elapsed = 10L

	@Before
	fun setUp() {
		resetBroker(setOf(CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `revocation fences blocked demand before foreground activation in one transaction`() =
		runTest {
			val demand = SourceDemandEntity(
				demandId = "blocked-before-revocation",
				consumerId = "session:logical",
				sourceKind = SourceKind.LOCATION.stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = "logical",
				serviceRunId = "run",
				manifestRevision = 1L,
				lifecycleLeaseGeneration = 1L,
				sourcePolicyRevision = 1L,
				consentEpoch = 1L,
				persistenceEligible = true,
				qosCode = 0,
				maximumAgeMs = 0L,
				desiredLatencyMs = 0L,
				requestedBootId = "boot-1",
				requestedElapsedRealtimeNanos = 10L,
				requestedAtMs = 10L,
				status = SourceDemandEntity.STATUS_BLOCKED,
				retireBootId = null,
				retireElapsedRealtimeNanos = null,
				retiredAtMs = null,
				sourceCallerAuthorityReference = "caller-ref",
			)
			database.sourceBrokerDao().insertDemands(listOf(demand))

			val activated = database.withTransaction {
				database.sourceBrokerDao().markSourcePurposesRetiring(
					SourceKind.LOCATION.stableCode,
					listOf(SourceBrokerPurpose.SESSION_CAPTURE),
					"boot-1",
					20L,
					20L,
				)
				subject.activatePreparedSessionDemandsInTransaction(
					logicalTrackingId = "logical",
					serviceRunId = "run",
					manifestRevision = 1L,
					leaseGeneration = 1L,
					sourceCallerAuthorityReference = "caller-ref",
					bootId = "boot-1",
					elapsedRealtimeNanos = 21L,
					wallTimeMs = 21L,
					currentAuthority = SourceCallerCurrentAuthorityPredicate { _, _, _ -> true },
				)
			}

			activated shouldBe false
			database.sourceBrokerDao().demandsByIds(listOf(demand.demandId))
				.single().status shouldBe SourceDemandEntity.STATUS_RETIRED
		}

	@Test
	fun `prepared activation leaves blocked demand closed when caller authority is stale`() =
		runTest {
			val demand = SourceDemandEntity(
				demandId = "blocked-stale-authority",
				consumerId = "session:logical",
				sourceKind = SourceKind.LOCATION.stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = "logical",
				serviceRunId = "run",
				manifestRevision = 1L,
				lifecycleLeaseGeneration = 1L,
				sourcePolicyRevision = 1L,
				consentEpoch = 1L,
				persistenceEligible = true,
				qosCode = 0,
				maximumAgeMs = 0L,
				desiredLatencyMs = 0L,
				requestedBootId = "boot-1",
				requestedElapsedRealtimeNanos = 10L,
				requestedAtMs = 10L,
				status = SourceDemandEntity.STATUS_BLOCKED,
				retireBootId = null,
				retireElapsedRealtimeNanos = null,
				retiredAtMs = null,
				sourceCallerAuthorityReference = "caller-ref",
			)
			database.sourceBrokerDao().insertDemands(listOf(demand))

			subject.activatePreparedSessionDemandsInTransaction(
				logicalTrackingId = "logical",
				serviceRunId = "run",
				manifestRevision = 1L,
				leaseGeneration = 1L,
				sourceCallerAuthorityReference = "caller-ref",
				bootId = "boot-1",
				elapsedRealtimeNanos = 20L,
				wallTimeMs = 20L,
				currentAuthority = SourceCallerCurrentAuthorityPredicate { _, _, _ -> false },
			) shouldBe false

			database.sourceBrokerDao().demandsByIds(listOf(demand.demandId))
				.single().status shouldBe SourceDemandEntity.STATUS_BLOCKED
		}

	@Test
	fun `malformed caller authority cannot roll back blocked automatic demand retirement`() = runTest {
		val reference = "malformed-automatic"
		database.sourceCallerAuthorityDao().insert(
			SourceCallerAcceptedAuthorityEffectChecksum.seal(
				listOf(
					SourceCallerAcceptedAuthorityEntity(
						reference = reference,
						formatVersion = SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION,
						origin = "PURPOSE_OWNER",
						acceptedPurpose = "CONTROL",
						sourceKind = SourceKind.ACTIVITY.stableCode,
						purpose = "CONTROL",
						policyRevision = 1L,
						consentEpoch = 1L,
						collectedDataEpoch = 3L,
						rolloutRevision = 1L,
						executionRevision = 1L,
						ownerCasToken = "",
						logicalTrackingId = null,
						manifestRevision = null,
						status = SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE,
						createdAtMs = 1L,
						retiredAtMs = null,
						retireReason = null,
						effectChecksum = "pending",
					),
				),
			),
		)
		val demand = purposeDemand(
			id = "blocked-automatic",
			consumerId = "app:automatic-start:activity",
			source = SourceKind.ACTIVITY,
			purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
			status = SourceDemandEntity.STATUS_BLOCKED,
			reference = reference,
		)
		database.sourceBrokerDao().insertDemands(listOf(demand))

		subject.retirePurposeDemand(
			demand.consumerId,
			demand.sourceKind,
			demand.purpose,
			"boot-1",
			20L,
			20L,
		) shouldBe true

		database.sourceBrokerDao().demandsByIds(listOf(demand.demandId))
			.single().status shouldBe SourceDemandEntity.STATUS_RETIRED
		database.sourceCallerAuthorityDao().rows(reference).shouldBeEmpty()
	}

	@Test
	fun `caller authority storage failure cannot roll back exact demand fencing`() = runTest {
		val unavailableRepository = mockk<SourceCallerAcceptedAuthorityRepository>()
		coEvery {
			unavailableRepository.retireForTeardown(any(), any(), any())
		} throws IllegalStateException("caller authority unavailable")
		val broker = SourceBroker(
			database,
			rolloutStore,
			RejectingAmbientRadioMutationLeaseGuard,
			unavailableRepository,
			retentionReader,
		)
		val demand = purposeDemand(
			id = "active-ambient",
			consumerId = "app:ambient:steps",
			source = SourceKind.STEPS,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			status = SourceDemandEntity.STATUS_ACTIVE,
			reference = "unavailable-authority",
		)
		database.sourceBrokerDao().insertDemands(listOf(demand))

		broker.retirePurposeDemand(
			demand.consumerId,
			demand.sourceKind,
			demand.purpose,
			"boot-1",
			30L,
			30L,
		) shouldBe false

		database.sourceBrokerDao().demandsByIds(listOf(demand.demandId))
			.single().status shouldBe SourceDemandEntity.STATUS_RETIRED
	}

	@Test
	fun `superseded session authority retirement propagates caller cleanup failure`() = runTest {
		val reference = SourceCallerReplayReference("superseded-retirement-failure")
		val unavailableRepository = mockk<SourceCallerAcceptedAuthorityRepository>()
		coEvery {
			unavailableRepository.load(reference)
		} returns StoredSourceCallerAuthorityLoadResult.Available(mockk())
		coEvery {
			unavailableRepository.retireForTeardown(reference, any(), any())
		} returns false
		val broker = SourceBroker(
			database,
			rolloutStore,
			RejectingAmbientRadioMutationLeaseGuard,
			unavailableRepository,
			retentionReader,
		)
		database.sourceBrokerDao().insertDemands(
			listOf(
				purposeDemand(
					id = "superseded-session-demand",
					consumerId = broker.sessionConsumerId("logical"),
					source = SourceKind.STEPS,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					status = SourceDemandEntity.STATUS_ACTIVE,
					reference = reference.value,
				),
			),
		)

		database.withTransaction {
			broker.retireSupersededSessionAuthoritiesInTransaction(
				logicalTrackingId = "logical",
				currentReference = SourceCallerReplayReference("current-authority"),
				expectedSupersededReference = reference,
				wallTimeMs = 200L,
			)
		} shouldBe SourceCallerAuthorityRetirementOutcome.Retryable(
			SourceCallerAuthorityRetirementRetryReason.COMPARE_AND_SET_FAILED,
		)
	}

	@Test
	fun `superseded retirement reports terminal authority categories and idempotent completion`() =
		runTest {
			val current = SourceCallerReplayReference("current-authority")
			val expected = SourceCallerReplayReference("expected-predecessor")
			val missingRepository = mockk<SourceCallerAcceptedAuthorityRepository>()
			coEvery { missingRepository.load(expected) } returns
				StoredSourceCallerAuthorityLoadResult.Missing
			val missingBroker = SourceBroker(
				database,
				rolloutStore,
				RejectingAmbientRadioMutationLeaseGuard,
				missingRepository,
				retentionReader,
			)

			database.withTransaction {
				missingBroker.retireSupersededSessionAuthoritiesInTransaction(
					"logical-missing",
					current,
					expected,
					200L,
				)
			} shouldBe SourceCallerAuthorityRetirementOutcome.TerminalMissing

			val linked = purposeDemand(
				id = "expected-predecessor-demand",
				consumerId = missingBroker.sessionConsumerId("logical-linked"),
				source = SourceKind.STEPS,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				status = SourceDemandEntity.STATUS_RETIRED,
				reference = expected.value,
			)
			database.sourceBrokerDao().insertDemands(listOf(linked))
			coEvery { missingRepository.load(expected) } returnsMany listOf(
				StoredSourceCallerAuthorityLoadResult.Retired,
				StoredSourceCallerAuthorityLoadResult.Corrupt,
			)
			database.withTransaction {
				missingBroker.retireSupersededSessionAuthoritiesInTransaction(
					"logical-linked",
					current,
					expected,
					200L,
				)
			} shouldBe SourceCallerAuthorityRetirementOutcome.Completed
			database.withTransaction {
				missingBroker.retireSupersededSessionAuthoritiesInTransaction(
					"logical-linked",
					current,
					expected,
					200L,
				)
			} shouldBe SourceCallerAuthorityRetirementOutcome.TerminalCorrupt
		}

	@Test
	fun `superseded retirement distinguishes storage unavailability from ambiguous active history`() =
		runTest {
			val current = SourceCallerReplayReference("current-authority")
			val expected = SourceCallerReplayReference("expected-predecessor")
			val unexpected = SourceCallerReplayReference("unexpected-predecessor")
			val repository = mockk<SourceCallerAcceptedAuthorityRepository>()
			val broker = SourceBroker(
				database,
				rolloutStore,
				RejectingAmbientRadioMutationLeaseGuard,
				repository,
				retentionReader,
			)
			database.sourceBrokerDao().insertDemands(
				listOf(
					purposeDemand(
						id = "expected-history",
						consumerId = broker.sessionConsumerId("logical-storage"),
						source = SourceKind.STEPS,
						purpose = SourceBrokerPurpose.SESSION_CAPTURE,
						status = SourceDemandEntity.STATUS_RETIRED,
						reference = expected.value,
					),
				),
			)
			coEvery { repository.load(expected) } throws IOException("storage unavailable")

			database.withTransaction {
				broker.retireSupersededSessionAuthoritiesInTransaction(
					"logical-storage",
					current,
					expected,
					200L,
				)
			} shouldBe SourceCallerAuthorityRetirementOutcome.Retryable(
				SourceCallerAuthorityRetirementRetryReason.STORAGE_UNAVAILABLE,
			)

			database.sourceBrokerDao().insertDemands(
				listOf(
					purposeDemand(
						id = "expected-ambiguous",
						consumerId = broker.sessionConsumerId("logical-ambiguous"),
						source = SourceKind.STEPS,
						purpose = SourceBrokerPurpose.SESSION_CAPTURE,
						status = SourceDemandEntity.STATUS_RETIRED,
						reference = expected.value,
					),
					purposeDemand(
						id = "unexpected-ambiguous",
						consumerId = broker.sessionConsumerId("logical-ambiguous"),
						source = SourceKind.LOCATION,
						purpose = SourceBrokerPurpose.SESSION_CAPTURE,
						status = SourceDemandEntity.STATUS_RETIRED,
						reference = unexpected.value,
					),
				),
			)
			coEvery { repository.load(unexpected) } returns
				StoredSourceCallerAuthorityLoadResult.Available(mockk())

			database.withTransaction {
				broker.retireSupersededSessionAuthoritiesInTransaction(
					"logical-ambiguous",
					current,
					expected,
					200L,
				)
			} shouldBe SourceCallerAuthorityRetirementOutcome.TerminalAmbiguous
		}

	@Test
	fun `session retirement fences active and blocked rows despite corrupt caller metadata`() = runTest {
		val reference = "malformed-session"
		val malformed = SourceCallerAcceptedAuthorityEntity(
			reference = reference,
			formatVersion = SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION + 1,
			origin = "MANUAL",
			acceptedPurpose = "SESSION_CAPTURE",
			sourceKind = SourceKind.LOCATION.stableCode,
			purpose = "SESSION_CAPTURE",
			policyRevision = 1L,
			consentEpoch = 1L,
			collectedDataEpoch = 3L,
			rolloutRevision = 1L,
			executionRevision = 1L,
			ownerCasToken = "owner",
			logicalTrackingId = "logical-corrupt",
			manifestRevision = 1L,
			status = SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE,
			createdAtMs = 1L,
			retiredAtMs = null,
			retireReason = null,
			effectChecksum = "pending",
		)
		database.sourceCallerAuthorityDao().insert(
			SourceCallerAcceptedAuthorityEffectChecksum.seal(listOf(malformed)),
		)
		val active = purposeDemand(
			"session-active",
			"session:logical-corrupt",
			SourceKind.LOCATION,
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceDemandEntity.STATUS_ACTIVE,
			reference,
		).copy(
			logicalTrackingId = "logical-corrupt",
			serviceRunId = "run-active",
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
		)
		val blocked = active.copy(
			demandId = "session-blocked",
			serviceRunId = "run-blocked",
			manifestRevision = 2L,
			status = SourceDemandEntity.STATUS_BLOCKED,
		)
		database.sourceBrokerDao().insertDemands(listOf(active, blocked))

		subject.retireSessionDemands(
			"logical-corrupt",
			"boot-1",
			40L,
			40L,
		)

		database.sourceBrokerDao().demandsByIds(listOf(active.demandId, blocked.demandId))
			.map(SourceDemandEntity::status)
			.toSet() shouldBe setOf(SourceDemandEntity.STATUS_RETIRED)
		database.sourceCallerAuthorityDao().rows(reference).shouldBeEmpty()
	}

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
					collectedDataEpoch = 1L,
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
				SourceKind.STEPS to setOf(CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE),
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

	@Test
	fun `ambient-only Steps policy creates one sessionless provider-specific demand`() = runTest {
		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val snapshot = policy.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = false,
				ambientStepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		val retention = retentionReader.installCurrent(
			database,
			TrackingSourceComponent.STEPS,
			snapshot.revision,
			requireNotNull(snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch),
			3L,
			"boot-1",
		)

		val result = subject.replaceAmbientStepsDemand(
			consumerId = "app:ambient:steps",
			mechanism = AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			retentionSnapshot = retention,
		)

		val demand = (result as AmbientStepsDemandResult.Active).demand
		snapshot[TrackingSourceComponent.STEPS].enabled shouldBe false
		demand.purpose shouldBe SourceBrokerPurpose.AMBIENT_PRODUCT
		demand.logicalTrackingId shouldBe null
		demand.serviceRunId shouldBe null
		demand.manifestRevision shouldBe null
		demand.lifecycleLeaseGeneration shouldBe null
		demand.persistenceEligible shouldBe true
		demand.qosCode shouldBe 0
		demand.liveAmbientRetentionPolicyId shouldBe "privacy:steps:ambient:v1"
		demand.liveAmbientRetentionApprovalRevision shouldBe 1L
		(demand.toSourceDemandContract().floor as AmbientStepsAcquisitionFloor).mechanism shouldBe
			AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
		database.sourceBrokerDao().currentDemands("app:ambient:steps") shouldBe listOf(demand)
		subject.authorizationDemands(SourceKind.STEPS) shouldBe listOf(demand)
	}

	@Test
	fun `ambient provider replacement at one boundary never overlaps or aliases demand identity`() = runTest {
		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		val snapshot = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}.bootstrapFromLegacy(
			TrackingParamsState(
				ambientStepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		var retention = retentionReader.installCurrent(
			database,
			TrackingSourceComponent.STEPS,
			snapshot.revision,
			requireNotNull(snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch),
			3L,
			"boot-1",
		)
		val healthConnect = (subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
			"boot-1",
			100L,
			100L,
			retentionSnapshot = retention,
		) as AmbientStepsDemandResult.Active).demand
		val localRecording = (subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			"boot-1",
			100L,
			100L,
			retentionSnapshot = retention,
		) as AmbientStepsDemandResult.Active).demand

		(healthConnect.demandId == localRecording.demandId) shouldBe false
		database.sourceBrokerDao().currentDemands("app:ambient:steps") shouldBe listOf(localRecording)
		val history = database.sourceBrokerDao().demandHistory("app:ambient:steps")
		history.size shouldBe 2
		history.single { it.demandId == healthConnect.demandId }.status shouldBe
			SourceDemandEntity.STATUS_RETIRED
		history.single { it.demandId == localRecording.demandId }.status shouldBe
			SourceDemandEntity.STATUS_ACTIVE

		val unchanged = subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			"boot-1",
			150L,
			150L,
			retentionSnapshot = retention,
		) as AmbientStepsDemandResult.Active
		unchanged.demand shouldBe localRecording
		database.sourceBrokerDao().demandHistory("app:ambient:steps").size shouldBe 2

		retentionReader.approvalRevision = 2L
		retention = retentionReader.installCurrent(
			database,
			TrackingSourceComponent.STEPS,
			snapshot.revision,
			requireNotNull(snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch),
			3L,
			"boot-1",
		)
		val rotated = subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			"boot-1",
			200L,
			200L,
			retentionSnapshot = retention,
		) as AmbientStepsDemandResult.Active
		rotated.demand.liveAmbientRetentionApprovalRevision shouldBe 2L
		(rotated.demand.demandId == localRecording.demandId) shouldBe false
		(SourceBrokerAuthorization.fingerprint(listOf(rotated.demand)) ==
			SourceBrokerAuthorization.fingerprint(listOf(localRecording))) shouldBe false
		database.sourceBrokerDao().demandHistory("app:ambient:steps").size shouldBe 3
	}

	@Test
	fun `ambient consent and rollout independently deny demand creation`() = runTest {
		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}.bootstrapFromLegacy(
			TrackingParamsState(
				ambientStepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		rolloutStore.save(TrackingRolloutState.contained(revision = 9L), updatedAtMs = 150L)

		subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			"boot-1",
			200L,
			200L,
		) shouldBe AmbientStepsDemandResult.Inactive(
			AmbientStepsDemandInactiveReason.ROLLOUT_CONTAINED,
		)
		database.sourceBrokerDao().currentDemands("app:ambient:steps") shouldBe emptyList()

		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}.bootstrapFromLegacy(TrackingParamsState(legacySettingsMigrationCompleted = true))
		subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			"boot-1",
			300L,
			300L,
		) shouldBe AmbientStepsDemandResult.Inactive(
			AmbientStepsDemandInactiveReason.CONSENT_REVOKED,
		)
		database.sourceBrokerDao().currentDemands("app:ambient:steps") shouldBe emptyList()
	}

	@Test
	fun `Ambient Steps mutation fails closed when retention hook is stale`() = runTest {
		RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}.bootstrapFromLegacy(
			TrackingParamsState(
				ambientStepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		retentionReader.current = false

		subject.replaceAmbientStepsDemand(
			"app:ambient:steps",
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			"boot-1",
			100L,
			100L,
		) shouldBe AmbientStepsDemandResult.Inactive(
			AmbientStepsDemandInactiveReason.RETENTION_AUTHORITY_UNAVAILABLE,
		)
		database.sourceBrokerDao().currentDemands("app:ambient:steps") shouldBe emptyList()
	}

	@Test
	fun `Ambient Steps mutation rejects a corrupt persisted retention row after snapshot`() = runTest {
		resetBroker(setOf(CaptureReachabilityMode.AMBIENT))
		val policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}.bootstrapFromLegacy(
			TrackingParamsState(
				ambientStepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		val retention = retentionReader.installCurrent(
			database,
			TrackingSourceComponent.STEPS,
			policy.revision,
			requireNotNull(policy[TrackingSourceComponent.STEPS].ambientConsentEpoch),
			3L,
			"boot-1",
		)
		val grant = retention.grants.getValue(SourceKind.STEPS)
		val identity = AmbientReconciliationIdentity(
			source = AmbientTrackingSource.STEPS,
			policyRevision = policy.revision,
			consentEpoch = requireNotNull(
				policy[TrackingSourceComponent.STEPS].ambientConsentEpoch,
			),
			collectedDataEpoch = grant.collectedDataEpoch,
			rolloutRevision = 1L,
			ownerCasToken = "steps-stale-snapshot-owner",
			executionRevision = 1L,
			retainedFromMs = grant.retainedFromMs,
			retentionPolicyId = grant.opaquePolicyId,
			retentionApprovalRevision = grant.approvalRevision,
		)
		val callerIdentity = SourceCallerDemandIdentity(identity.purposeLeaseIdentity, null)
		val callerReference = SourceCallerReplayReference("steps-stale-snapshot-caller")
		val prepared = PreparedSourceCallerAcceptance(
			receipt = SourceCallerAcceptanceReceipt(callerReference, setOf(callerIdentity)),
			authority = StoredSourceCallerAuthority(
				StoredSourceCallerOrigin.PURPOSE_OWNER,
				TrackingPurpose.AMBIENT_PRODUCT,
				setOf(callerIdentity),
			),
			createdAtMs = 95L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_steps_retention_authority SET effect_checksum = 'corrupt' " +
				"WHERE scope = 'LIVE_AMBIENT'",
		)

		subject.replaceAmbientStepsDemand(
			consumerId = "app:ambient:steps",
			mechanism = AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			leaseIdentity = identity,
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 100L,
			sourceCallerAuthorityReference = callerReference.value,
			retentionSnapshot = retention,
			preparedCallerAcceptance = prepared,
		) shouldBe AmbientStepsDemandResult.Inactive(
			AmbientStepsDemandInactiveReason.RETENTION_AUTHORITY_UNAVAILABLE,
		)
		database.sourceBrokerDao().currentDemands("app:ambient:steps").shouldBeEmpty()
		RoomSourceCallerAcceptedAuthorityRepository(database).load(callerReference) shouldBe
			StoredSourceCallerAuthorityLoadResult.Missing
	}

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
	fun `Pressure manifest creates one exact session capture demand`() = runTest {
		val demand = subject.buildSessionDemands(
			logicalTrackingId = "pressure-session",
			serviceRunId = "pressure-run",
			manifestRevision = 4L,
			lifecycleLeaseGeneration = 2L,
			policyRevision = 9L,
			bindings = listOf(
				binding(SourceKind.PRESSURE, SourceBrokerPurpose.SESSION_CAPTURE, 12L, true)
					.copy(logicalTrackingId = "pressure-session"),
			),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 200L,
		).single()

		database.sourceBrokerDao().insertDemands(listOf(demand))
		val stored = database.sourceBrokerDao().demandHistory(
			subject.sessionConsumerId("pressure-session"),
		).single()
		stored.sourceKind shouldBe SourceKind.PRESSURE.stableCode
		stored.purpose shouldBe SourceBrokerPurpose.SESSION_CAPTURE
		stored.persistenceEligible shouldBe true
		stored.logicalTrackingId shouldBe "pressure-session"
		stored.serviceRunId shouldBe "pressure-run"
		stored.toSourceDemandContract() shouldBe SourceDemandContractFactory.forQos(
			SourceKind.PRESSURE,
			stored.qosCode,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		)
		database.sourceBrokerDao().currentDemands(
			subject.sessionConsumerId("pressure-session"),
		).map { it.sourceKind to it.purpose } shouldBe listOf(
			SourceKind.PRESSURE.stableCode to SourceBrokerPurpose.SESSION_CAPTURE,
		)
	}

	@Test
	fun `Pressure control manifest is rejected before a demand can exist`() = runTest {
		shouldThrow<IllegalArgumentException> {
			subject.buildSessionDemands(
				logicalTrackingId = "pressure-session",
				serviceRunId = "pressure-run",
				manifestRevision = 4L,
				lifecycleLeaseGeneration = 2L,
				policyRevision = 9L,
				bindings = listOf(
					binding(SourceKind.PRESSURE, "CONTROL", 12L, false)
						.copy(logicalTrackingId = "pressure-session"),
				),
				bootId = "boot-1",
				elapsedRealtimeNanos = 100L,
				wallTimeMs = 200L,
			)
		}.message shouldBe "Pressure supports direct session capture demand only"
		database.sourceBrokerDao().demandHistory(
			subject.sessionConsumerId("pressure-session"),
		).shouldBeEmpty()
	}

	@Test
	fun `Activity capture and continuation share classifications after persistence`() = runTest {
		val demands = subject.buildSessionDemands(
			logicalTrackingId = "session-1",
			serviceRunId = "run-1",
			manifestRevision = 4L,
			lifecycleLeaseGeneration = 2L,
			policyRevision = 9L,
			bindings = listOf(
				binding(SourceKind.ACTIVITY, SourceBrokerPurpose.SESSION_CAPTURE, 15L, true)
					.copy(qosCode = 3),
				binding(SourceKind.ACTIVITY, "CONTROL", 15L, false).copy(qosCode = 3),
			),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 200L,
		)

		database.sourceBrokerDao().insertDemands(demands)
		val restored = database.sourceBrokerDao().demandHistory(subject.sessionConsumerId("session-1"))
		val capture = restored.single { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }
			.toSourceDemandContract().floor as ActivityAcquisitionFloor
		val control = restored.single { it.purpose == SourceBrokerPurpose.CONTROL_CONTINUATION }
			.toSourceDemandContract().floor as ActivityAcquisitionFloor
		capture.requiredCapabilities shouldBe setOf(ActivityAcquisitionCapability.CLASSIFICATIONS)
		control.requiredCapabilities shouldBe setOf(ActivityAcquisitionCapability.CLASSIFICATIONS)
		capture.union(control).requiredCapabilities shouldBe
			setOf(ActivityAcquisitionCapability.CLASSIFICATIONS)
	}

	@Test
	fun `authorization fingerprint includes floor and adaptive permission`() {
		val demand = subject.buildSessionDemands(
			logicalTrackingId = "session-1",
			serviceRunId = "run-1",
			manifestRevision = 4L,
			lifecycleLeaseGeneration = 2L,
			policyRevision = 9L,
			bindings = listOf(binding(SourceKind.LOCATION, SourceBrokerPurpose.SESSION_CAPTURE, 12L, true)),
			bootId = "boot-1",
			elapsedRealtimeNanos = 100L,
			wallTimeMs = 200L,
		).single()
		val original = SourceBrokerAuthorization.fingerprint(listOf(demand))
		val changedSpec = SourceDemandContractFactory.forQos(
			SourceKind.LOCATION,
			3,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		).encodeFloor()
		val changedFloor = SourceBrokerAuthorization.fingerprint(listOf(demand.copy(
			minimumAcquisitionSpec = changedSpec,
		)))
		val changedAdaptive = SourceBrokerAuthorization.fingerprint(listOf(demand.copy(
			adaptiveReductionAllowed = !demand.adaptiveReductionAllowed,
		)))
		val changedRequestedLatency = SourceBrokerAuthorization.fingerprint(listOf(demand.copy(
			requestedDeliveryLatencyMs = 123L,
		)))

		(original == changedFloor) shouldBe false
		(original == changedAdaptive) shouldBe false
		(original == changedRequestedLatency) shouldBe false
	}

	@Test
	fun `persisted direct demands round trip the one live contract for every source qos and purpose`() = runTest {
		val cases = buildList {
			SourceKind.entries.forEach { source ->
				(1..3).forEach { qos ->
					add(Triple(source, qos, SourceBrokerPurpose.SESSION_CAPTURE))
				}
			}
			listOf(SourceKind.ACTIVITY, SourceKind.STEPS).forEach { source ->
				(0..3).forEach { qos ->
					add(Triple(source, qos, "CONTROL"))
				}
			}
		}
		cases.forEachIndexed { index, (source, qos, manifestPurpose) ->
			val logicalTrackingId = "session-$index"
			val demand = subject.buildSessionDemands(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = "run-$index",
				manifestRevision = 4L,
				lifecycleLeaseGeneration = 2L,
				policyRevision = 9L,
				bindings = listOf(binding(source, manifestPurpose, 12L, true).copy(qosCode = qos)),
				bootId = "boot-1",
				elapsedRealtimeNanos = 100L + index,
				wallTimeMs = 200L + index,
			).single()
			database.sourceBrokerDao().insertDemands(listOf(demand))
			val restored = database.sourceBrokerDao()
				.demandHistory(subject.sessionConsumerId(logicalTrackingId)).single()
			val purpose = if (manifestPurpose == SourceBrokerPurpose.SESSION_CAPTURE) {
				DirectSourceDemandPurpose.SESSION_CAPTURE
			} else {
				DirectSourceDemandPurpose.CONTROL_CONTINUATION
			}
			restored.toSourceDemandContract() shouldBe
				SourceDemandContractFactory.forQos(source, qos, purpose)
		}
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
		subject.authorizationDemands(SourceKind.STEPS) shouldBe listOf(first)
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				registrationGeneration = 1L,
				sourceInstanceId = "steps-provider-1",
				ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "test-process",
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
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "test-process",
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

		// The policy boundary shares the same monotonic clock as provider demand changes.
		elapsed = 400L
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
		subject.authorizationDemands(SourceKind.STEPS) shouldBe emptyList()
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
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
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
	): SessionManifestSourceEntity {
		val writer = when {
			source == SourceKind.STEPS && isCaptured(purpose, persistenceEligible) -> STEPS_TEST_WRITER
			source == SourceKind.PRESSURE && isCaptured(purpose, persistenceEligible) -> PRESSURE_TEST_WRITER
			else -> null
		}
		return SessionManifestSourceEntity(
			logicalTrackingId = "session-1",
			manifestRevision = 4L,
			sourceKind = source.stableCode,
			purpose = purpose,
			consentEpoch = consentEpoch,
			persistenceEligible = persistenceEligible,
			qosCode = 2,
			outputDestination = writer?.destination,
			writerOwner = writer?.owner,
			writerOwnerGeneration = writer?.ownerGeneration,
			writerProjectionId = writer?.projectionId,
			writerProjectionVersion = writer?.projectionVersion,
			writerBindingGeneration = writer?.bindingGeneration,
		)
	}

	private fun purposeDemand(
		id: String,
		consumerId: String,
		source: SourceKind,
		purpose: String,
		status: String,
		reference: String?,
	) = SourceDemandEntity(
		demandId = id,
		consumerId = consumerId,
		sourceKind = source.stableCode,
		purpose = purpose,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = 1L,
		consentEpoch = 1L,
		persistenceEligible = purpose == SourceBrokerPurpose.AMBIENT_PRODUCT,
		qosCode = 0,
		maximumAgeMs = 1L,
		desiredLatencyMs = 1L,
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 1L,
		requestedAtMs = 1L,
		status = status,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
		sourceCallerAuthorityReference = reference,
	)

	private fun resetBroker(captureModes: Set<CaptureReachabilityMode>) {
		if (::database.isInitialized) database.close()
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		rolloutStore = runBlocking {
			database.sourceEvidenceStateDao().ensure(
				SourceEvidenceState(collectedDataEpoch = 3L, updatedAtMs = 1L),
			)
			activateAllBrokerTestProductLanes(database, captureModes)
		}
		retentionReader = TestLiveAmbientRetentionAuthorityReader()
		subject = SourceBroker(
			database,
			rolloutStore,
			retentionReader,
		)
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
		if (demand.enabled) owners += owner else owners -= owner
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
			collectedDataEpoch = 1L,
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

private fun isCaptured(purpose: String, persistenceEligible: Boolean): Boolean =
	purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible

private data class BrokerTestWriter(
	val destination: String,
	val owner: String,
	val ownerGeneration: Long,
	val projectionId: String? = null,
	val projectionVersion: Int? = null,
	val bindingGeneration: Long? = null,
)

private val STEPS_TEST_WRITER = BrokerTestWriter(
	destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
	owner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
	ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
)

private val PRESSURE_TEST_WRITER = ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS.let { binding ->
	BrokerTestWriter(
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		owner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		projectionId = binding.projectionId,
		projectionVersion = binding.projectionVersion,
		bindingGeneration = binding.bindingGeneration,
	)
}

private suspend fun activateAllBrokerTestProductLanes(
	database: AppDatabase,
	captureModes: Set<CaptureReachabilityMode>,
): RoomTrackingRolloutStateStore {
	database.sourceDestinationOwnerDao().insertIfAbsent(
		SourceDestinationOwnerEntity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			owner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
			ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			updatedAtMs = 1L,
		),
	)
	val bindings = SourceKind.entries.map { source ->
		if (source == SourceKind.PRESSURE) {
			ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS
		} else {
			ExecutableSourceLaneBinding(
				source = source,
				bindingGeneration = 1L,
				projectionId = "broker-test-${source.name.lowercase()}-product",
				projectionVersion = 1,
				captureModes = captureModes,
			)
		}
	}
	return installCanonicalProductLanesForTest(
		database = database,
		bindings = bindings,
		rolloutRevision = bindings.size.toLong(),
	)
}
