package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.fenceSourcePurposesInTransaction
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.process.ProcessIncarnationIdProvider
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityReader
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
	private lateinit var retentionReader: TestLiveAmbientRetentionAuthorityReader
	private lateinit var lifecycleStore: FakeCollectedDataLifecycleStore

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		rolloutStore = runBlocking {
			activateAllSourceProductLanes(database)
		}
		processIncarnationIdProvider = ProcessIncarnationIdProvider()
		retentionReader = TestLiveAmbientRetentionAuthorityReader()
		runBlocking { installTestPolicyAndRetention() }
		lifecycleStore = FakeCollectedDataLifecycleStore(CollectedDataLifecycleSnapshot(3L, null))
		subject = SourceRegistrationRepository(
			database,
			lifecycleStore,
			object : BootClockDomainProvider {
				override fun current(): String = "boot-7"
			},
			processIncarnationIdProvider,
			rolloutStore,
			retentionBroker(),
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
	fun `restored non-session pressure demands cannot reserve or authorize a provider`() = runTest {
		enablePressureRegistrationForTest()
		database.sourceBrokerDao().insertDemands(
			listOf(
				demand(
					"pressure-control",
					"app:legacy-control",
					SourceBrokerPurpose.CONTROL_AUTOSTART,
					null,
					null,
					false,
					SourceKind.PRESSURE,
				),
				demand(
					"pressure-ambient",
					"app:legacy-ambient",
					SourceBrokerPurpose.AMBIENT_PRODUCT,
					null,
					null,
					false,
					SourceKind.PRESSURE,
				),
			),
		)

		shouldThrow<IllegalArgumentException> {
			subject.begin(SourceKind.PRESSURE, 1L, PHYSICAL_CONFIG, 100L, 100L)
		}
		database.sourceBrokerDao().maximumRegistrationGeneration(SourceKind.PRESSURE.stableCode) shouldBe 0L
		database.sourceBrokerDao().maximumAuthorizationRevision(SourceKind.PRESSURE.stableCode) shouldBe 0L
	}

	@Test
	fun `non-session pressure demand blocks reserved provider acceptance`() = runTest {
		enablePressureRegistrationForTest()
		database.sourceBrokerDao().insertDemands(
			listOf(pressureCaptureDemand()),
		)
		val reservation = subject.begin(SourceKind.PRESSURE, 1L, PHYSICAL_CONFIG, 100L, 100L)
		database.sourceBrokerDao().retireConsumer("session:s1", "boot-7", 110L, 110L)
		database.sourceBrokerDao().insertDemands(
			listOf(
				demand(
					"pressure-control",
					"app:legacy-control",
					SourceBrokerPurpose.CONTROL_AUTOSTART,
					null,
					null,
					false,
					SourceKind.PRESSURE,
				),
			),
		)

		shouldThrow<IllegalStateException> {
			subject.markAccepted(reservation, 120L, 120L)
		}
		database.sourceBrokerDao().registration(
			SourceKind.PRESSURE.stableCode,
			reservation.state.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RESERVED
		database.sourceBrokerDao().maximumAuthorizationRevision(SourceKind.PRESSURE.stableCode) shouldBe 1L
	}

	@Test
	fun `non-session pressure demand blocks active authorization refresh`() = runTest {
		enablePressureRegistrationForTest()
		database.sourceBrokerDao().insertDemands(listOf(pressureCaptureDemand()))
		val registration = subject.begin(SourceKind.PRESSURE, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(registration, 110L, 110L)
		database.sourceBrokerDao().retireConsumer("session:s1", "boot-7", 120L, 120L)
		database.sourceBrokerDao().insertDemands(
			listOf(
				demand(
					"pressure-ambient",
					"app:legacy-ambient",
					SourceBrokerPurpose.AMBIENT_PRODUCT,
					null,
					null,
					false,
					SourceKind.PRESSURE,
				),
			),
		)

		subject.refreshActiveAuthorization(
			SourceKind.PRESSURE,
			registration,
			2L,
			PHYSICAL_CONFIG,
			130L,
			130L,
		) shouldBe null
		database.sourceBrokerDao().maximumAuthorizationRevision(SourceKind.PRESSURE.stableCode) shouldBe 1L
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
			retentionBroker(),
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
		registration.providerAcceptedElapsedRealtimeNanos shouldBe null

		subject.markAccepted(registration, acceptedAtMs = 120L, acceptedElapsedRealtimeNanos = 120L)
		database.sourceBrokerDao().registration(SourceKind.STEPS.stableCode, 1L)?.status shouldBe
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
	}

	@Test
	fun `incompatible Steps providers retain isolated purpose authority and owner pointers`() = runTest {
		val capture = demand(
			"capture",
			"session:s1",
			SourceBrokerPurpose.SESSION_CAPTURE,
			"s1",
			1L,
			true,
		)
		val ambient = demand(
			"ambient",
			"app:ambient:steps",
			SourceBrokerPurpose.AMBIENT_PRODUCT,
			null,
			null,
			true,
		)
		database.sourceBrokerDao().insertDemands(listOf(capture, ambient))

		val direct = subject.beginPurposeScoped(
			source = SourceKind.STEPS,
			appliedRevision = 1L,
			physicalConfigurationFingerprint = "direct-counter-v1",
			updatedAtMs = 100L,
			updatedElapsedRealtimeNanos = 100L,
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		)
		subject.markAccepted(direct, 110L, 110L)
		val ambientProvider = subject.beginPurposeScoped(
			source = SourceKind.STEPS,
			appliedRevision = 1L,
			physicalConfigurationFingerprint = "ambient-provider-v1",
			updatedAtMs = 120L,
			updatedElapsedRealtimeNanos = 120L,
			purposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		)
		subject.markAccepted(ambientProvider, 130L, 130L)

		direct.ownerScope shouldBe SourceProviderPurposeScope.exactOwnerScope(
			SourceKind.STEPS.stableCode,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		)
		ambientProvider.ownerScope shouldBe SourceProviderPurposeScope.exactOwnerScope(
			SourceKind.STEPS.stableCode,
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		)
		direct.authorization.authorizedMembers.map { it.demandId } shouldBe listOf("capture")
		ambientProvider.authorization.authorizedMembers.map { it.demandId } shouldBe listOf("ambient")
		ambientProvider.authorization.authorizedMembers.single().let { member ->
			member.liveAmbientRetentionPolicyId shouldBe "privacy:steps:ambient:v1"
			member.liveAmbientRetentionApprovalRevision shouldBe 1L
		}
		database.sourceBrokerDao().currentPhysicalRegistrations(SourceKind.STEPS.stableCode)
			.map { it.registrationGeneration to it.status } shouldBe listOf(
			1L to ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
			2L to ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		)

		database.withTransaction {
			database.fenceSourcePurposesInTransaction(
				sourceKind = SourceKind.STEPS.stableCode,
				purposes = listOf(SourceBrokerPurpose.AMBIENT_PRODUCT),
				bootId = "boot-7",
				elapsedRealtimeNanos = 200L,
				wallTimeMs = 200L,
			)
		}

		database.sourceBrokerDao().authorizationAt(
			SourceKind.STEPS.stableCode,
			direct.state.registrationGeneration,
			"boot-7",
			200L,
		).toAuthorizationSnapshotOrNull()?.authorizedMembers?.map { it.demandId } shouldBe
			listOf("capture")
		database.sourceBrokerDao().authorizationAt(
			SourceKind.STEPS.stableCode,
			ambientProvider.state.registrationGeneration,
			"boot-7",
			200L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
	}

	@Test
	fun `ambient provider acceptance revalidates exact retention identity`() = runTest {
		val ambient = demand(
			"ambient-retention",
			"app:ambient:steps",
			SourceBrokerPurpose.AMBIENT_PRODUCT,
			null,
			null,
			true,
		)
		database.sourceBrokerDao().insertDemands(listOf(ambient))
		val reserved = subject.beginPurposeScoped(
			source = SourceKind.STEPS,
			appliedRevision = 1L,
			physicalConfigurationFingerprint = "ambient-provider-v1",
			updatedAtMs = 100L,
			updatedElapsedRealtimeNanos = 100L,
			purposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		)
		retentionReader.current = false

		shouldThrow<IllegalStateException> {
			subject.markAccepted(reserved, 110L, 110L)
		}
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			reserved.state.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RESERVED
	}

	@Test
	fun `provider acceptance authenticates the exact lifecycle epoch before Room mutation`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(
				demand(
					"capture-lifecycle",
					"session:lifecycle",
					SourceBrokerPurpose.SESSION_CAPTURE,
					"lifecycle",
					1L,
					true,
				),
			),
		)
		val reservation = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		lifecycleStore.beginFullDeletion(105L)

		shouldThrow<IllegalStateException> {
			subject.markAccepted(reservation, 110L, 110L)
		}

		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			reservation.state.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RESERVED
	}

	@Test
	fun `provider reservation obtains retention serialization before entering Room`() = runTest {
		val ambient = demand(
			"ambient-lock-order",
			"app:ambient:steps",
			SourceBrokerPurpose.AMBIENT_PRODUCT,
			null,
			null,
			true,
		)
		database.sourceBrokerDao().insertDemands(listOf(ambient))
		val readerEntered = CompletableDeferred<Unit>()
		val roomProbeComplete = CompletableDeferred<Unit>()
		val orderedReader = object : RetentionAuthorityReader {
			override suspend fun currentLiveAmbient(
				source: TrackingSourceComponent,
				expectedSourcePolicyRevision: Long,
				expectedAmbientConsentEpoch: Long,
				expectedCollectedDataEpoch: Long,
			): CurrentRetentionAuthority {
				readerEntered.complete(Unit)
				roomProbeComplete.await()
				return CurrentRetentionAuthority.Approved(
					opaquePolicyId = "privacy:steps:ambient:v1",
					approvalRevision = 1L,
					effectiveBootId = "boot-7",
					effectiveElapsedRealtimeNanos = 0L,
					effectiveWallTimeMs = 0L,
				)
			}

			override suspend fun isCurrentLiveAmbientAt(
				source: TrackingSourceComponent,
				expectedSourcePolicyRevision: Long,
				expectedAmbientConsentEpoch: Long,
				expectedCollectedDataEpoch: Long,
				expectedOpaquePolicyId: String,
				expectedApprovalRevision: Long,
				currentBootId: String,
				currentElapsedRealtimeNanos: Long,
				currentWallTimeMs: Long,
			): Boolean = true
		}
		val orderedRepository = SourceRegistrationRepository(
			database,
			FakeCollectedDataLifecycleStore(CollectedDataLifecycleSnapshot(3L, null)),
			BootClockDomainProvider { "boot-7" },
			processIncarnationIdProvider,
			rolloutStore,
			SourceBroker(database, rolloutStore, orderedReader),
		)

		val reservation = async {
			orderedRepository.beginPurposeScoped(
				SourceKind.STEPS,
				1L,
				PHYSICAL_CONFIG,
				100L,
				100L,
				SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			)
		}
		readerEntered.await()
		withTimeout(1_000L) {
			database.withTransaction {
				database.sourceEvidenceStateDao().get()
			}
		}
		roomProbeComplete.complete(Unit)

		reservation.await().requiresProviderAcceptance shouldBe true
	}

	@Test
	fun `provider acceptance rejects a retention revision changed after snapshot capture`() = runTest {
		val ambient = demand(
			"ambient-retention-race",
			"app:ambient:steps",
			SourceBrokerPurpose.AMBIENT_PRODUCT,
			null,
			null,
			true,
		)
		database.sourceBrokerDao().insertDemands(listOf(ambient))
		val reserved = subject.beginPurposeScoped(
			SourceKind.STEPS,
			1L,
			PHYSICAL_CONFIG,
			100L,
			100L,
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		)
		val racingReader = object : RetentionAuthorityReader {
			override suspend fun currentLiveAmbient(
				source: TrackingSourceComponent,
				expectedSourcePolicyRevision: Long,
				expectedAmbientConsentEpoch: Long,
				expectedCollectedDataEpoch: Long,
			): CurrentRetentionAuthority = CurrentRetentionAuthority.Approved(
				"privacy:steps:ambient:v1",
				1L,
				"boot-7",
				0L,
				0L,
			)

			override suspend fun isCurrentLiveAmbientAt(
				source: TrackingSourceComponent,
				expectedSourcePolicyRevision: Long,
				expectedAmbientConsentEpoch: Long,
				expectedCollectedDataEpoch: Long,
				expectedOpaquePolicyId: String,
				expectedApprovalRevision: Long,
				currentBootId: String,
				currentElapsedRealtimeNanos: Long,
				currentWallTimeMs: Long,
			): Boolean {
				database.ambientStepsFactRevisionDao().insertRetentionAuthority(
					AmbientStepsRetentionAuthorityIntegrity.create(
						scope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
						approvalRevision = 2L,
						state = AmbientStepsRetentionAuthorityEntity.STATE_REVOKED,
						opaquePolicyId = expectedOpaquePolicyId,
						sourcePolicyRevision = expectedSourcePolicyRevision,
						ambientConsentEpoch = expectedAmbientConsentEpoch,
						collectedDataEpoch = expectedCollectedDataEpoch,
						effectiveBootId = currentBootId,
						effectiveElapsedRealtimeNanos = currentElapsedRealtimeNanos,
						effectiveWallTimeMs = currentWallTimeMs,
					),
				)
				return true
			}
		}
		val racingRepository = SourceRegistrationRepository(
			database,
			lifecycleStore,
			BootClockDomainProvider { "boot-7" },
			processIncarnationIdProvider,
			rolloutStore,
			SourceBroker(database, rolloutStore, racingReader),
		)

		shouldThrow<IllegalStateException> {
			racingRepository.markAccepted(reserved, 110L, 110L)
		}
		database.sourceBrokerDao().registration(
			SourceKind.STEPS.stableCode,
			reserved.state.registrationGeneration,
		)?.status shouldBe ProviderRegistrationGenerationEntity.STATUS_RESERVED
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
		second.providerAcceptedElapsedRealtimeNanos shouldBe 110L
		second.authorization.authorizationRevision shouldBe first.authorization.authorizationRevision + 1L
		unrelatedGlobalRevision.state.registrationGeneration shouldBe 1L
		unrelatedGlobalRevision.providerAcceptedElapsedRealtimeNanos shouldBe 110L
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
		refreshed?.providerAcceptedElapsedRealtimeNanos shouldBe 110L
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
			retentionBroker(),
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
	fun `terminal checkpoint and exact run completeness commit or roll back together`() = runTest {
		database.sourceBrokerDao().insertDemands(
			listOf(demand("capture", "session:s1", SourceBrokerPurpose.SESSION_CAPTURE, "s1", 1L, true)),
		)
		val registration = subject.begin(SourceKind.STEPS, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(registration, 110L, 110L)
		val terminal = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.QUIESCED,
			metrics = RuntimeAdmissionSnapshot(2L, 20L, 0L, null, null, emptySet()),
			componentStateVersion = 7,
			componentPayload = byteArrayOf(1),
			causalOrderElapsedRealtimeNanos = 200L,
		)
		val completeness = SourceSessionCompletenessEntity(
			logicalTrackingId = "s1",
			serviceRunId = "run-s1",
			sourceKind = SourceKind.STEPS.stableCode,
			sourceInstanceId = registration.state.sourceInstanceId,
			registrationGeneration = registration.state.registrationGeneration,
			lastAdmissionOrdinal = 20L,
			lastSourceSequence = 2L,
			appDrainComplete = true,
			providerCoverage = ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER.name,
			stopStatus = SourceStopStatus.COMPLETE.name,
			unresolvedSequenceStart = null,
			unresolvedSequenceEnd = null,
			updatedAtMs = 200L,
		)

		shouldThrow<IllegalStateException> {
			subject.saveSensorRuntimeCheckpoint(
				registration,
				2L,
				terminal,
				200L,
				terminalCompleteness = completeness.copy(sourceKind = SourceKind.LOCATION.stableCode),
			)
		}
		subject.loadRuntimeState(registration) shouldBe null
		database.sourceSessionDao().completenessForServiceRun("s1", "run-s1") shouldBe emptyList()

		subject.saveSensorRuntimeCheckpoint(
			registration,
			2L,
			terminal,
			200L,
			terminalCompleteness = completeness,
		)
		subject.loadRuntimeState(registration).shouldNotBeNull()
		database.sourceSessionDao().completenessForServiceRun("s1", "run-s1") shouldBe listOf(completeness)
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
	fun `Cell callback barrier publishes only after authorization becomes CONTROL-only`() = runTest {
		val capture = cellDemand(
			"cell-capture",
			"session:cell",
			SourceBrokerPurpose.SESSION_CAPTURE,
			"cell",
			1L,
			true,
		)
		val control = cellDemand(
			"cell-control",
			"app:cell-control",
			SourceBrokerPurpose.CONTROL_AUTOSTART,
			null,
			null,
			false,
		)
		database.sourceBrokerDao().insertDemands(listOf(capture, control))
		val registration = subject.begin(SourceKind.CELL, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(registration, 110L, 110L)

		database.withTransaction {
			database.fenceSourcePurposesInTransaction(
				sourceKind = SourceKind.CELL.stableCode,
				purposes = listOf(SourceBrokerPurpose.SESSION_CAPTURE),
				bootId = "boot-7",
				elapsedRealtimeNanos = 200L,
				wallTimeMs = 200L,
			)
		}
		val refreshed = requireNotNull(subject.refreshActiveAuthorization(
			SourceKind.CELL,
			registration,
			2L,
			PHYSICAL_CONFIG,
			210L,
			210L,
		))

		subject.publishCellCaptureCallbackBarrier(refreshed, 3L) shouldBe
			CellCaptureCallbackBarrierPublication.Established(1L)
		database.sourceBrokerDao().registration(
			SourceKind.CELL.stableCode,
			registration.state.registrationGeneration,
		)?.captureCallbackBarrierAuthorizationRevision shouldBe 1L
		database.sourceBrokerDao().demandsByIds(listOf(control.demandId)) shouldBe listOf(control)
	}

	@Test
	fun `CONTROL-only Cell generation authenticates an exact zero capture barrier`() = runTest {
		val control = cellDemand(
			"cell-control",
			"app:cell-control",
			SourceBrokerPurpose.CONTROL_AUTOSTART,
			null,
			null,
			false,
		)
		database.sourceBrokerDao().insertDemands(listOf(control))
		val registration = subject.begin(SourceKind.CELL, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(registration, 110L, 110L)

		subject.publishCellCaptureCallbackBarrier(registration, 3L) shouldBe
			CellCaptureCallbackBarrierPublication.Established(0L)
		database.sourceBrokerDao().registration(
			SourceKind.CELL.stableCode,
			registration.state.registrationGeneration,
		)?.captureCallbackBarrierAuthorizationRevision shouldBe 0L
	}

	@Test
	fun `Cell callback barrier rejects active capture and stale lifecycle`() = runTest {
		val capture = cellDemand(
			"cell-capture",
			"session:cell",
			SourceBrokerPurpose.SESSION_CAPTURE,
			"cell",
			1L,
			true,
		)
		database.sourceBrokerDao().insertDemands(listOf(capture))
		val registration = subject.begin(SourceKind.CELL, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(registration, 110L, 110L)

		subject.publishCellCaptureCallbackBarrier(registration, 3L) shouldBe
			CellCaptureCallbackBarrierPublication.Blocked(
				CellCaptureCallbackBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
			)
		subject.publishCellCaptureCallbackBarrier(registration, 4L) shouldBe
			CellCaptureCallbackBarrierPublication.Blocked(
				CellCaptureCallbackBarrierBlockedReason.STALE_LIFECYCLE,
			)
		database.sourceBrokerDao().registration(
			SourceKind.CELL.stableCode,
			registration.state.registrationGeneration,
		)?.captureCallbackBarrierAuthorizationRevision shouldBe 0L
	}

	@Test
	fun `Cell callback barrier rejects a stale in-memory registration identity`() = runTest {
		val control = cellDemand(
			"cell-control",
			"app:cell-control",
			SourceBrokerPurpose.CONTROL_AUTOSTART,
			null,
			null,
			false,
		)
		database.sourceBrokerDao().insertDemands(listOf(control))
		val registration = subject.begin(SourceKind.CELL, 1L, PHYSICAL_CONFIG, 100L, 100L)
		subject.markAccepted(registration, 110L, 110L)
		val stale = registration.copy(
			state = registration.state.copy(sourceInstanceId = "stale-cell-instance"),
		)

		subject.publishCellCaptureCallbackBarrier(stale, 3L) shouldBe
			CellCaptureCallbackBarrierPublication.Blocked(
				CellCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION,
			)
		database.sourceBrokerDao().registration(
			SourceKind.CELL.stableCode,
			registration.state.registrationGeneration,
		)?.captureCallbackBarrierAuthorizationRevision shouldBe 0L
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
				retentionBroker(),
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

	@Test
	fun `Wi-Fi callback barrier authenticates exact ambient successor without retiring demands`() =
		runTest {
			val capture = wifiDemand(
				id = "wifi-capture",
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = "wifi-session",
				persistenceEligible = true,
			)
			database.sourceBrokerDao().insertDemands(listOf(capture))
			val reserved = subject.begin(SourceKind.WIFI, 1L, PHYSICAL_CONFIG, 100L, 100L)
			subject.markAccepted(reserved, 110L, 110L)
			val ambient = wifiDemand(
				id = "wifi-ambient",
				purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
				logicalTrackingId = null,
				persistenceEligible = true,
			).copy(
				requestedElapsedRealtimeNanos = 200L,
				requestedAtMs = 200L,
			)
			database.withTransaction {
				database.sourceBrokerDao().insertDemands(listOf(ambient))
				database.fenceSourcePurposesInTransaction(
					sourceKind = SourceKind.WIFI.stableCode,
					purposes = listOf(SourceBrokerPurpose.SESSION_CAPTURE),
					bootId = "boot-7",
					elapsedRealtimeNanos = 200L,
					wallTimeMs = 200L,
				)
			}
			val refreshed = requireNotNull(
				subject.refreshActiveAuthorization(
					source = SourceKind.WIFI,
					expectedRegistration = reserved,
					appliedRevision = 2L,
					physicalConfigurationFingerprint = PHYSICAL_CONFIG,
					updatedAtMs = 210L,
					updatedElapsedRealtimeNanos = 210L,
				),
			)

			subject.publishWifiCaptureCallbackBarrier(refreshed, 3L) shouldBe
				WifiCaptureCallbackBarrierPublication.Established(2L)
			database.sourceBrokerDao().registration(
				SourceKind.WIFI.stableCode,
				refreshed.state.registrationGeneration,
			)?.captureCallbackBarrierAuthorizationRevision shouldBe 2L
			database.sourceBrokerDao().demandsByIds(listOf(capture.demandId, ambient.demandId))
				.associateBy(SourceDemandEntity::demandId)
				.let { retained ->
					retained.getValue(capture.demandId).status shouldBe SourceDemandEntity.STATUS_RETIRING
					retained.getValue(ambient.demandId).status shouldBe SourceDemandEntity.STATUS_ACTIVE
				}
	}

	@Test
	fun `Wi-Fi callback barrier rejects stale lifecycle and stale registration without publication`() =
		runTest {
			val ambient = wifiDemand(
				id = "wifi-ambient",
				purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
				logicalTrackingId = null,
				persistenceEligible = true,
			)
			database.sourceBrokerDao().insertDemands(listOf(ambient))
			val registration = subject.begin(SourceKind.WIFI, 1L, PHYSICAL_CONFIG, 100L, 100L)
			subject.markAccepted(registration, 110L, 110L)
			val accepted = registration.copy(
				requiresProviderAcceptance = false,
				providerAcceptedElapsedRealtimeNanos = 110L,
			)
			val staleLifecycle = SourceRegistrationRepository(
				database,
				FakeCollectedDataLifecycleStore(CollectedDataLifecycleSnapshot(4L, null)),
				object : BootClockDomainProvider {
					override fun current(): String = "boot-7"
				},
				processIncarnationIdProvider,
				rolloutStore,
				retentionBroker(),
			)

			staleLifecycle.publishWifiCaptureCallbackBarrier(accepted, 3L) shouldBe
				WifiCaptureCallbackBarrierPublication.Blocked(
					WifiCaptureCallbackBarrierBlockedReason.STALE_LIFECYCLE,
				)
			subject.publishWifiCaptureCallbackBarrier(
				accepted.copy(
					state = accepted.state.copy(sourceInstanceId = "stale-wifi-instance"),
				),
				3L,
			) shouldBe WifiCaptureCallbackBarrierPublication.Blocked(
				WifiCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION,
			)
			database.sourceBrokerDao().registration(
				SourceKind.WIFI.stableCode,
				accepted.state.registrationGeneration,
			)?.captureCallbackBarrierAuthorizationRevision shouldBe 0L
		}

	@Test
	fun `Wi-Fi callback barrier rejects an active demand not represented by current authorization`() =
		runTest {
			val ambient = wifiDemand(
				id = "wifi-ambient",
				purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
				logicalTrackingId = null,
				persistenceEligible = true,
			)
			database.sourceBrokerDao().insertDemands(listOf(ambient))
			val reserved = subject.begin(SourceKind.WIFI, 1L, PHYSICAL_CONFIG, 100L, 100L)
			subject.markAccepted(reserved, 110L, 110L)
			val accepted = reserved.copy(
				requiresProviderAcceptance = false,
				providerAcceptedElapsedRealtimeNanos = 110L,
			)
			val racedDemand = ambient.copy(
				demandId = "wifi-ambient-raced",
				consumerId = "app:wifi-ambient-raced",
				requestedElapsedRealtimeNanos = 120L,
				requestedAtMs = 120L,
			)
			database.sourceBrokerDao().insertDemands(listOf(racedDemand))

			subject.publishWifiCaptureCallbackBarrier(accepted, 3L) shouldBe
				WifiCaptureCallbackBarrierPublication.Blocked(
					WifiCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE,
				)
			database.sourceBrokerDao().registration(
				SourceKind.WIFI.stableCode,
				accepted.state.registrationGeneration,
			)?.captureCallbackBarrierAuthorizationRevision shouldBe 0L
			database.sourceBrokerDao().demandsByIds(listOf(ambient.demandId, racedDemand.demandId))
				.map(SourceDemandEntity::status) shouldBe listOf(
				SourceDemandEntity.STATUS_ACTIVE,
				SourceDemandEntity.STATUS_ACTIVE,
			)
		}

	private fun demand(
		id: String,
		consumerId: String,
		purpose: String,
		logicalTrackingId: String?,
		manifestRevision: Long?,
		persistenceEligible: Boolean,
		source: SourceKind = SourceKind.STEPS,
	) = SourceDemandEntity(
		demandId = id,
		consumerId = consumerId,
		sourceKind = source.stableCode,
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
		liveAmbientRetentionPolicyId = if (purpose == SourceBrokerPurpose.AMBIENT_PRODUCT) {
			"privacy:${source.name.lowercase()}:ambient:v1"
		} else {
			null
		},
		liveAmbientRetentionApprovalRevision =
			1L.takeIf { purpose == SourceBrokerPurpose.AMBIENT_PRODUCT },
	)

	private fun retentionBroker(): SourceBroker = SourceBroker(
		database,
		rolloutStore,
		retentionReader,
	)

	private suspend fun installTestPolicyAndRetention() {
		database.sourceEvidenceStateDao().ensure(
			com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState(
				collectedDataEpoch = 3L,
				updatedAtMs = 1L,
			),
		)
		if (database.sourceEvidenceStateDao().get()?.collectedDataEpoch != 3L) {
			database.sourceEvidenceStateDao().updateLifecycle(3L, null, 1L)
		}
		val policyDao = database.sourcePolicyDao()
		val authority = SourcePolicyAuthorityEntity(
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			currentPolicyRevision = 5L,
			legacySettingsFingerprint = null,
			updatedAtMs = 1L,
		)
		val currentAuthority = policyDao.authority()
		if (currentAuthority == null) {
			policyDao.ensureAuthority(authority)
		} else {
			policyDao.compareAndSetAuthority(
				currentAuthority.bootstrapState,
				currentAuthority.currentPolicyRevision,
				authority.bootstrapState,
				authority.currentPolicyRevision,
				authority.legacySettingsFingerprint,
				authority.updatedAtMs,
			)
		}
		policyDao.insertPolicies(
			SourceKind.entries.map { source ->
				SourcePolicyEntity(
					policyRevision = 5L,
					sourceKind = source.stableCode,
					enabled = true,
					qosCode = 2,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = true,
					controlPersistenceEligible = source == SourceKind.ACTIVITY,
					ambientPersistenceEligible =
						source in setOf(SourceKind.STEPS, SourceKind.WIFI, SourceKind.CELL),
					captureConsentEpoch = 8L,
					controlConsentEpoch = 8L.takeIf { source == SourceKind.ACTIVITY },
					ambientConsentEpoch =
						8L.takeIf { source in setOf(SourceKind.STEPS, SourceKind.WIFI, SourceKind.CELL) },
					effectiveBootId = "boot-7",
					effectiveElapsedRealtimeNanos = 1L,
					effectiveWallTimeMs = 1L,
					changeReason = "TEST",
				)
			},
		)
		policyDao.insertConsentEpochs(
			listOf(SourceKind.STEPS, SourceKind.WIFI, SourceKind.CELL).map { source ->
				SourceConsentEpochEntity(
					sourceKind = source.stableCode,
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					epoch = 8L,
					eligible = true,
					persistenceEligible = true,
					policyRevision = 5L,
					effectiveBootId = "boot-7",
					effectiveElapsedRealtimeNanos = 1L,
					effectiveWallTimeMs = 1L,
					changeReason = "TEST",
				)
			},
		)
		listOf(
			TrackingSourceComponent.STEPS,
			TrackingSourceComponent.WIFI,
			TrackingSourceComponent.CELL,
		).forEach { source ->
			retentionReader.installCurrent(
				database,
				source,
				sourcePolicyRevision = 5L,
				ambientConsentEpoch = 8L,
				collectedDataEpoch = 3L,
				bootId = "boot-7",
			)
		}
	}

	private suspend fun enablePressureRegistrationForTest() {
		database.sourceProjectionStateDao().deleteAllProductLanes()
		rolloutStore = installCanonicalProductLanesForTest(
			database = database,
			bindings = listOf(ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS),
			rolloutRevision = 100L,
		)
		subject = SourceRegistrationRepository(
			database,
			FakeCollectedDataLifecycleStore(CollectedDataLifecycleSnapshot(3L, null)),
			object : BootClockDomainProvider {
				override fun current(): String = "boot-7"
			},
			processIncarnationIdProvider,
			rolloutStore,
			retentionBroker(),
		)
	}

	private fun pressureCaptureDemand() = demand(
		"pressure-capture",
		"session:s1",
		SourceBrokerPurpose.SESSION_CAPTURE,
		"s1",
		1L,
		true,
		SourceKind.PRESSURE,
	)

	private fun cellDemand(
		id: String,
		consumerId: String,
		purpose: String,
		logicalTrackingId: String?,
		manifestRevision: Long?,
		persistenceEligible: Boolean,
	) = demand(
		id,
		consumerId,
		purpose,
		logicalTrackingId,
		manifestRevision,
		persistenceEligible,
		source = SourceKind.CELL,
	)

	private fun wifiDemand(
		id: String,
		purpose: String,
		logicalTrackingId: String?,
		persistenceEligible: Boolean,
	): SourceDemandEntity = demand(
		id = id,
		consumerId = logicalTrackingId?.let { "session:$it" } ?: "app:$id",
		purpose = purpose,
		logicalTrackingId = logicalTrackingId,
		manifestRevision = logicalTrackingId?.let { 1L },
		persistenceEligible = persistenceEligible,
		source = SourceKind.WIFI,
	)

	private companion object {
		const val PHYSICAL_CONFIG = "physical-config-v1"
	}
}

private suspend fun activateAllSourceProductLanes(database: AppDatabase): RoomTrackingRolloutStateStore {
	val bindings = SourceKind.entries
		.filterNot { source -> source == SourceKind.PRESSURE }
		.map { source ->
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
