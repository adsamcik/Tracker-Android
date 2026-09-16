package com.adsamcik.tracker.tracker.source.location

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.LocationAcquisitionMode
import com.adsamcik.tracker.shared.base.data.LocationRequestPriority
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.recordFullDeletion
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAppliedPlanStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.PressureSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.tracker.pipeline.persistence.DurableSignalBuffer
import com.adsamcik.tracker.tracker.pipeline.persistence.ExclusiveTrackingPersistenceLifecycleLease
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.pipeline.persistence.RawLocationObservationRepair
import com.adsamcik.tracker.tracker.pipeline.persistence.RoomPersistenceTransactor
import com.adsamcik.tracker.tracker.pipeline.persistence.SignalSerializer
import com.adsamcik.tracker.tracker.pipeline.persistence.TrackingPersistenceTransactor
import com.adsamcik.tracker.tracker.component.consumer.data.LocationTrackerComponent
import com.adsamcik.tracker.tracker.altitude.AltitudeProcessor
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.toStableFlags
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.CancellationException
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationWalQualificationAdapterTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private val payloadCodec = DefaultSourcePayloadCodec()
	private val planCodec = SourcePlanCodec()
	private lateinit var subject: LocationWalQualificationAdapter
	private val walPayloadQueries = CopyOnWriteArrayList<String>()
	private val walCoveringQueries = CopyOnWriteArrayList<String>()

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
			.allowMainThreadQueries()
			.setQueryCallback({ sql, _ ->
				if (sql.startsWith("SELECT ") && "FROM source_event_wal" in sql) {
					if (sql.startsWith("SELECT * FROM source_event_wal")) {
						walPayloadQueries.add(sql)
					} else {
						walCoveringQueries.add(sql)
					}
				}
			}, Executor(Runnable::run))
			.build()
		subject = LocationWalQualificationAdapter(database, payloadCodec, planCodec)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `canonical location WAL remains typed unverifiable without durable mock provenance`() = runTest {
		installValidFixture(receivedWallTimeMs = null)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
		assertEquals(1L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `version two WAL qualifies exact non-mock provenance without writing a second fact`() = runTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)

		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val qualified = assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)

		assertFalse(qualified.command.productEffect.isMock)
		assertEquals(
			LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			qualified.command.productEffect.durableEvidence.payloadVersion,
		)
		assertEquals(
			LocationAcquisitionMode.PLATFORM_GPS,
			evaluated.acquisitionMetadata.acquisitionMode,
		)
		assertEquals(
			LocationRequestPriority.HIGH_ACCURACY,
			evaluated.acquisitionMetadata.requestPriority,
		)
		assertEquals(50f, evaluated.acquisitionMetadata.requiredAccuracyMeters)
		assertEquals(PolicyTier.ACTIVE, evaluated.acquisitionMetadata.policyTier)
		assertEquals("SOURCE_QOS_BALANCED", evaluated.acquisitionMetadata.policyName)
		assertEquals(
			PROTECTED_LOCATION_CANONICAL_CURATION_VERSION,
			evaluated.acquisitionMetadata.curationVersion,
		)
		assertEquals(
			RECEIVED_WALL_MS,
			qualified.command.productEffect.durableEvidence.clockAuthority.receivedWallTimeMs,
		)
		assertEquals(EVENT_ID, qualified.command.mutation.identity.sourceEventId)
		assertEquals(1L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `version two WAL retains positive mock provenance in qualified product effect`() = runTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = true)),
		)

		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val qualified = assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)

		assertTrue(qualified.command.productEffect.isMock)
		assertTrue(requireNotNull(qualified.command.productEffect.payload.isMock))
	}

	@Test
	fun `real WAL handoff preserves capture policy after current policy changes`() = runTest {
		val payload = locationPayload(isMock = false).copy(
			horizontalAccuracyMeters = 40f,
			altitudeMeters = null,
			verticalAccuracyMeters = null,
		)
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(payload),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		installLaterStricterLocationPolicy()
		val demandBefore = database.sourceBrokerDao().demandsByIds(listOf(DEMAND_ID))
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val persistence = newLocationPersistence(
			dispatchers,
			RoomPersistenceTransactor(database),
		)
		val writer = ProtectedLocationOfflineCanonicalWriter(
			context,
			database,
			dispatchers,
			persistence,
		)
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer)

		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal),
		)

		assertEquals(ordinal, complete.lastCommittedOrdinal)
		val sample = requireNotNull(database.locationSampleDao().getBySourceSignalId(
			ProtectedLocationCanonicalSignalIdentity.canonicalProduct(EVENT_ID.value),
		))
		assertEquals(40f, sample.hAccM)
		assertEquals("SOURCE_QOS_BALANCED", sample.policy)
		assertEquals(RECEIVED_WALL_MS, requireNotNull(
			database.locationObservationDao().getBySourceEventId(EVENT_ID.value),
		).receivedAtMs)
		assertEquals(demandBefore, database.sourceBrokerDao().demandsByIds(listOf(DEMAND_ID)))
	}

	@Test
	fun `real WAL unknown commit reopens from receipt without rewriting product`() = runTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		var cancelAfterCommit = true
		val uncertainTransactor = object : TrackingPersistenceTransactor {
			override suspend fun <R> inTransaction(block: suspend () -> R): R {
				val result = database.withTransaction { block() }
				if (cancelAfterCommit) {
					cancelAfterCommit = false
					throw CancellationException("unknown commit")
				}
				return result
			}
		}
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val firstWriter = ProtectedLocationOfflineCanonicalWriter(
			context,
			database,
			dispatchers,
			newLocationPersistence(
				dispatchers,
				uncertainTransactor,
			),
		)
		val firstHandoff = ProtectedLocationCanonicalHandoff(database, subject, firstWriter)

		assertFailsWith<CancellationException> {
			firstHandoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal)
		}
		assertEquals(
			ordinal - 1L,
			database.sourceProjectionStateDao()
				.activeProductLane(SourceKind.LOCATION.stableCode)
				?.contiguousAdmissionOrdinal,
		)
		assertEquals(1L, database.locationSampleDao().countAll())

		val replay = ProtectedLocationCanonicalHandoff(
			database,
			subject,
			ProtectedLocationCanonicalWriter { _, _ ->
				error("Existing exact receipt must bypass the writer")
			},
		)
		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			replay.drainThrough(LOGICAL_ID, RUN_ID, ordinal),
		)

		assertEquals(ordinal, complete.lastCommittedOrdinal)
		assertEquals(1L, database.locationSampleDao().countAll())
	}

	@Test
	fun `real WAL crash-pending commands recover before replay`() = runTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val failingTransactor = object : TrackingPersistenceTransactor {
			override suspend fun <R> inTransaction(block: suspend () -> R): R {
				error("simulated destination outage")
			}
		}
		val first = ProtectedLocationCanonicalHandoff(
			database,
			subject,
			ProtectedLocationOfflineCanonicalWriter(
				context,
				database,
				dispatchers,
				newLocationPersistence(dispatchers, failingTransactor),
			),
		)

		val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
			first.drainThrough(LOGICAL_ID, RUN_ID, ordinal),
		)
		assertFalse(failed.terminal)
		assertTrue(database.pendingSignalDao().countAll() > 0)

		val replay = ProtectedLocationCanonicalHandoff(
			database,
			subject,
			ProtectedLocationOfflineCanonicalWriter(
				context,
				database,
				dispatchers,
				newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
			),
		)
		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			replay.drainThrough(LOGICAL_ID, RUN_ID, ordinal),
		)

		assertEquals(ordinal, complete.lastCommittedOrdinal)
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(0, database.pendingSignalDao().countAll())
	}

	@Test
	fun `offline cancellation during suspended recovery stops partial pipeline before lease release`() =
		runTest {
			installValidFixture(
				payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
				deliveryPayloads = listOf(locationPayload(isMock = false)),
			)
			val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
				.admissionOrdinal
			installProtectedHandoffAuthority(ordinal)
			val signal = TrackingSignal(
				timestampMs = EpochMs(500L),
				elapsedRealtimeNanos = 500_000_000L,
				pressure = PressureSignal(1_013.25f, 120f),
			)
			val encoded = SignalSerializer.encode(signal)
			database.pendingSignalDao().insertAll(
				listOf(
					PendingSignalEntity(
						signalId = "offline-cancelled-recovery",
						sessionId = 1L,
						envelopeVersion = encoded.envelopeVersion,
						payloadChecksum = encoded.payloadChecksum,
						signalJson = encoded.payloadJson,
						createdAt = signal.timestampMs.raw,
						capturedEpoch = 0L,
						acquiredAtMs = signal.timestampMs.raw,
					),
				),
			)
			val recoveryEntered = CompletableDeferred<Unit>()
			var transactionCalls = 0
			val transactor = object : TrackingPersistenceTransactor {
				override suspend fun <R> inTransaction(block: suspend () -> R): R {
					transactionCalls++
					if (transactionCalls == 2) {
						recoveryEntered.complete(Unit)
						awaitCancellation()
					}
					if (transactionCalls == 3) {
						error("simulated offline cleanup failure")
					}
					return database.withTransaction { block() }
				}
			}
			val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
			val lifecycleLease = ExclusiveTrackingPersistenceLifecycleLease()
			val persistence = newLocationPersistence(dispatchers, transactor)
			val handoff = ProtectedLocationCanonicalHandoff(
				database,
				subject,
				ProtectedLocationOfflineCanonicalWriter(
					context,
					database,
					dispatchers,
					persistence,
					lifecycleLease,
				),
			)
			val draining = backgroundScope.async {
				handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal)
			}

			recoveryEntered.await()
			draining.cancelAndJoin()

			assertEquals(1, database.pendingSignalDao().countAll())
			assertFalse(persistence.isPipelineActiveForPersistenceLifecycle())
			val liveAcquired = CompletableDeferred<Unit>()
			val liveWaiter = backgroundScope.async {
				lifecycleLease.acquireLivePipeline().also {
					liveAcquired.complete(Unit)
				}
			}
			runCurrent()
			assertFalse(liveAcquired.isCompleted)
			liveWaiter.cancelAndJoin()

			val recovered = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
				handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal),
			)
			assertEquals(ordinal, recovered.lastCommittedOrdinal)
			assertEquals(0, database.pendingSignalDao().countAll())
			lifecycleLease.acquireLivePipeline().release()
		}

	@Test
	fun `fallback receipt cancellation propagates after successful cleanup releases lifecycle`() =
		runTest {
			assertFallbackReceiptCancellation(cleanupFails = false)
		}

	@Test
	fun `fallback receipt cancellation retains failed cleanup for deterministic next-call retry`() =
		runTest {
			assertFallbackReceiptCancellation(cleanupFails = true)
		}

	@Test
	fun `confirmed fallback commit waits for retained cleanup before handoff advancement`() =
		runTest {
			assertConfirmedFallbackCommitCleanupRetry()
		}

	@Test
	fun `real WAL owner loss blocks writer before pending acknowledgement`() = runTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(
				locationPayload(isMock = false).copy(
					altitudeMeters = null,
					verticalAccuracyMeters = null,
				),
			),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		check(database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
			destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
			expectedOwner =
				SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
			expectedOwnerGeneration =
				SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
			newOwner = "OTHER_LOCATION_OWNER",
			newOwnerGeneration = 2L,
			updatedAtMs = 3_000L,
		) == 1)
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val handoff = ProtectedLocationCanonicalHandoff(
			database,
			subject,
			ProtectedLocationOfflineCanonicalWriter(
				context,
				database,
				dispatchers,
				newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
			),
		)

		val inactive = assertIs<ProtectedLocationCanonicalDrainResult.Inactive>(
			handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal),
		)

		assertEquals(
			ProtectedLocationCanonicalInactiveReason.DESTINATION_NOT_OWNED,
			inactive.reason,
		)
		assertEquals(0L, database.locationObservationDao().countAll())
		assertEquals(0, database.pendingSignalDao().countAll())
	}

	@Test
	fun `real WAL deletion settlement advances without resurrecting product`() = runTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		database.sourceEvidenceStateDao().recordFullDeletion(
			epoch = 1L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = ordinal,
			updatedAtMs = 3_000L,
		)
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			ProtectedLocationCanonicalHandoff(
				database,
				subject,
				ProtectedLocationOfflineCanonicalWriter(
					context,
					database,
					dispatchers,
					newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
				),
			).drainThrough(LOGICAL_ID, RUN_ID, ordinal),
		)

		assertEquals(ordinal, complete.lastCommittedOrdinal)
		assertEquals(1, complete.lifecycleSettled)
		assertEquals(0L, database.locationObservationDao().countAll())
	}

	@Test
	fun `real WAL chain rejects conflicting preexisting decision before receipt`() = runTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = true)),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		val signalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(EVENT_ID.value)
		database.locationObservationDecisionDao().insert(
			listOf(
				LocationObservationDecision(
					observationSourceEventId = EVENT_ID.value,
					decision = LocationObservationDecision.REJECTED,
					reason = "WRONG_NONBLANK_REASON",
					acceptedSampleSourceSignalId = null,
					sourceSignalId = signalId,
					clockDomainId = BOOT_ID,
					decidedAtMs = OBSERVED_WALL_MS,
				),
			),
		)
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
			ProtectedLocationCanonicalHandoff(
				database,
				subject,
				ProtectedLocationOfflineCanonicalWriter(
					context,
					database,
					dispatchers,
					newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
				),
			).drainThrough(LOGICAL_ID, RUN_ID, ordinal),
		)

		assertFalse(failed.terminal)
		assertEquals(ordinal - 1L, failed.lastCommittedOrdinal)
		assertEquals(null, database.locationObservationDao().getBySourceEventId(EVENT_ID.value))
		assertEquals(null, database.sourceProjectionStateDao().joinState(
			ProtectedLocationCanonicalHandoff.WRITER_ID,
			ProtectedLocationCanonicalHandoff.WRITER_VERSION,
			"canonical-receipt:${EVENT_ID.value}",
		))
		assertEquals(
			ordinal - 1L,
			database.sourceProjectionStateDao()
				.activeProductLane(SourceKind.LOCATION.stableCode)
				?.contiguousAdmissionOrdinal,
		)
	}

	@Test
	fun `real WAL chain rejects conflicting preexisting derived sample before receipt`() = runTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		val signalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(EVENT_ID.value)
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			ProtectedLocationCanonicalHandoff(
				database,
				subject,
				ProtectedLocationOfflineCanonicalWriter(
					context,
					database,
					dispatchers,
					newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
				),
			).drainThrough(LOGICAL_ID, RUN_ID, ordinal),
		)
		val exactProductionSample = requireNotNull(
			database.locationSampleDao().getBySourceSignalId(signalId),
		)
		database.withTransaction {
			database.openHelper.writableDatabase.apply {
				execSQL(
					"DELETE FROM location_observation_decision " +
						"WHERE observation_source_event_id = ?",
					arrayOf(EVENT_ID.value),
				)
				execSQL(
					"DELETE FROM location_sample WHERE source_signal_id = ?",
					arrayOf(signalId),
				)
				execSQL(
					"DELETE FROM location_observation WHERE source_event_id = ?",
					arrayOf(EVENT_ID.value),
				)
				execSQL(
					"DELETE FROM source_projection_join_state WHERE projection_id = ? " +
						"AND projection_version = ?",
					arrayOf(
						ProtectedLocationCanonicalHandoff.WRITER_ID,
						ProtectedLocationCanonicalHandoff.WRITER_VERSION,
					),
				)
				execSQL(
					"UPDATE source_product_projection_lane " +
						"SET contiguous_admission_ordinal = ? " +
						"WHERE source_kind = ? AND projection_id = ? " +
						"AND projection_version = ?",
					arrayOf(
						ordinal - 1L,
						SourceKind.LOCATION.stableCode,
						ProtectedLocationCanonicalHandoff.WRITER_ID,
						ProtectedLocationCanonicalHandoff.WRITER_VERSION,
					),
				)
			}
			database.locationSampleDao().insert(
				exactProductionSample.copy(
					altitudeM = (exactProductionSample.altitudeM ?: 0f) + 999f,
					speedMps = (exactProductionSample.speedMps ?: 0f) + 999f,
				),
			)
		}
		assertEquals(null, database.locationObservationDao().getBySourceEventId(EVENT_ID.value))
		val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
			ProtectedLocationCanonicalHandoff(
				database,
				subject,
				ProtectedLocationOfflineCanonicalWriter(
					context,
					database,
					dispatchers,
					newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
				),
			).drainThrough(LOGICAL_ID, RUN_ID, ordinal),
		)

		assertFalse(failed.terminal)
		assertEquals(ordinal - 1L, failed.lastCommittedOrdinal)
		assertEquals(null, database.locationObservationDao().getBySourceEventId(EVENT_ID.value))
		assertEquals(null, database.sourceProjectionStateDao().joinState(
			ProtectedLocationCanonicalHandoff.WRITER_ID,
			ProtectedLocationCanonicalHandoff.WRITER_VERSION,
			"canonical-receipt:${EVENT_ID.value}",
		))
		assertEquals(
			ordinal - 1L,
			database.sourceProjectionStateDao()
				.activeProductLane(SourceKind.LOCATION.stableCode)
				?.contiguousAdmissionOrdinal,
		)
	}

	@Test
	fun `maximum canonical location payload qualifies through only SQL bounded blob reads`() = runTest {
		val payload = locationPayload(isMock = false).copy(provider = "p".repeat(65_535))
		assertEquals(
			MAX_LOCATION_PAYLOAD_BYTES,
			payloadCodec.encode(payload, LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION).bytes.size,
		)
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(payload),
		)
		walPayloadQueries.clear()
		walCoveringQueries.clear()

		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)
		assertEquals(2, walPayloadQueries.size)
		assertTrue(walPayloadQueries.all { "LENGTH(payload) <=" in it })
		assertPayloadFreeCoveringReads()
	}

	@Test
	fun `maximum modified UTF legacy provider remains mock provenance unverifiable`() = runTest {
		val payload = locationPayload().copy(provider = "\u0800".repeat(21_845))
		assertEquals(
			MAX_LOCATION_PAYLOAD_BYTES - 1,
			payloadCodec.encode(payload, LEGACY_LOCATION_PAYLOAD_VERSION).bytes.size,
		)
		installValidFixture(deliveryPayloads = listOf(payload))

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `selected payload one byte beyond canonical maximum is rejected without loading a blob`() = runTest {
		installValidFixture()
		replacePayloadWithZeroBlob(EVENT_ID.value, MAX_LOCATION_PAYLOAD_BYTES + 1)
		walPayloadQueries.clear()

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_TOO_LARGE),
			subject.qualify(EVENT_ID),
		)
		assertTrue(walPayloadQueries.isEmpty())
	}

	@Test
	fun `oversized required sibling is rejected before full delivery loading`() = runTest {
		installValidFixture(
			deliveryPayloads = listOf(locationPayload(), locationPayload().copy(provider = "network")),
		)
		replacePayloadWithZeroBlob("location-event-sibling-1", MAX_LOCATION_PAYLOAD_BYTES + 1)
		walPayloadQueries.clear()

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_TOO_LARGE),
			subject.qualify(EVENT_ID),
		)
		assertOnlySelectedPayloadWasRead()
	}

	@Test
	fun `empty selected payload is malformed without loading a blob`() = runTest {
		installValidFixture()
		replacePayloadWithZeroBlob(EVENT_ID.value, 0)
		walPayloadQueries.clear()

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MALFORMED_DELIVERY),
			subject.qualify(EVENT_ID),
		)
		assertTrue(walPayloadQueries.isEmpty())
	}

	@Test
	fun `empty required sibling is rejected before full delivery loading`() = runTest {
		installValidFixture(
			deliveryPayloads = listOf(locationPayload(), locationPayload().copy(provider = "network")),
		)
		replacePayloadWithZeroBlob("location-event-sibling-1", 0)
		walPayloadQueries.clear()

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MALFORMED_DELIVERY),
			subject.qualify(EVENT_ID),
		)
		assertOnlySelectedPayloadWasRead()
	}

	@Test
	fun `maximum delivery cardinality qualifies without an unbounded sequence lookup`() = runTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = List(256) { index ->
				locationPayload(isMock = false).copy(provider = "provider-${index.toString().padStart(3, '0')}")
			},
		)
		walPayloadQueries.clear()

		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)
		assertEquals(2, walPayloadQueries.size)
		assertTrue(walPayloadQueries.all { "LENGTH(payload) <=" in it })
	}

	@Test
	fun `declared delivery overflow is rejected before full delivery loading`() = runTest {
		installValidFixture(
			deliveryPayloads = List(257) { index -> locationPayload().copy(provider = "provider-$index") },
		)
		walPayloadQueries.clear()

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_TOO_LARGE),
			subject.qualify(EVENT_ID),
		)
		assertOnlySelectedPayloadWasRead()
	}

	@Test
	fun `undeclared extra delivery member is discovered by cap plus one preflight`() = runTest {
		installValidFixture(
			deliveryPayloads = List(257) { index -> locationPayload().copy(provider = "provider-$index") },
			declaredUnitCount = 256,
		)
		walPayloadQueries.clear()

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_TOO_LARGE),
			subject.qualify(EVENT_ID),
		)
		assertOnlySelectedPayloadWasRead()
	}

	@Test
	fun `version two mock provenance participates in exact delivery identity`() = runTest {
		val nonMockPayload = locationPayload(isMock = false)
		val nonMockIdentity = sourceDeliveryIdentity(
			canonicalLocationDelivery(
				payloads = listOf(nonMockPayload),
				payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			),
		).value
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(nonMockPayload.copy(isMock = true)),
			deliveryIdentityOverride = nonMockIdentity,
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `independent event and delivery queries preserve value identity before mock rejection`() = runTest {
		installValidFixture()
		val walDao = database.sourceEventWalDao()
		val eventRead = requireNotNull(walDao.getByEventId(EVENT_ID.value))
		val deliveryRead = walDao.deliveryEvents(
			sourceKind = LOCATION_SOURCE,
			collectedDataEpoch = eventRead.capturedCollectedDataEpoch,
			clockDomainId = eventRead.clockDomainId,
			deliveryIdentity = requireNotNull(eventRead.deliveryIdentity),
			limit = 2,
		).single()
		assertNotSame(eventRead, deliveryRead)
		assertNotSame(eventRead.payload, deliveryRead.payload)
		assertContentEquals(eventRead.payload, deliveryRead.payload)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `later mutable applied-plan pointer cannot replace exact historical plan binding`() = runTest {
		installValidFixture()
		val laterPlan = locationPlan(2L).copy(requestedIntervalMs = 30_000L)
		insertPlan(laterPlan)
		database.sourcePlanStateDao().saveAppliedState(
			SourceAppliedPlanStateEntity(
				sourceKind = LOCATION_SOURCE,
				desiredRevision = 2L,
				appliedRevision = 2L,
				sourceInstanceId = SOURCE_INSTANCE,
				registrationGeneration = REGISTRATION_GENERATION + 1L,
				appliedAtElapsedNanos = 700L,
				status = "APPLIED",
				degradedReasons = "",
				updatedAtMs = 2_000L,
			),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `zero source sequence cannot be accepted as a durably allocated delivery`() = runTest {
		installValidFixture(sourceSequence = 0L)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `source sequence at exhausted allocator sentinel fails closed`() = runTest {
		installValidFixture(sourceSequence = Long.MAX_VALUE)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `next same-registration authorization may be sparse across global revisions`() = runTest {
		installValidFixture()
		val fingerprint = locationPlan(PLAN_REVISION).physicalConfigurationFingerprint()
		database.sourceBrokerDao().insertRegistration(
			registration(fingerprint).copy(
				registrationGeneration = REGISTRATION_GENERATION + 1L,
				sourceInstanceId = "interleaved-location-instance",
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = LOCATION_SOURCE,
				registrationGeneration = REGISTRATION_GENERATION + 1L,
				authorizationRevision = AUTHORIZATION_REVISION + 1L,
				demands = emptyList(),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 650L,
				effectiveWallTimeMs = 1_650L,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = LOCATION_SOURCE,
				registrationGeneration = REGISTRATION_GENERATION,
				authorizationRevision = AUTHORIZATION_REVISION + 2L,
				demands = emptyList(),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 700L,
				effectiveWallTimeMs = 1_700L,
			),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `noncanonical WAL bytes fail before provider payload can reach qualifier`() = runTest {
		installValidFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = X'00' WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.WAL_INTEGRITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `delivery identity must authenticate the complete canonical provider batch`() = runTest {
		installValidFixture(deliveryIdentityOverride = "a".repeat(64))

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `full deletion high-water prevents dormant shadow qualification`() = runTest {
		installValidFixture(
			evidenceState = SourceEvidenceState(
				collectedDataEpoch = 0L,
				deletedSourceEventHighWaterOrdinal = 1L,
			),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELETED_EVIDENCE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `desired plan whose exact bytes do not own registration fails closed`() = runTest {
		val actualPlan = locationPlan(PLAN_REVISION)
		val differentPlan = actualPlan.copy(requestedIntervalMs = 2_000L)
		installValidFixture(plan = differentPlan, providerFingerprint = actualPlan.physicalConfigurationFingerprint())

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `complete canonical batch authenticates the exact selected indexed unit`() = runTest {
		installValidFixture(
			deliveryPayloads = listOf(
				locationPayload().copy(provider = "network"),
				locationPayload(),
			),
			selectedUnitIndex = 1,
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
		assertEquals(2L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `missing sibling makes declared delivery cardinality unverifiable`() = runTest {
		installValidFixture(
			deliveryPayloads = listOf(locationPayload(), locationPayload().copy(provider = "network")),
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE delivery_identity = ? AND delivery_unit_index = 1",
			arrayOf(requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value)).deliveryIdentity),
		)
		walPayloadQueries.clear()

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MALFORMED_DELIVERY),
			subject.qualify(EVENT_ID),
		)
		assertOnlySelectedPayloadWasRead()
	}

	@Test
	fun `integrity-valid but malformed payload cannot be decoded as Location`() = runTest {
		installValidFixture()
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val withMalformedPayload = original.copy(payload = byteArrayOf(0x01))
		val withChecksum = withMalformedPayload.copy(
			payloadChecksum = withMalformedPayload.calculatedPayloadChecksum(),
		)
		val rewritten = withChecksum.copy(integrityIdentity = withChecksum.calculatedIntegrityIdentity())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ?, payload_checksum = ?, integrity_identity = ? " +
				"WHERE event_id = ?",
			arrayOf(rewritten.payload, rewritten.payloadChecksum, rewritten.integrityIdentity, EVENT_ID.value),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MALFORMED_DELIVERY),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `manifest checksum mismatch fails before captured source authority is trusted`() = runTest {
		installValidFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET qos_code = ? WHERE logical_tracking_id = ? " +
				"AND manifest_revision = ? AND source_kind = ?",
			arrayOf(QOS_CODE - 1, LOGICAL_ID, MANIFEST_REVISION, LOCATION_SOURCE),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `service run and segment must preserve exact reverse binding`() = runTest {
		installValidFixture(segmentRunId = "different-run")

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SEGMENT_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `manifest stored zone must be a valid ZoneId`() = runTest {
		installValidFixture(zoneId = "Not/AZone")

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.INVALID_STORED_ZONE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `retention floor rejects an uncertainty interval that can precede it`() = runTest {
		installValidFixture(
			evidenceState = SourceEvidenceState(retainedFromMs = OBSERVED_WALL_MS + 1L),
			wallTimeUncertaintyMs = 1L,
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.RETAINED_EVIDENCE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `retention floor exactly at earliest possible wall preserves eligibility`() = runTest {
		installValidFixture(
			evidenceState = SourceEvidenceState(retainedFromMs = OBSERVED_WALL_MS - 1L),
			wallTimeUncertaintyMs = 1L,
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	private fun replacePayloadWithZeroBlob(eventId: String, byteCount: Int) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = zeroblob(?) WHERE event_id = ?",
			arrayOf(byteCount, eventId),
		)
	}

	private fun assertOnlySelectedPayloadWasRead() {
		assertEquals(1, walPayloadQueries.size)
		assertTrue("WHERE event_id = ?" in walPayloadQueries.single())
		assertTrue("LENGTH(payload) <=" in walPayloadQueries.single())
	}

	private fun assertPayloadFreeCoveringReads() {
		assertEquals(
			listOf(
				"SELECT event_id, source_kind, captured_collected_data_epoch, clock_domain_id, " +
					"delivery_identity, delivery_unit_index, delivery_unit_count, LENGTH(payload) AS payload_bytes",
				"SELECT event_id, admission_ordinal, provider_dedup_key, source_instance_id, " +
					"registration_generation, physical_configuration_fingerprint, authorization_revision, " +
					"authorization_purpose_eligibility_mask, authorization_fingerprint, " +
					"source_sequence, activity_automation_epoch, source_policy_revision, " +
					"capture_consent_epoch, session_manifest_revision, lifecycle_lease_generation, " +
					"payload_version, payload_checksum, integrity_identity",
				"SELECT event_id, delivery_unit_index, delivery_unit_count, LENGTH(payload) AS payload_bytes",
			),
			walCoveringQueries.map { it.substringBefore(" FROM source_event_wal") },
		)
	}

	private suspend fun installValidFixture(
		plan: LocationPlan = locationPlan(PLAN_REVISION),
		providerFingerprint: String = plan.physicalConfigurationFingerprint(),
		deliveryIdentityOverride: String? = null,
		evidenceState: SourceEvidenceState = SourceEvidenceState(),
		sourceSequence: Long = SOURCE_SEQUENCE,
		payloadVersion: Int = LEGACY_LOCATION_PAYLOAD_VERSION,
		deliveryPayloads: List<LocationFixPayload> = listOf(locationPayload()),
		declaredUnitCount: Int = deliveryPayloads.size,
		selectedUnitIndex: Int = 0,
		zoneId: String = ZONE_ID,
		segmentRunId: String = RUN_ID,
		wallTimeUncertaintyMs: Long = 0L,
		receivedWallTimeMs: Long? = RECEIVED_WALL_MS,
	) {
		require(selectedUnitIndex in deliveryPayloads.indices)
		database.sourceEvidenceStateDao().ensure(evidenceState)
		insertPlan(plan)
		installPolicyAndConsent()
		val segmentId = database.sessionSegmentDao().insert(segment(segmentRunId))
		assertEquals(SEGMENT_ID, segmentId)
		installSessionAndManifest(segmentId, zoneId)
		val demand = demand()
		database.sourceBrokerDao().insertDemands(listOf(demand))
		database.sourceBrokerDao().insertRegistration(registration(providerFingerprint))
		val authorization = SourceBrokerAuthorization.rows(
			sourceKind = LOCATION_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			demands = listOf(demand),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = 1_050L,
		)
		database.sourceBrokerDao().insertAuthorizations(authorization)
		val deliveryIdentity = deliveryIdentityOverride ?: sourceDeliveryIdentity(
			canonicalLocationDelivery(deliveryPayloads, payloadVersion),
		).value
		val walRows = deliveryPayloads.mapIndexed { index, payload ->
			val encodedPayload = payloadCodec.encode(payload, payloadVersion)
			val unsignedWal = SourceEventWalEntity(
				eventId = if (index == selectedUnitIndex) EVENT_ID.value else "location-event-sibling-$index",
				providerDedupKey = null,
				deliveryIdentity = deliveryIdentity,
				deliveryUnitIndex = index,
				deliveryUnitCount = declaredUnitCount,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				sourceKind = LOCATION_SOURCE,
				sourceInstanceId = SOURCE_INSTANCE,
				registrationGeneration = REGISTRATION_GENERATION,
				physicalConfigurationFingerprint = providerFingerprint,
				authorizationRevision = AUTHORIZATION_REVISION,
				authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				authorizationFingerprint = authorization.first().authorizationFingerprint,
				sourceSequence = Math.addExact(sourceSequence, index.toLong()),
				configRevision = PLAN_REVISION,
				planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
				clockDomainId = BOOT_ID,
				observedElapsedNanos = OBSERVED_NANOS,
				observedIntervalStartNanos = OBSERVED_NANOS,
				receivedElapsedNanos = RECEIVED_NANOS,
				receivedWallTimeMs = receivedWallTimeMs,
				wallTimeMs = OBSERVED_WALL_MS,
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
				capturedCollectedDataEpoch = 0L,
				sourcePolicyRevision = POLICY_REVISION,
				captureConsentEpoch = CONSENT_EPOCH,
				sessionManifestRevision = MANIFEST_REVISION,
				lifecycleLeaseGeneration = LEASE_GENERATION,
				acquiredAtMs = OBSERVED_WALL_MS,
				qualityFlags = SourceQuality().toStableFlags(),
				qualityConfidence = null,
				payloadVersion = payloadVersion,
				payload = encodedPayload.bytes,
				payloadChecksum = encodedPayload.checksum,
				createdAtMs = 1_600L,
			)
			unsignedWal.copy(integrityIdentity = unsignedWal.calculatedIntegrityIdentity())
		}
		assertEquals(walRows.size, database.sourceEventWalDao().insertDeliveryUnits(walRows).size)
	}

	private suspend fun insertPlan(plan: LocationPlan) {
		val encoded = planCodec.encode(plan)
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(
				revision = plan.revision,
				planId = "plan-${plan.revision}",
				createdAtMs = 900L,
				status = "EFFECTIVE",
				sourcePolicyRevision = POLICY_REVISION,
			),
		)
		database.sourcePlanStateDao().insertDesiredPlans(
			listOf(
				SourceDesiredPlanEntity(
					revision = plan.revision,
					sourceKind = LOCATION_SOURCE,
					payloadVersion = 1,
					payload = encoded.bytes,
					payloadChecksum = encoded.checksum,
				),
			),
		)
	}

	private suspend fun installPolicyAndConsent() {
		val policyDao = database.sourcePolicyDao()
		policyDao.ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = POLICY_REVISION,
				legacySettingsFingerprint = null,
				updatedAtMs = 1_000L,
			),
		)
		policyDao.insertPolicies(
			listOf(
				SourcePolicyEntity(
					policyRevision = POLICY_REVISION,
					sourceKind = LOCATION_SOURCE,
					enabled = true,
					qosCode = QOS_CODE,
					locationMinTimeSeconds = 1,
					locationMinDistanceMeters = 0,
					locationRequiredAccuracyMeters = 50,
					capturePersistenceEligible = true,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = false,
					captureConsentEpoch = CONSENT_EPOCH,
					controlConsentEpoch = null,
					ambientConsentEpoch = null,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
					effectiveWallTimeMs = 1_000L,
					changeReason = "TEST",
				),
			),
		)
		policyDao.insertConsentEpochs(
			listOf(
				SourceConsentEpochEntity(
					sourceKind = LOCATION_SOURCE,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					epoch = CONSENT_EPOCH,
					eligible = true,
					persistenceEligible = true,
					policyRevision = POLICY_REVISION,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
					effectiveWallTimeMs = 1_000L,
					changeReason = "TEST",
				),
			),
		)
	}

	private suspend fun installLaterStricterLocationPolicy() {
		val policyDao = database.sourcePolicyDao()
		val captured = requireNotNull(policyDao.policyAtRevision(POLICY_REVISION, LOCATION_SOURCE))
		policyDao.insertPolicies(
			listOf(
				captured.copy(
					policyRevision = POLICY_REVISION + 1L,
					qosCode = 3,
					locationRequiredAccuracyMeters = 1,
					effectiveElapsedRealtimeNanos = RECEIVED_NANOS + 1L,
					effectiveWallTimeMs = RECEIVED_WALL_MS + 1L,
					changeReason = "LATER_STRICTER_POLICY",
				),
			),
		)
		check(policyDao.compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = POLICY_REVISION,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = POLICY_REVISION + 1L,
			legacySettingsFingerprint = null,
			updatedAtMs = RECEIVED_WALL_MS + 1L,
		) == 1)
	}

	private suspend fun installProtectedHandoffAuthority(admissionOrdinal: Long) {
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
				owner = SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
				ownerGeneration =
					SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
				updatedAtMs = 2_000L,
			),
		)
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
				bindingGeneration =
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION,
				projectionId =
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
				projectionVersion =
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
				captureModeMask =
					ProtectedLocationCanonicalHandoff.MANUAL_CAPTURE_MODE_MASK,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = ROLLOUT_REVISION,
				activationOrdinal = admissionOrdinal,
				contiguousAdmissionOrdinal = admissionOrdinal - 1L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 2_000L,
				updatedAtMs = 2_000L,
			),
		)
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				sourceKind = LOCATION_SOURCE,
				sourceInstanceId = SOURCE_INSTANCE,
				registrationGeneration = REGISTRATION_GENERATION,
				lastAdmissionOrdinal = admissionOrdinal,
				lastSourceSequence = SOURCE_SEQUENCE,
				appDrainComplete = true,
				providerCoverage = "COMPLETE",
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = 2_000L,
			),
		)
	}

	private fun newLocationPersistence(
		dispatchers: DispatchersProvider,
		transactor: TrackingPersistenceTransactor,
	): PersistenceProcessor {
		val guard = ProtectedLocationCanonicalPersistenceGuard(database, subject, Unit)
		return PersistenceProcessor(
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
				dispatchers = dispatchers,
				pendingSignalClaimDao = database.pendingSignalClaimDao(),
				appDatabase = database,
			),
			transactor = transactor,
			sourceDestinationOwnerDao = database.sourceDestinationOwnerDao(),
			rawLocationObservationRepair = RawLocationObservationRepair(database, payloadCodec),
			appDatabaseProvider = Provider { database },
			protectedLocationCanonicalPersistenceGuardProvider = Provider { guard },
		)
	}

	private suspend fun TestScope.assertFallbackReceiptCancellation(cleanupFails: Boolean) {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		var transactionCalls = 0
		val transactor = object : TrackingPersistenceTransactor {
			override suspend fun <R> inTransaction(block: suspend () -> R): R {
				transactionCalls++
				if (transactionCalls == 1) {
					error("original offline write failure")
				}
				if (cleanupFails && transactionCalls == 2) {
					error("offline cleanup retry failure")
				}
				return database.withTransaction { block() }
			}
		}
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val lifecycleLease = ExclusiveTrackingPersistenceLifecycleLease()
		val persistence = newLocationPersistence(dispatchers, transactor)
		val fallbackReceiptEntered = CompletableDeferred<Unit>()
		var receiptReads = 0
		val writer = ProtectedLocationOfflineCanonicalWriter(
			context,
			database,
			dispatchers,
			persistence,
			locationComponentFactory = {
				LocationTrackerComponent(
					trackingParamsRepository = null,
					dispatchers = dispatchers,
				)
			},
			testMarker = Unit,
			persistenceLifecycleLease = lifecycleLease,
			receiptReader = { command, acquisitionMetadata ->
				receiptReads++
				if (receiptReads == 2) {
					database.withTransaction {
						fallbackReceiptEntered.complete(Unit)
						awaitCancellation()
					}
				}
				database.withTransaction {
					database.readProtectedLocationCanonicalReceipt(
						command,
						acquisitionMetadata,
					)
				}
			},
		)
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer)
		val draining = backgroundScope.async {
			handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal)
		}
		fallbackReceiptEntered.await()

		draining.cancel()
		draining.join()
		val cancellation = assertFailsWith<CancellationException> {
			draining.await()
		}
		assertTrue(cancellation.suppressed.isNotEmpty())

		if (cleanupFails) {
			assertTrue(database.pendingSignalDao().countAll() > 0)
			assertTrue(cancellation.suppressed.size >= 2)
			val liveAcquired = CompletableDeferred<Unit>()
			val liveWaiter = backgroundScope.async {
				lifecycleLease.acquireLivePipeline().also {
					liveAcquired.complete(Unit)
				}
			}
			runCurrent()
			assertFalse(liveAcquired.isCompleted)
			liveWaiter.cancelAndJoin()

			val recovered = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
				handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal),
			)
			assertEquals(ordinal, recovered.lastCommittedOrdinal)
			assertEquals(0, database.pendingSignalDao().countAll())
		}
		lifecycleLease.acquireLivePipeline().release()
	}

	private suspend fun TestScope.assertConfirmedFallbackCommitCleanupRetry() {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		val dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val lifecycleLease = ExclusiveTrackingPersistenceLifecycleLease()
		val persistence = newLocationPersistence(
			dispatchers,
			RoomPersistenceTransactor(database),
		)
		val altitudeProcessor = mockk<AltitudeProcessor>()
		var resetCalls = 0
		every { altitudeProcessor.reset() } answers {
			resetCalls++
			if (resetCalls == 1) error("simulated committed cleanup failure")
		}
		var receiptReads = 0
		val writer = ProtectedLocationOfflineCanonicalWriter(
			context,
			database,
			dispatchers,
			persistence,
			locationComponentFactory = {
				LocationTrackerComponent(
					trackingParamsRepository = null,
					dispatchers = dispatchers,
					altitudeProcessorFactory = { altitudeProcessor },
				)
			},
			testMarker = Unit,
			persistenceLifecycleLease = lifecycleLease,
			receiptReader = { command, acquisitionMetadata ->
				receiptReads++
				if (receiptReads == 2) {
					error("post-commit receipt read failure")
				}
				database.withTransaction {
					database.readProtectedLocationCanonicalReceipt(
						command,
						acquisitionMetadata,
					)
				}
			},
		)
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer)

		val first = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
			handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal),
		)
		assertEquals(ordinal - 1L, first.lastCommittedOrdinal)
		assertEquals("LOCATION_CANONICAL_OFFLINE_CLEANUP_PENDING", first.failureCode)
		assertFalse(first.terminal)
		assertEquals(1L, database.locationSampleDao().countAll())
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val qualified = assertIs<LocationObservationQualification.Qualified>(
			evaluated.qualification,
		)
		assertIs<ProtectedLocationCanonicalReceipt.Complete>(
			database.readProtectedLocationCanonicalReceipt(
				qualified.command,
				PROTECTED_LOCATION_ACQUISITION,
			),
		)
		assertEquals(
			ordinal - 1L,
			database.sourceProjectionStateDao()
				.activeProductLane(SourceKind.LOCATION.stableCode)
				?.contiguousAdmissionOrdinal,
		)
		val liveAcquired = CompletableDeferred<Unit>()
		val liveWaiter = backgroundScope.async {
			lifecycleLease.acquireLivePipeline().also {
				liveAcquired.complete(Unit)
			}
		}
		runCurrent()
		assertFalse(liveAcquired.isCompleted)
		liveWaiter.cancelAndJoin()

		val recovered = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal),
		)
		assertEquals(ordinal, recovered.lastCommittedOrdinal)
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(
			ordinal,
			database.sourceProjectionStateDao()
				.activeProductLane(SourceKind.LOCATION.stableCode)
				?.contiguousAdmissionOrdinal,
		)
		lifecycleLease.acquireLivePipeline().release()
	}

	private suspend fun installSessionAndManifest(segmentId: Long, zoneId: String) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = ROLLOUT_REVISION,
				startOrigin = START_ORIGIN,
				clockDomainId = BOOT_ID,
				startedAtMs = RUN_START_WALL_MS,
				startedElapsedNanos = RUN_START_NANOS,
				cutoffAtMs = 2_000L,
				cutoffElapsedNanos = SESSION_END_NANOS,
				completedAtMs = 2_000L,
				finalAdmissionOrdinal = 1L,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = MANIFEST_REVISION,
				currentIntentRevision = 1L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = RUN_ID,
				logicalTrackingId = LOGICAL_ID,
				state = "FINALIZED",
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = ROLLOUT_REVISION,
				foregroundCapabilityFlags = 0L,
				startedAtMs = RUN_START_WALL_MS,
				startedElapsedNanos = RUN_START_NANOS,
				completedAtMs = 2_000L,
				completionReason = "USER_STOP",
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = START_ORIGIN,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "location-start-token",
				startCommandGeneration = 1L,
				preparedManifestRevision = MANIFEST_REVISION,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = RUN_START_WALL_MS,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = 2_000L,
			),
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = LOCATION_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = QOS_CODE,
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = PLAN_REVISION,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = START_ORIGIN,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = RUN_START_NANOS,
			effectiveWallTimeMs = RUN_START_WALL_MS,
			zoneId = zoneId,
			automationEpoch = null,
			changeReason = "MANUAL_START",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun registration(fingerprint: String) = ProviderRegistrationGenerationEntity(
		sourceKind = LOCATION_SOURCE,
		registrationGeneration = REGISTRATION_GENERATION,
		sourceInstanceId = SOURCE_INSTANCE,
		ownerScope = "LOCATION_RUNTIME",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = fingerprint,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process-1",
		status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		reservedAtMs = 1_000L,
		reservedElapsedRealtimeNanos = RUN_START_NANOS,
		acceptedAtMs = 1_050L,
		acceptedElapsedRealtimeNanos = REGISTRATION_START_NANOS,
		retiredAtMs = 1_900L,
		retiredElapsedRealtimeNanos = REGISTRATION_END_NANOS,
		failureCode = null,
		captureCallbackBarrierAuthorizationRevision = AUTHORIZATION_REVISION,
	)

	private fun demand() = SourceDemandEntity(
		demandId = DEMAND_ID,
		consumerId = "session:$RUN_ID",
		sourceKind = LOCATION_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ID,
		manifestRevision = MANIFEST_REVISION,
		lifecycleLeaseGeneration = LEASE_GENERATION,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = QOS_CODE,
		minimumAcquisitionSpec = "location:v1:high_accuracy",
		adaptiveReductionAllowed = false,
		maximumAgeMs = 1_000L,
		desiredLatencyMs = 1_000L,
		requestedDeliveryLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		requestedAtMs = RUN_START_WALL_MS,
		status = SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID,
		retireElapsedRealtimeNanos = SESSION_END_NANOS,
		retiredAtMs = 2_000L,
	)

	private fun segment(serviceRunId: String) = SessionSegment(
		id = SEGMENT_ID,
		startTimeMs = RUN_START_WALL_MS,
		endTimeMs = 2_000L,
		distanceM = 10f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = null,
		createdAt = 2_000L,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = serviceRunId,
	)

	private fun locationPlan(revision: Long) = LocationPlan(
		revision = revision,
		backend = LocationBackend.FRAMEWORK,
		mode = LocationMode.HIGH_ACCURACY,
		requestedIntervalMs = 1_000L,
		minimumUpdateIntervalMs = 500L,
		minimumDisplacementMeters = 0f,
		maximumBatchDelayMs = 0L,
		probeDurationMs = null,
		preciseLocationAvailable = true,
	)

	private fun locationPayload(isMock: Boolean? = null) = LocationFixPayload(
		latitudeDegrees = 50.087,
		longitudeDegrees = 14.421,
		horizontalAccuracyMeters = 5f,
		altitudeMeters = 210.0,
		verticalAccuracyMeters = 3f,
		speedMetersPerSecond = 1.5f,
		bearingDegrees = 90f,
		provider = "gps",
		isMock = isMock,
	)

	private fun canonicalLocationDelivery(
		payloads: List<LocationFixPayload>,
		payloadVersion: Int = LEGACY_LOCATION_PAYLOAD_VERSION,
	): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(0x4c4f4342)
				output.writeInt(payloadVersion)
				output.writeInt(payloads.size)
				payloads.forEach { payload ->
					val provider = payload.provider.encodeToByteArray()
					output.writeInt(provider.size)
					output.write(provider)
					output.writeLong(OBSERVED_NANOS)
					output.writeLong(OBSERVED_WALL_MS)
					output.writeLong(java.lang.Double.doubleToRawLongBits(payload.latitudeDegrees))
					output.writeLong(java.lang.Double.doubleToRawLongBits(payload.longitudeDegrees))
					output.writeInt(java.lang.Float.floatToRawIntBits(payload.horizontalAccuracyMeters))
					output.writeBoolean(true)
					output.writeLong(java.lang.Double.doubleToRawLongBits(requireNotNull(payload.altitudeMeters)))
					output.writeBoolean(true)
					output.writeInt(java.lang.Float.floatToRawIntBits(requireNotNull(payload.verticalAccuracyMeters)))
					output.writeBoolean(true)
					output.writeInt(java.lang.Float.floatToRawIntBits(requireNotNull(payload.speedMetersPerSecond)))
					output.writeBoolean(true)
					output.writeInt(java.lang.Float.floatToRawIntBits(requireNotNull(payload.bearingDegrees)))
					if (payloadVersion >= LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION) {
						output.writeBoolean(requireNotNull(payload.isMock))
					}
				}
			}
			bytes.toByteArray()
		}

	private companion object {
		val EVENT_ID = SourceEventId("location-event-1")
		const val LOCATION_SOURCE = 1
		const val LOGICAL_ID = "logical-location"
		const val RUN_ID = "run-location"
		const val SOURCE_INSTANCE = "location-instance"
		const val DEMAND_ID = "location-demand"
		const val BOOT_ID = "boot-1"
		const val ZONE_ID = "Europe/Prague"
		const val PLAN_REVISION = 1L
		const val POLICY_REVISION = 1L
		const val CONSENT_EPOCH = 1L
		const val MANIFEST_REVISION = 1L
		const val LEASE_GENERATION = 1L
		const val REGISTRATION_GENERATION = 1L
		const val AUTHORIZATION_REVISION = 1L
		const val ROLLOUT_REVISION = 1L
		const val SEGMENT_ID = 1L
		const val SOURCE_SEQUENCE = 1L
		const val LEGACY_LOCATION_PAYLOAD_VERSION = 1
		const val MAX_LOCATION_PAYLOAD_BYTES = 65_586
		const val QOS_CODE = 2
		const val START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val RUN_START_NANOS = 100L
		const val POLICY_START_NANOS = 100L
		const val REGISTRATION_START_NANOS = 120L
		const val AUTHORIZATION_START_NANOS = 140L
		const val OBSERVED_NANOS = 500L
		const val RECEIVED_NANOS = 600L
		const val REGISTRATION_END_NANOS = 800L
		const val SESSION_END_NANOS = 900L
		const val RUN_START_WALL_MS = 1_000L
		const val OBSERVED_WALL_MS = 1_500L
		const val RECEIVED_WALL_MS = 6_500L
	}
}
