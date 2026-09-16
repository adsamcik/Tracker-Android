package com.adsamcik.tracker.tracker.source.pressure

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierBlockedReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierResult
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierToken
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierVerification
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseFenceOwner
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.PressureSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.pipeline.persistence.DurableSignalBuffer
import com.adsamcik.tracker.tracker.pipeline.persistence.ExclusiveTrackingPersistenceLifecycleLease
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.pipeline.persistence.RoomPersistenceTransactor
import com.adsamcik.tracker.tracker.pipeline.persistence.SignalSerializer
import com.adsamcik.tracker.tracker.pipeline.persistence.TrackingPersistenceTransactor
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.PersistenceLegacySourceWriterTransitionBoundary
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.toEntity
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.PressureProviderEraseSettlement
import com.adsamcik.tracker.tracker.source.runtime.PressureProviderEraseVerification
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistenceLegacyPressureWriterLifecycleBarrierTest {
	private lateinit var database: AppDatabase
	private lateinit var persistence: PersistenceProcessor
	private lateinit var lifecycleLease: ExclusiveTrackingPersistenceLifecycleLease
	private lateinit var subject: PersistenceLegacyPressureWriterLifecycleBarrier

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure()
		RoomTrackingRolloutStateStore(database, ExecutableSourceLaneCatalog()).load()
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
				owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
				ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
				updatedAtMs = 1L,
			),
		)
		persistence = mockk()
		every { persistence.isPipelineActiveForPersistenceLifecycle() } returns false
		every { persistence.hasUnrecoverablePersistenceStateForLifecycleFence() } returns false
		every { persistence.hasUnsettledPersistenceStateForPressureFence() } returns false
		coEvery { persistence.drainOrphanedSignals() } returns true
		lifecycleLease = ExclusiveTrackingPersistenceLifecycleLease()
		subject = PersistenceLegacyPressureWriterLifecycleBarrier(
			database,
			persistence,
			lifecycleLease,
		)
	}

	@Test
	fun `Pressure establishment waits for the live persistence lifecycle`() = runTest {
		val live = lifecycleLease.acquireLivePipeline()
		var providerCalled = false
		val establishing = async {
			subject.establish(0L) {
				providerCalled = true
				PressureProviderEraseSettlement.NoLocalProvider
			}
		}

		yield()
		providerCalled shouldBe false
		live.release()
		establishing.await() shouldBe PressureSourceEraseBarrierResult.NoLocalProvider(
			PressureSourceEraseBarrierToken(
				collectedDataEpoch = 0L,
				providerRegistrationGeneration = null,
				legacyWriteFenceOwner =
					PressureSourceEraseFenceOwner.LEGACY_PRESSURE_SAMPLE,
				legacyWriteFenceGeneration =
					SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION,
			),
		)
		providerCalled shouldBe true
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `empty pending lane establishes exact durable fence after provider settlement`() = runTest {
		val result = subject.establish(0L) {
			PressureProviderEraseSettlement.Settled(7L)
		}
		val expectedToken = PressureSourceEraseBarrierToken(
			collectedDataEpoch = 0L,
			providerRegistrationGeneration = 7L,
			legacyWriteFenceOwner = PressureSourceEraseFenceOwner.LEGACY_PRESSURE_SAMPLE,
			legacyWriteFenceGeneration =
				SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION,
		)

		result shouldBe PressureSourceEraseBarrierResult.Established(
			expectedToken,
		)
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)?.ownerGeneration shouldBe
			SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION
		coVerify(exactly = 1) { persistence.drainOrphanedSignals() }
		subject.verifySettled(expectedToken) {
			PressureProviderEraseVerification.Verified
		} shouldBe PressureSourceEraseBarrierVerification.Verified
		updatePressureOwner(
			SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
			3L,
		)
		var providerCalled = false
		subject.verifySettled(expectedToken) {
			providerCalled = true
			PressureProviderEraseVerification.Verified
		} shouldBe PressureSourceEraseBarrierVerification.Blocked(
			PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
		)
		providerCalled shouldBe false
	}

	@Test
	fun `verification rejects token mismatch before provider reauthentication`() = runTest {
		var providerCalled = false

		subject.verifySettled(
			PressureSourceEraseBarrierToken(
				0L,
				7L,
				PressureSourceEraseFenceOwner.LEGACY_PRESSURE_SAMPLE,
				99L,
			),
			verifyProvider = {
				providerCalled = true
				PressureProviderEraseVerification.Verified
			},
		) shouldBe PressureSourceEraseBarrierVerification.Blocked(
			PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
		)

		providerCalled shouldBe false
	}

	@Test
	fun `epoch and provider settlement failures do not publish a fence`() = runTest {
		var providerCalled = false
		subject.establish(1L) {
			providerCalled = true
			PressureProviderEraseSettlement.NoLocalProvider
		} shouldBe PressureSourceEraseBarrierResult.Blocked(
			PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
		)
		providerCalled shouldBe false

		subject.establish(0L) {
			PressureProviderEraseSettlement.CallbackDrainTimedOut
		} shouldBe PressureSourceEraseBarrierResult.Retryable(
			PressureSourceEraseBarrierRetryableReason.CALLBACK_DRAIN_TIMED_OUT,
		)
		subject.establish(0L) {
			PressureProviderEraseSettlement.ProviderRemovalFailed
		} shouldBe PressureSourceEraseBarrierResult.Retryable(
			PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
		)
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)?.ownerGeneration shouldBe SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
	}

	@Test
	fun `owner CAS race fails closed without returning a token`() = runTest {
		coEvery { persistence.drainOrphanedSignals() } coAnswers {
			updatePressureOwner(
				SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
				SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			)
			true
		}

		subject.establish(0L) {
			PressureProviderEraseSettlement.NoLocalProvider
		} shouldBe PressureSourceEraseBarrierResult.Blocked(
			PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
		)
	}

	@Test
	fun `legacy token is rejected after same-generation candidate owner replacement`() = runTest {
		val token = when (val established = subject.establish(0L) {
			PressureProviderEraseSettlement.NoLocalProvider
		}) {
			is PressureSourceEraseBarrierResult.NoLocalProvider -> established.token
			else -> error("Expected legacy Pressure fence")
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_destination_owner SET owner = ? " +
				"WHERE source_kind = ? AND destination = ?",
			arrayOf(
				SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			),
		)
		var providerCalled = false

		subject.verifySettled(token) {
			providerCalled = true
			PressureProviderEraseVerification.Verified
		} shouldBe PressureSourceEraseBarrierVerification.Blocked(
			PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
		)

		providerCalled shouldBe false
	}

	@Test
	fun `contained owner verifies only after exact candidate lane retirement`() = runTest {
		updatePressureOwner(
			SourceDestinationOwnerEntity.OWNER_CONTAINED_PRESSURE_SESSION_FACTS,
			3L,
		)
		database.sourceProjectionStateDao().installProductLane(
			pressureLane(
				status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
				cutoffOrdinal = 10L,
				terminalDisposition =
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
			),
		)

		val expectedToken = PressureSourceEraseBarrierToken(
			0L,
			null,
			PressureSourceEraseFenceOwner.CONTAINED_PRESSURE_SESSION_FACTS,
			3L,
		)
		val established = subject.establish(0L) {
			PressureProviderEraseSettlement.NoLocalProvider
		}

		established shouldBe PressureSourceEraseBarrierResult.NoLocalProvider(
			expectedToken,
		)
		subject.verifySettled(expectedToken) {
			PressureProviderEraseVerification.Verified
		} shouldBe PressureSourceEraseBarrierVerification.Verified
		var providerCalled = false
		subject.verifySettled(expectedToken.copy(legacyWriteFenceGeneration = 5L)) {
			providerCalled = true
			PressureProviderEraseVerification.Verified
		} shouldBe PressureSourceEraseBarrierVerification.Blocked(
			PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
		)
		providerCalled shouldBe false
	}

	@Test
	fun `later contained generation derives its exact retired Pressure binding`() = runTest {
		updatePressureOwner(
			SourceDestinationOwnerEntity.OWNER_CONTAINED_PRESSURE_SESSION_FACTS,
			5L,
		)
		database.sourceProjectionStateDao().installProductLane(
			pressureLane(
				status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
				cutoffOrdinal = 10L,
				terminalDisposition =
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
			).copy(bindingGeneration = 2L),
		)

		subject.establish(0L) {
			PressureProviderEraseSettlement.NoLocalProvider
		} shouldBe PressureSourceEraseBarrierResult.NoLocalProvider(
			PressureSourceEraseBarrierToken(
				0L,
				null,
				PressureSourceEraseFenceOwner.CONTAINED_PRESSURE_SESSION_FACTS,
				5L,
			),
		)
	}

	@Test
	fun `retired Pressure lane cannot forge a fence while rollout still advertises activation`() =
		runTest {
			updatePressureOwner(
				SourceDestinationOwnerEntity.OWNER_CONTAINED_PRESSURE_SESSION_FACTS,
				3L,
			)
			database.sourceProjectionStateDao().installProductLane(
				pressureLane(
					status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
					cutoffOrdinal = 10L,
					terminalDisposition =
						SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				),
			)
			database.trackingRolloutStateDao().save(
				TrackingRolloutState.eventCanonical(
					sources = setOf(SourceKind.PRESSURE),
					revision = 2L,
				).toEntity(2L),
			)

			subject.establish(0L) {
				PressureProviderEraseSettlement.NoLocalProvider
			} shouldBe PressureSourceEraseBarrierResult.Blocked(
				PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
			)
		}

	@Test
	fun `active candidate owner never establishes an erase fence`() = runTest {
		updatePressureOwner(
			SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
			SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		)
		database.sourceProjectionStateDao().installProductLane(
			pressureLane(
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				cutoffOrdinal = null,
				terminalDisposition = null,
			),
		)
		var providerCalled = false

		subject.establish(0L) {
			providerCalled = true
			PressureProviderEraseSettlement.NoLocalProvider
		} shouldBe PressureSourceEraseBarrierResult.Blocked(
			PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
		)

		providerCalled shouldBe false
	}

	@Test
	fun `contained owner with active candidate lane is not an erase fence`() = runTest {
		updatePressureOwner(
			SourceDestinationOwnerEntity.OWNER_CONTAINED_PRESSURE_SESSION_FACTS,
			3L,
		)
		database.sourceProjectionStateDao().installProductLane(
			pressureLane(
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				cutoffOrdinal = null,
				terminalDisposition = null,
			),
		)

		subject.establish(0L) {
			PressureProviderEraseSettlement.NoLocalProvider
		} shouldBe PressureSourceEraseBarrierResult.Blocked(
			PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
		)
	}

	@Test
	fun `candidate owner with retired Pressure facts never becomes an erase fence`() = runTest {
		updatePressureOwner(
			SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
			SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		)
		database.sourceProjectionStateDao().installProductLane(
			pressureLane(
				status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
				cutoffOrdinal = 10L,
				terminalDisposition =
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
			),
		)
		var providerCalled = false

		subject.establish(0L) {
			providerCalled = true
			PressureProviderEraseSettlement.NoLocalProvider
		} shouldBe PressureSourceEraseBarrierResult.Blocked(
			PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
		)

		providerCalled shouldBe false
	}

	@Test
	fun `contained owner rejects forged Pressure lane identity and immutable activation fields`() =
		runTest {
			updatePressureOwner(
				SourceDestinationOwnerEntity.OWNER_CONTAINED_PRESSURE_SESSION_FACTS,
				3L,
			)
			val valid = pressureLane(
				status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
				cutoffOrdinal = 10L,
				terminalDisposition =
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
			)
			val forgeries = listOf(
				valid.copy(bindingGeneration = 2L),
				valid.copy(projectionId = "forged-pressure-session-facts"),
				valid.copy(projectionVersion = valid.projectionVersion + 1),
				valid.copy(captureModeMask = valid.captureModeMask shl 1),
				valid.copy(productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW),
				valid.copy(activatedRolloutRevision = 0L),
				valid.copy(activatedRolloutRevision = 2L),
				valid.copy(activationOrdinal = 0L),
				valid.copy(retentionRequired = true),
				valid.copy(terminalAtMs = null),
			)

			forgeries.forEach { forged ->
				database.openHelper.writableDatabase.execSQL(
					"DELETE FROM source_product_projection_lane WHERE source_kind = ?",
					arrayOf(SourceDestinationOwnerEntity.SOURCE_PRESSURE),
				)
				database.sourceProjectionStateDao().installProductLane(forged)
				var providerCalled = false

				subject.establish(0L) {
					providerCalled = true
					PressureProviderEraseSettlement.NoLocalProvider
				} shouldBe PressureSourceEraseBarrierResult.Blocked(
					PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
				)

				providerCalled shouldBe false
			}
		}

	@Test
	fun `contained owner rejects global writer registration and nonterminal provider state`() =
		runTest {
			updatePressureOwner(
				SourceDestinationOwnerEntity.OWNER_CONTAINED_PRESSURE_SESSION_FACTS,
				3L,
			)
			database.sourceProjectionStateDao().installProductLane(
				pressureLane(
					status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
					cutoffOrdinal = 10L,
					terminalDisposition =
						SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				),
			)
			database.sourceProjectionStateDao().register(
				SourceProjectionRegistrationEntity(
					projectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
					projectionVersion =
						SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
					activationOrdinal = 1L,
					retentionRequired = true,
					status = "ACTIVE",
					createdAtMs = 1L,
				),
			)

			subject.establish(0L) {
				PressureProviderEraseSettlement.NoLocalProvider
			} shouldBe PressureSourceEraseBarrierResult.Blocked(
				PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
			)

			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM source_projection_registration WHERE projection_id = ? " +
					"AND projection_version = ?",
				arrayOf(
					SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
				),
			)
			database.sourceBrokerDao().insertRegistration(activePressureRegistration())

			subject.establish(0L) {
				PressureProviderEraseSettlement.NoLocalProvider
			} shouldBe PressureSourceEraseBarrierResult.Blocked(
				PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
			)
		}

	@Test
	fun `later legacy fence is idempotent and cannot become manifest writer authority`() = runTest {
		updatePressureOwner(
			SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
			3L,
		)
		val expected = PressureSourceEraseBarrierResult.NoLocalProvider(
			PressureSourceEraseBarrierToken(
				0L,
				null,
				PressureSourceEraseFenceOwner.LEGACY_PRESSURE_SAMPLE,
				3L,
			),
		)

		subject.establish(0L) {
			PressureProviderEraseSettlement.NoLocalProvider
		} shouldBe expected
		subject.establish(0L) {
			PressureProviderEraseSettlement.NoLocalProvider
		} shouldBe expected
		shouldThrow<IllegalArgumentException> {
			com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity(
				logicalTrackingId = "pressure-fenced",
				manifestRevision = 1L,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				consentEpoch = 1L,
				persistenceEligible = true,
				qosCode = 1,
				outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
				writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
				writerOwnerGeneration = 3L,
			)
		}
	}

	@Test
	fun `direct Pressure demand appearing after fence blocks erase verification`() = runTest {
		val token = when (val established = subject.establish(0L) {
			PressureProviderEraseSettlement.NoLocalProvider
		}) {
			is PressureSourceEraseBarrierResult.NoLocalProvider -> established.token
			else -> error("Expected an established no-provider Pressure fence")
		}
		database.sourceBrokerDao().insertDemands(
			listOf(
				SourceDemandEntity(
					demandId = "pressure-stale-demand",
					consumerId = "pressure-test",
					sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = true,
					qosCode = 0,
					maximumAgeMs = 1_000L,
					desiredLatencyMs = 1_000L,
					requestedBootId = "boot-test",
					requestedElapsedRealtimeNanos = 1L,
					requestedAtMs = 1L,
					status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null,
					retireElapsedRealtimeNanos = null,
					retiredAtMs = null,
				),
			),
		)
		var providerCalled = false

		subject.verifySettled(token) {
			providerCalled = true
			PressureProviderEraseVerification.Verified
		} shouldBe PressureSourceEraseBarrierVerification.Blocked(
			PressureSourceEraseBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
		)

		providerCalled shouldBe false
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)?.ownerGeneration shouldBe
			SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION
	}

	@Test
	fun `pending Pressure command blocks exact fence verification`() = runTest {
		val token = when (val established = subject.establish(0L) {
			PressureProviderEraseSettlement.NoLocalProvider
		}) {
			is PressureSourceEraseBarrierResult.NoLocalProvider -> established.token
			else -> error("Expected legacy Pressure fence")
		}
		database.pendingSignalDao().insertAll(
			listOf(
				PendingSignalEntity(
					signalId = "pressure-after-fence",
					sessionId = 1L,
					signalJson = "{}",
					createdAt = 1L,
					pressureWriterOwner =
						SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
					pressureWriterOwnerGeneration =
						SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION,
				),
			),
		)
		var providerCalled = false

		subject.verifySettled(token) {
			providerCalled = true
			PressureProviderEraseVerification.Verified
		} shouldBe PressureSourceEraseBarrierVerification.Blocked(
			PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
		)

		providerCalled shouldBe false
	}

	@Test
	fun `staged or in-memory persistence blocks an existing fence verification`() = runTest {
		val token = when (val established = subject.establish(0L) {
			PressureProviderEraseSettlement.NoLocalProvider
		}) {
			is PressureSourceEraseBarrierResult.NoLocalProvider -> established.token
			else -> error("Expected legacy Pressure fence")
		}
		every { persistence.hasUnsettledPersistenceStateForPressureFence() } returns true
		var providerCalled = false

		subject.verifySettled(token) {
			providerCalled = true
			PressureProviderEraseVerification.Verified
		} shouldBe PressureSourceEraseBarrierVerification.Retryable(
			PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
		)

		providerCalled shouldBe false
	}

	@Test
	fun `staged or in-memory persistence blocks first fence without clearing recovery state`() =
		runTest {
			every {
				persistence.hasUnrecoverablePersistenceStateForLifecycleFence()
			} returns true
			var providerCalled = false

			subject.establish(0L) {
				providerCalled = true
				PressureProviderEraseSettlement.NoLocalProvider
			} shouldBe PressureSourceEraseBarrierResult.Retryable(
				PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
			)

			providerCalled shouldBe true
			coVerify(exactly = 0) { persistence.drainOrphanedSignals() }
			database.sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			)?.ownerGeneration shouldBe SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
		}

	@Test
	fun `recoverable startup backlog is retried by erase and transition lifecycle fences`() =
		runTest {
			val eraseRecovery = prepareRecoverableBacklog(testScheduler, "erase-recovery")
			eraseRecovery.processor.hasUnrecoverablePersistenceStateForLifecycleFence() shouldBe false
			eraseRecovery.processor.hasUnsettledPersistenceStateForPressureFence() shouldBe true
			eraseRecovery.transactor.fail = false
			val eraseBarrier = PersistenceLegacyPressureWriterLifecycleBarrier(
				database,
				eraseRecovery.processor,
				ExclusiveTrackingPersistenceLifecycleLease(),
			)

			eraseBarrier.establish(0L) {
				PressureProviderEraseSettlement.NoLocalProvider
			}.let { result ->
				(result is PressureSourceEraseBarrierResult.NoLocalProvider) shouldBe true
			}
			database.pendingSignalDao().countAll() shouldBe 0
			database.pressureFactRevisionDao().legacyPressureSampleCount() shouldBe 1L
			eraseRecovery.processor.hasUnsettledPersistenceStateForPressureFence() shouldBe false

			val transitionRecovery =
				prepareRecoverableBacklog(testScheduler, "transition-recovery")
			transitionRecovery.transactor.fail = false
			val boundary = PersistenceLegacySourceWriterTransitionBoundary(
				transitionRecovery.processor,
				ExclusiveTrackingPersistenceLifecycleLease(),
			)

			boundary.runIfQuiescent(SourceKind.ACTIVITY) { "transitioned" } shouldBe
				"transitioned"
			database.pendingSignalDao().countAll() shouldBe 0
			transitionRecovery.processor.hasUnsettledPersistenceStateForPressureFence() shouldBe false
		}

	@Test
	fun `repeated orphan recovery failure remains retryable and retains durable backlog`() =
		runTest {
			val recovery = prepareRecoverableBacklog(testScheduler, "repeated-failure")
			val barrier = PersistenceLegacyPressureWriterLifecycleBarrier(
				database,
				recovery.processor,
				ExclusiveTrackingPersistenceLifecycleLease(),
			)

			barrier.establish(0L) {
				PressureProviderEraseSettlement.NoLocalProvider
			} shouldBe PressureSourceEraseBarrierResult.Retryable(
				PressureSourceEraseBarrierRetryableReason.PROVIDER_REMOVAL_FAILED,
			)

			database.pendingSignalDao().countAll() shouldBe 1
			recovery.processor.hasUnrecoverablePersistenceStateForLifecycleFence() shouldBe false
			recovery.processor.hasUnsettledPersistenceStateForPressureFence() shouldBe true
		}

	private suspend fun prepareRecoverableBacklog(
		scheduler: TestCoroutineScheduler,
		signalId: String,
	): RecoverablePersistence {
		insertPendingPressure(signalId)
		val transactor = SwitchablePersistenceTransactor(database)
		val processor = realPersistenceProcessor(scheduler, transactor)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(0L), sessionId = 1L))
		processor.onStop()
		return RecoverablePersistence(processor, transactor)
	}

	private suspend fun insertPendingPressure(signalId: String) {
		val signal = TrackingSignal(
			timestampMs = EpochMs(1_000L),
			elapsedRealtimeNanos = 1_000_000_000L,
			pressure = PressureSignal(1_013.25f, 120f),
		)
		val encoded = SignalSerializer.encode(signal)
		database.pendingSignalDao().insertAll(
			listOf(
				PendingSignalEntity(
					signalId = signalId,
					sessionId = 1L,
					envelopeVersion = encoded.envelopeVersion,
					payloadChecksum = encoded.payloadChecksum,
					signalJson = encoded.payloadJson,
					createdAt = signal.timestampMs.raw,
					capturedEpoch = 0L,
					acquiredAtMs = signal.timestampMs.raw,
					pressureWriterOwner =
						SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
					pressureWriterOwnerGeneration =
						SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
				),
			),
		)
	}

	private fun realPersistenceProcessor(
		scheduler: TestCoroutineScheduler,
		transactor: TrackingPersistenceTransactor = RoomPersistenceTransactor(database),
	) = PersistenceProcessor(
		locationSampleDao = database.locationSampleDao(),
		locationObservationDao = database.locationObservationDao(),
		locationObservationDecisionDao = database.locationObservationDecisionDao(),
		sourceEvidenceStateDao = database.sourceEvidenceStateDao(),
		cellSampleDao = database.cellSampleDao(),
		wifiObservationDao = database.wifiObservationDao(),
		pressureSampleDao = database.pressureSampleDao(),
		stepIntervalDao = database.stepIntervalDao(),
		activitySnapshotDao = database.activitySnapshotDao(),
		pendingSignalDao = database.pendingSignalDao(),
		pendingSignalClaimDao = database.pendingSignalClaimDao(),
		durableBuffer = DurableSignalBuffer(
			pendingSignalDao = database.pendingSignalDao(),
			dispatchers = TestDispatchersProvider(StandardTestDispatcher(scheduler)),
			pendingSignalClaimDao = database.pendingSignalClaimDao(),
			appDatabase = database,
		),
		transactor = transactor,
		sourceDestinationOwnerDao = database.sourceDestinationOwnerDao(),
		appDatabaseProvider = Provider { database },
	)

	private data class RecoverablePersistence(
		val processor: PersistenceProcessor,
		val transactor: SwitchablePersistenceTransactor,
	)

	private class SwitchablePersistenceTransactor(
		private val database: AppDatabase,
	) : TrackingPersistenceTransactor {
		var fail: Boolean = true

		override suspend fun <R> inTransaction(block: suspend () -> R): R {
			if (fail) error("transient startup recovery failure")
			return database.withTransaction { block() }
		}
	}

	private fun updatePressureOwner(owner: String, generation: Long) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_destination_owner SET owner = ?, owner_generation = ? " +
				"WHERE source_kind = ? AND destination = ?",
			arrayOf(
				owner,
				generation,
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			),
		)
	}

	private fun pressureLane(
		status: String,
		cutoffOrdinal: Long?,
		terminalDisposition: String?,
	) = SourceProductProjectionLaneEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		bindingGeneration = 1L,
		projectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		projectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		captureModeMask = 1L,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		activatedRolloutRevision = 1L,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = 10L,
		captureAdmissionCutoffOrdinal = cutoffOrdinal,
		retentionRequired = status == SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		status = status,
		terminalDisposition = terminalDisposition,
		terminalAtMs = 10L.takeIf {
			status == SourceProductProjectionLaneEntity.STATUS_RETIRED
		},
		installedAtMs = 1L,
		updatedAtMs = 10L,
	)

	private fun activePressureRegistration() = ProviderRegistrationGenerationEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		registrationGeneration = 1L,
		sourceInstanceId = "pressure-instance",
		ownerScope = "source-broker:${SourceDestinationOwnerEntity.SOURCE_PRESSURE}",
		clockDomainId = "boot-pressure",
		physicalConfigurationFingerprint = "pressure-config",
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process-pressure",
		status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		reservedAtMs = 1L,
		reservedElapsedRealtimeNanos = 1L,
		acceptedAtMs = 1L,
		acceptedElapsedRealtimeNanos = 1L,
		retiredAtMs = null,
		retiredElapsedRealtimeNanos = null,
		failureCode = null,
	)
}
