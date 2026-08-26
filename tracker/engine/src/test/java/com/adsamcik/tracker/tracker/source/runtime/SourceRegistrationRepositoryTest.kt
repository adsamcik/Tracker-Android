package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.fenceSourcePurposesInTransaction
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.process.ProcessIncarnationIdProvider
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class SourceRegistrationRepositoryTest {
	private lateinit var database: AppDatabase
	private lateinit var subject: SourceRegistrationRepository
	private lateinit var processIncarnationIdProvider: ProcessIncarnationIdProvider
	private lateinit var rolloutStore: RoomTrackingRolloutStateStore

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		rolloutStore = runBlocking {
			activateAllSourceProductLanes(database)
		}
		processIncarnationIdProvider = ProcessIncarnationIdProvider()
		subject = SourceRegistrationRepository(
			database,
			FakeCollectedDataLifecycleStore(CollectedDataLifecycleSnapshot(3L, null)),
			object : BootClockDomainProvider {
				override fun current(): String = "boot-7"
			},
			processIncarnationIdProvider,
			rolloutStore,
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `registration fails closed without a durable active demand`() = runTest {
		shouldThrow<IllegalArgumentException> {
			subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		}
	}

	@Test
	fun `contained rollout rejects a stale durable demand before reservation`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("control", "app:auto", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false)),
		)
		rolloutStore.save(
			TrackingRolloutState.contained(revision = 7L),
			updatedAtMs = 7L,
		)

		shouldThrow<IllegalArgumentException> {
			subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		}
		database.sourceBrokerDao().maximumRegistrationGeneration(SourceKind.STEPS.stableCode) shouldBe 0L
	}

	@Test
	fun `rollout containment between reserve and accept leaves no active generation`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val reservation = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		rolloutStore.save(
			TrackingRolloutState.contained(revision = 7L),
			updatedAtMs = 7L,
		)

		shouldThrow<IllegalArgumentException> {
			subject.markAccepted(reservation, 120L, 120L)
		}
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			reservation.state.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RESERVED
		database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, reservation.ownerScope) shouldBe null
	}

	@Test
	fun `system rearmable activity cannot enter the process bound repository`() = runTest {
		shouldThrow<IllegalArgumentException> {
			subject.begin(SourceKind.ACTIVITY, 1L, PHYSICAL_CONFIG, 100L, 100L)
		}
	}

	@Test
	fun `new process reconciles stale reservation before replacement`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val priorReservation = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		val restartedRepository = SourceRegistrationRepository(
			database,
			FakeCollectedDataLifecycleStore(CollectedDataLifecycleSnapshot(3L, null)),
			object : BootClockDomainProvider {
				override fun current(): String = "boot-7"
			},
			ProcessIncarnationIdProvider(),
			rolloutStore,
		)
		shouldThrow<IllegalStateException> {
			restartedRepository.begin(
				SourceKind.STEPS,
				2L,
				PHYSICAL_CONFIG,
				120L,
				120L,
			)
		}

		val reconciled = restartedRepository.reconcilePriorProcessRegistrations(
			reconciledAtMs = 130L,
			reconciledElapsedRealtimeNanos = 130L,
		)
		val replacement = restartedRepository.begin(
			SourceKind.STEPS,
			2L,
			PHYSICAL_CONFIG,
			140L,
			140L,
		)
		restartedRepository.markAccepted(replacement, 150L, 150L)

		reconciled.failedReservations shouldBe 1
		reconciled.affectedRegistrations shouldBe 1
		replacement.state.registrationGeneration shouldBe 2L
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			priorReservation.state.registrationGeneration,
		)?.let { prior ->
			prior.status shouldBe ProviderRegistrationGenerationEntity.STATUS_FAILED
			prior.failureCode shouldBe "PRIOR_PROCESS_INCARNATION_ENDED"
			prior.retiredElapsedRealtimeNanos shouldBe 130L
		}
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			replacement.state.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE
	}

	@Test
	fun `registration snapshots the exact merged purpose vector before provider acceptance`() = runTest {
		val demands = listOf(
			demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 4L, true),
			demand("control", "app:automation", SourceBrokerPurpose.CONTROL_AUTOSTART, null, null, false),
		)
		database.sourceBrokerDao().insertDemands(demands)

		val registration = subject.begin(SourceKind.STEPS, 9L, PHYSICAL_CONFIG, 100L, 100L)
		val reserved = requireNotNull(
			database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L),
		)

		reserved.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RESERVED
		reserved.providerResidency shouldBe ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND
		reserved.providerProcessIncarnationId shouldBe processIncarnationIdProvider.current()
		database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, registration.ownerScope) shouldBe null
		reserved.ownerScope shouldBe "source-broker:${SourceKind.STEPS.stableCode}"
		reserved.physicalConfigurationFingerprint shouldBe PHYSICAL_CONFIG
		registration.authorization.purposeEligibilityMask shouldBe
			(SourceBrokerPurpose.MASK_SESSION_CAPTURE or SourceBrokerPurpose.MASK_CONTROL_AUTOSTART)
		registration.authorization.authorizedMembers
			.map { it.demandId } shouldBe listOf("control", "capture")

		subject.markAccepted(registration, acceptedAtMs = 120L, acceptedElapsedRealtimeNanos = 120L)
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
	}

	@Test
	fun `manifest and global plan revisions rotate authorization without physical generation churn`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture-v1", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val first = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(first, 110L, 110L)
		database.sourceBrokerDao().retireConsumer("session:s1", "boot-7", 120L, 120L)
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture-v2", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 2L, true)),
		)

		val second = subject.begin(SourceKind.STEPS, 2L, PHYSICAL_CONFIG, 130L, 130L)
		val unrelatedGlobalRevision = subject.begin(SourceKind.STEPS, 99L, PHYSICAL_CONFIG, 140L, 140L)

		second.state.registrationGeneration shouldBe 1L
		second.requiresProviderAcceptance shouldBe false
		second.authorization.authorizationRevision shouldBe first.authorization.authorizationRevision + 1L
		unrelatedGlobalRevision.state.registrationGeneration shouldBe 1L
		unrelatedGlobalRevision.authorization.authorizationRevision shouldBe second.authorization.authorizationRevision
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		second.authorization.authorizedMembers.single().manifestRevision shouldBe 2L
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 2L) shouldBe null
	}

	@Test
	fun `active authorization refresh never reserves an incompatible replacement`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val first = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(first, 110L, 110L)

		val refreshed = subject.refreshActiveAuthorization(
			SourceKind.STEPS,
			first,
			2L,
			PHYSICAL_CONFIG,
			120L,
			120L,
		)
		val incompatible = subject.refreshActiveAuthorization(
			SourceKind.STEPS,
			first,
			3L,
			"physical-config-v2",
			130L,
			130L,
		)

		refreshed?.state?.registrationGeneration shouldBe 1L
		refreshed?.requiresProviderAcceptance shouldBe false
		incompatible shouldBe null
		database.sourceBrokerDao().maximumRegistrationGeneration(SourceKind.STEPS.stableCode) shouldBe 1L
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 2L) shouldBe null
		database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, first.ownerScope)
			?.appliedRevision shouldBe 2L
	}

	@Test
	fun `physical configuration change alone rotates generation`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val first = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(first, 110L, 110L)

		val second = subject.begin(SourceKind.STEPS, 2L, "physical-config-v2", 200L, 200L)

		second.state.registrationGeneration shouldBe 2L
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 2L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_RESERVED
		database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, second.ownerScope)
			?.registrationGeneration shouldBe 1L
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			1L,
			first.state.sourceInstanceId,
			"boot-7",
			PHYSICAL_CONFIG,
			200L,
		)?.registrationGeneration shouldBe 1L

		val retiring = subject.markAccepted(second, 220L, 220L)

		retiring?.registrationGeneration shouldBe 1L
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_RETIRING
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 2L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, second.ownerScope)
			?.registrationGeneration shouldBe 2L
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			1L,
			first.state.sourceInstanceId,
			"boot-7",
			PHYSICAL_CONFIG,
			219L,
		)?.registrationGeneration shouldBe 1L
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			1L,
			first.state.sourceInstanceId,
			"boot-7",
			PHYSICAL_CONFIG,
			220L,
		) shouldBe null
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			2L,
			second.state.sourceInstanceId,
			"boot-7",
			"physical-config-v2",
			219L,
		) shouldBe null
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			2L,
			second.state.sourceInstanceId,
			"boot-7",
			"physical-config-v2",
			220L,
		)?.registrationGeneration shouldBe 2L
	}

	@Test
	fun `failed replacement preserves accepted generation and pointer`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val first = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(first, 110L, 110L)
		val replacement = subject.begin(SourceKind.STEPS, 2L, "physical-config-v2", 200L, 200L)

		subject.markFailed(replacement, "PROVIDER_REGISTRATION_FAILED", 210L, 210L)

		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 2L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_FAILED
		database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, replacement.ownerScope)
			?.registrationGeneration shouldBe 1L
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			1L,
			first.state.sourceInstanceId,
			"boot-7",
			PHYSICAL_CONFIG,
			250L,
		)?.registrationGeneration shouldBe 1L
	}

	@Test
	fun `unaccepted failure cannot terminalize an accepted provider`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val reservation = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)

		subject.failUnacceptedReservation(
			reservation,
			failureCode = "PROVIDER_REGISTRATION_FAILED",
			failedAtMs = 110L,
			failedElapsedRealtimeNanos = 110L,
		) shouldBe true
		subject.failUnacceptedReservation(
			reservation,
			failureCode = "REPLAYED_FAILURE",
			failedAtMs = 120L,
			failedElapsedRealtimeNanos = 120L,
		) shouldBe false

		val replacement = subject.begin(SourceKind.STEPS, 2L, PHYSICAL_CONFIG, 130L, 130L)
		subject.markAccepted(replacement, 140L, 140L)
		subject.failUnacceptedReservation(
			replacement,
			failureCode = "MUST_NOT_FAIL_ACTIVE",
			failedAtMs = 150L,
			failedElapsedRealtimeNanos = 150L,
		) shouldBe false

		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			reservation.state.registrationGeneration,
		)?.let { failed ->
			failed.status shouldBe ProviderRegistrationGenerationEntity.STATUS_FAILED
			failed.failureCode shouldBe "PROVIDER_REGISTRATION_FAILED"
			failed.retiredElapsedRealtimeNanos shouldBe 110L
		}
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			replacement.state.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE
	}

	@Test
	fun `retirement token is idempotent exact and blocks replacement until completion`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val registration = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(registration, 110L, 110L)

		val firstToken = subject.beginRetirement(
			registration,
			reason = "ORDERLY_STOP",
			retiredAtMs = 200L,
			retiredElapsedRealtimeNanos = 190L,
		)
		val replayedToken = subject.beginRetirement(
			registration,
			reason = "MUST_NOT_MOVE_BOUNDARY",
			retiredAtMs = 300L,
			retiredElapsedRealtimeNanos = 290L,
		)

		replayedToken shouldBe firstToken
		firstToken.reason shouldBe "ORDERLY_STOP"
		firstToken.retiredAtMs shouldBe 200L
		firstToken.retiredElapsedRealtimeNanos shouldBe 190L
		subject.pendingRetirements(SourceKind.STEPS) shouldBe listOf(firstToken)
		shouldThrow<IllegalStateException> {
			subject.begin(SourceKind.STEPS, 2L, "physical-config-v2", 310L, 310L)
		}
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			registration.state.registrationGeneration,
			registration.state.sourceInstanceId,
			"boot-7",
			PHYSICAL_CONFIG,
			189L,
		)?.registrationGeneration shouldBe registration.state.registrationGeneration
		database.sourceBrokerDao().registrationAtObservedTime(
			SourceKind.STEPS.stableCode,
			registration.state.registrationGeneration,
			registration.state.sourceInstanceId,
			"boot-7",
			PHYSICAL_CONFIG,
			190L,
		) shouldBe null

		val otherProcessRepository = SourceRegistrationRepository(
			database,
			FakeCollectedDataLifecycleStore(CollectedDataLifecycleSnapshot(3L, null)),
			object : BootClockDomainProvider {
				override fun current(): String = "boot-7"
			},
			ProcessIncarnationIdProvider(),
			rolloutStore,
		)
		otherProcessRepository.completeRetirement(firstToken) shouldBe false
		subject.completeRetirement(firstToken) shouldBe true
		subject.completeRetirement(firstToken) shouldBe true
		subject.pendingRetirements(SourceKind.STEPS) shouldBe emptyList()

		val replacement = subject.begin(SourceKind.STEPS, 2L, "physical-config-v2", 320L, 320L)
		replacement.state.registrationGeneration shouldBe 2L
	}

	@Test
	fun `reserved provider can enter exact retirement when cleanup remains required`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val registration = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)

		val token = subject.beginRetirement(
			registration,
			reason = "UNCONFIRMED_PROVIDER_CLEANUP",
			retiredAtMs = 120L,
			retiredElapsedRealtimeNanos = 120L,
		)

		token.registrationGeneration shouldBe registration.state.registrationGeneration
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			registration.state.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RETIRING
		subject.completeRetirement(token) shouldBe true
	}

	@Test
	fun `sensor checkpoint merge is monotonic and rejects a superseded registration`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val registration = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(registration, 110L, 110L)
		val terminal = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.QUIESCED,
			metrics = RuntimeAdmissionSnapshot(
				lastDurablyAdmittedSequence = 2L,
				lastAdmissionOrdinal = 20L,
				failedAdmissionCount = 1L,
				unresolvedSequenceStart = 4L,
				unresolvedSequenceEndInclusive = 4L,
				gapClassifications = setOf(RuntimeGapClassification.ADMISSION_FAILED),
			),
			componentStateVersion = 7,
			componentPayload = byteArrayOf(1),
			causalOrderElapsedRealtimeNanos = 200L,
		)
		val delayedActive = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
			metrics = RuntimeAdmissionSnapshot(
				lastDurablyAdmittedSequence = 3L,
				lastAdmissionOrdinal = 30L,
				failedAdmissionCount = 2L,
				unresolvedSequenceStart = 5L,
				unresolvedSequenceEndInclusive = 5L,
				gapClassifications = setOf(RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW),
			),
			componentStateVersion = 7,
			componentPayload = byteArrayOf(2),
			causalOrderElapsedRealtimeNanos = 150L,
		)

		subject.saveSensorRuntimeCheckpoint(registration, 2L, terminal, 200L)
		subject.saveSensorRuntimeCheckpoint(registration, 3L, delayedActive, 150L)

		val merged = decodeSensorRuntimeCheckpoint(
			subject.loadRuntimeState(registration),
			legacyComponentStateVersion = 7,
		)
		requireNotNull(merged).let { checkpoint ->
			checkpoint.lifecycle shouldBe RuntimeCheckpointLifecycle.QUIESCED
			checkpoint.componentPayload.toList() shouldBe listOf(1.toByte())
			checkpoint.metrics.lastDurablyAdmittedSequence shouldBe 3L
			checkpoint.metrics.lastAdmissionOrdinal shouldBe 30L
			checkpoint.metrics.failedAdmissionCount shouldBe 2L
			checkpoint.metrics.unresolvedSequenceStart shouldBe 4L
			checkpoint.metrics.unresolvedSequenceEndInclusive shouldBe 5L
			checkpoint.metrics.gapClassifications shouldBe setOf(
				RuntimeGapClassification.ADMISSION_FAILED,
				RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW,
			)
		}

		val replacement = subject.begin(SourceKind.STEPS, 2L, "physical-config-v2", 300L, 300L)
		subject.markAccepted(replacement, 310L, 310L)
		shouldThrow<IllegalStateException> {
			subject.saveSensorRuntimeCheckpoint(registration, 4L, terminal, 320L)
		}
	}

	@Test
	fun `canonical Android boot domain fences a live generation at the revocation boundary`() =
		runTest {
			val context = ApplicationProvider.getApplicationContext<Application>()
			val bootProvider = AndroidBootClockDomainProvider(context)
			val concreteRepository = SourceRegistrationRepository(
				database,
				FakeCollectedDataLifecycleStore(CollectedDataLifecycleSnapshot(3L, null)),
				bootProvider,
				processIncarnationIdProvider,
				rolloutStore,
			)
			val bootId = bootProvider.current()
			database.sourceBrokerDao().insertDemands(
				listOf(
					demand(
						"capture",
						"session:s1",
						SourceBrokerPurpose.SESSION_CAPTURE,
						"s1",
						1L,
						true,
					).copy(requestedBootId = bootId),
				),
			)
			val registration = concreteRepository.begin(
				SourceKind.STEPS,
				1L,
				PHYSICAL_CONFIG,
				100L,
				100L,
			)
			concreteRepository.markAccepted(registration, 110L, 110L)

			database.withTransaction {
				database.fenceSourcePurposesInTransaction(
					sourceKind = SourceKind.STEPS.stableCode,
					purposes = listOf(SourceBrokerPurpose.SESSION_CAPTURE),
					bootId = bootId,
					elapsedRealtimeNanos = 200L,
					wallTimeMs = 200L,
				)
			}

			database.sourceBrokerDao().authorizationAt(
				SourceKind.STEPS.stableCode,
				registration.state.registrationGeneration,
				bootId,
				199L,
			).toAuthorizationSnapshotOrNull()?.authorizedMembers?.size shouldBe 1
			database.sourceBrokerDao().authorizationAt(
				SourceKind.STEPS.stableCode,
				registration.state.registrationGeneration,
				bootId,
				200L,
			).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
		}

	private fun demand(
		id: String,
		consumerId: String,
		purpose: String,
		logicalTrackingId: String?,
		manifestRevision: Long?,
		persistenceEligible: Boolean,
	) = SourceDemandEntity(
		demandId = id,
		consumerId = consumerId,
		sourceKind = SourceKind.STEPS.stableCode,
		purpose = purpose,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = logicalTrackingId?.let { "run-$it" },
		manifestRevision = manifestRevision,
		lifecycleLeaseGeneration = logicalTrackingId?.let { 1L },
		sourcePolicyRevision = 5L,
		consentEpoch = 8L,
		persistenceEligible = persistenceEligible,
		qosCode = 2,
		maximumAgeMs = 30_000L,
		desiredLatencyMs = 15_000L,
		requestedBootId = "boot-7",
		requestedElapsedRealtimeNanos = 100L + (manifestRevision ?: 0L),
		requestedAtMs = 100L + (manifestRevision ?: 0L),
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private companion object {
		const val PHYSICAL_CONFIG = "physical-config-v1"
	}
}

private suspend fun activateAllSourceProductLanes(database: AppDatabase): RoomTrackingRolloutStateStore {
	val bindings = SourceKind.entries.map { source ->
		ExecutableSourceLaneBinding(
			source = source,
			bindingGeneration = 1L,
			projectionId = "test-${source.name.lowercase()}-product",
			projectionVersion = 1,
			captureModes = setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
		)
	}
	return installCanonicalProductLanesForTest(
		database = database,
		bindings = bindings,
		rolloutRevision = bindings.size.toLong(),
	)
}

private class FakeCollectedDataLifecycleStore(initial: CollectedDataLifecycleSnapshot) :
	CollectedDataLifecycleStore {
	private val state = MutableStateFlow(initial)
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(epoch = state.value.epoch + 1L, retainedFromMs = deletedAtMs).also { state.emit(it) }

	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(retainedFromMs = retainedFromMs).also { state.emit(it) }
}
