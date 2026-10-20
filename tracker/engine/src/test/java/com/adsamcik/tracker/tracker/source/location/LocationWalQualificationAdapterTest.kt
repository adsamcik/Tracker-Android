package com.adsamcik.tracker.tracker.source.location

import android.app.Application
import android.location.Location
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.tracker.data.collection.LocationCanonicalCurationPoint
import com.adsamcik.tracker.tracker.data.collection.LocationCanonicalCurationState
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.ingress.RoomDurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.DeliveryAdmissionResult
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.coordinator.toEntity
import com.adsamcik.tracker.tracker.source.coordinator.toModel
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionAuthorityResult
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientLocationRetentionDecision
import com.adsamcik.tracker.shared.base.database.removeLocationAmbientPurposeInCurrentTransaction
import com.adsamcik.tracker.shared.base.database.pruneLocationAmbientPurposeInCurrentTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationRegistrationPlanIntegrity
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.LocationAcquisitionMode
import com.adsamcik.tracker.shared.base.data.LocationRequestPriority
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.deleteAllCollectedDataForTesting
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
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummarySelection
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
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
import com.adsamcik.tracker.tracker.source.coordinator.SourceProductDrainPlan
import com.adsamcik.tracker.tracker.source.coordinator.SourceProductDrainRequest
import com.adsamcik.tracker.tracker.source.coordinator.SourceProductDrainResult
import com.adsamcik.tracker.tracker.source.coordinator.SourceProductDrainAuthority
import com.adsamcik.tracker.tracker.source.coordinator.SourceDrainRetirementClaim
import com.adsamcik.tracker.tracker.source.coordinator.RoomSourceProductDrainRouter
import com.adsamcik.tracker.tracker.source.coordinator.ProtectedLocationCanonicalSourceDrain
import com.adsamcik.tracker.tracker.source.coordinator.authenticateSourceProductDrainRequest
import com.adsamcik.tracker.tracker.source.coordinator.buildSourceProductDrainPlan
import com.adsamcik.tracker.tracker.presentation.SessionPresentationLifecycle
import com.adsamcik.tracker.tracker.presentation.SessionPresentationBinding
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.ingress.EncodedSourcePayload
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.service.TrackingOrchestrator
import com.adsamcik.tracker.tracker.controller.DefaultTrackerServiceController
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import kotlinx.coroutines.flow.flowOf
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.toStableFlags
import com.adsamcik.tracker.tracker.source.runtime.LocationDeliveryAttribution
import com.adsamcik.tracker.tracker.source.runtime.locationDeliveryCandidate
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import io.mockk.every
import io.mockk.coEvery
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.spyk
import javax.inject.Provider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// One real Location adapter/Room fixture follows saved delivery qualification and the shared canonical
// handoff lifecycle.
@Suppress("LargeClass")
class LocationWalQualificationAdapterTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private val payloadCodec = DefaultSourcePayloadCodec()
	private val planCodec = SourcePlanCodec()
	private lateinit var subject: LocationWalQualificationAdapter
	private val walPayloadQueries = CopyOnWriteArrayList<String>()
	private val walCoveringQueries = CopyOnWriteArrayList<String>()
	private val manifestQueries = CopyOnWriteArrayList<String>()

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.inMemoryBuilder(context)
			.allowMainThreadQueries()
			.setQueryCallback({ sql, _ ->
				if (sql.startsWith("SELECT * FROM session_manifest_version WHERE service_run_id") ||
					sql.startsWith("SELECT source.* FROM session_manifest_source AS source")) {
					manifestQueries.add(sql)
				}
				if (sql.startsWith("SELECT ") && "FROM source_event_wal" in sql) {
					if (sql.startsWith("SELECT * FROM source_event_wal")) {
						walPayloadQueries.add(sql)
					} else {
						walCoveringQueries.add(sql)
					}
				}
			}, Executor(Runnable::run))
			.build()
		subject = LocationWalQualificationAdapter(
			database,
			payloadCodec,
			planCodec,
			BootClockDomainProvider { BOOT_ID },
		)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `shared capture and all-day handoff authenticates physical delivery once before real writer`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION, mixedPurposes = true,
			deliveryPayloads = listOf(locationPayload(false)))
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value)).admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		val counting = CountingLocationCodec(payloadCodec)
		subject = LocationWalQualificationAdapter(database, counting, planCodec, BootClockDomainProvider { BOOT_ID })
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val realWriter = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		var preliminaryDecodes = -1
		var preliminaryEncodes = -1
		var preliminaryBlobQueries = -1
		val writer = ProtectedLocationCanonicalWriter { command, metadata ->
			preliminaryDecodes = counting.decodes
			preliminaryEncodes = counting.encodes
			preliminaryBlobQueries = walPayloadQueries.size
			realWriter.write(command, metadata)
		}
		walPayloadQueries.clear()
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			ProtectedLocationCanonicalHandoff(database, subject, writer).drainThrough(LOGICAL_ID, RUN_ID, ordinal))
		assertEquals(1, preliminaryDecodes)
		assertEquals(2, preliminaryBlobQueries)
		assertEquals(1, counting.decodes, "Only immutable parsing may survive between snapshots")
		assertTrue(counting.encodes > preliminaryEncodes, "The writer must still revalidate fresh WAL bytes")
		assertEquals(1L, database.locationSampleDao().countAll())
		assertNotNull(database.locationPurposeDao().receiptByEvent(EVENT_ID.value))
	}

	@Test
	fun `writer transaction batch decodes sibling delivery and verifies manifest timeline once`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = List(3) { locationPayload(false).copy(latitudeDegrees = 50.087 + it * 0.0001) })
		val rows = database.sourceEventWalDao().eventsAfter(0L, 10)
		installProtectedHandoffAuthority(rows.last().admissionOrdinal, activationOrdinal = rows.first().admissionOrdinal)
		val counting = CountingLocationCodec(payloadCodec)
		subject = LocationWalQualificationAdapter(database, counting, planCodec, BootClockDomainProvider { BOOT_ID })
		val guard = ProtectedLocationCanonicalPersistenceGuard(database, subject, Unit)
		walPayloadQueries.clear()
		manifestQueries.clear()
		val qualified = database.withTransaction {
			guard.qualifyBatchInCurrentWriterTransaction(rows.map { it.eventId })
		}
		assertEquals(rows.map { it.eventId }.toSet(), qualified.keys)
		assertEquals(3, counting.decodes)
		assertEquals(4, walPayloadQueries.size, "Three selected rows and one bounded sibling delivery")
		assertEquals(2, manifestQueries.size, "One timeline header read and one source-membership read")
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		for (candidate in qualified.values) {
			assertEquals(ProtectedLocationCanonicalWriteResult.Committed,
				writer.write(candidate.command, candidate.acquisitionMetadata))
		}
		assertTrue(rows.all { database.locationObservationDao().getBySourceEventId(it.eventId) != null })
		assertEquals(3, counting.decodes)
		assertTrue(counting.encodes > 3, "Independent writer transactions still revalidate every payload")
	}

	@Test
	fun `combined purpose read keeps valid all-day data when capture manifest is missing`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION, mixedPurposes = true,
			deliveryPayloads = listOf(locationPayload(false)))
		database.openHelper.writableDatabase.execSQL("DELETE FROM session_manifest_version")
		val counting = CountingLocationCodec(payloadCodec)
		subject = LocationWalQualificationAdapter(database, counting, planCodec, BootClockDomainProvider { BOOT_ID })
		val purposes = subject.qualifyForPurposes(EVENT_ID)
		assertIs<LocationAmbientQualification.Qualified>(purposes.ambient)
		assertIs<LocationWalAdapterResult.Rejected>(purposes.capture)
		assertEquals(1, counting.decodes)
	}

	@Test
	fun `normal handoff through registered live writer decodes a sibling delivery only once per qualifier`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = List(3) { locationPayload(false) }, observedTimes = listOf(500L, 600L, 700L),
			receivedNanos = 750L)
		val rows = database.sourceEventWalDao().eventsAfter(0L, 10)
		installProtectedHandoffAuthority(rows.last().admissionOrdinal, activationOrdinal = rows.first().admissionOrdinal)
		database.openHelper.writableDatabase.execSQL("UPDATE source_session_completeness SET last_source_sequence = 2")
		val restoreFixture = activateLocationFixture()
		val counting = CountingLocationCodec(payloadCodec)
		subject = LocationWalQualificationAdapter(database, counting, planCodec, BootClockDomainProvider { BOOT_ID })
		val guardProvider = Provider {
			val writerQualifier = LocationWalQualificationAdapter(database, counting, planCodec, BootClockDomainProvider { BOOT_ID })
			ProtectedLocationCanonicalPersistenceGuard(database, writerQualifier, Unit)
		}
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val persistence = newLocationPersistence(dispatchers, RoomPersistenceTransactor(database), guardProvider)
		val orchestrator = liveLocationOrchestrator(dispatchers, persistence)
		val registry = ProtectedLocationLiveWriterRegistry()
		try {
			assertNotNull(orchestrator.initialize(context, true, PolicyTier.PRECISION, this,
				LOGICAL_ID, RUN_ID, SEGMENT_ID, TrackingRolloutState.eventCanonical(SourceKind.entries.toSet())))
			registry.attach(orchestrator)
			val writer = ProtectedLocationCanonicalWriterChain(registry::current,
				ProtectedLocationCanonicalWriter { _, _ -> error("The registered live writer must own this delivery") })
			val writes = mutableListOf<ProtectedLocationCanonicalWriteResult>()
			val observedWriter = ProtectedLocationCanonicalWriter { command, metadata ->
				writer.write(command, metadata).also(writes::add)
			}
			val drained = ProtectedLocationCanonicalHandoff(database, subject, observedWriter)
				.drainThrough(LOGICAL_ID, RUN_ID, rows.last().admissionOrdinal)
			val result = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(drained,
				"Real live handoff: $drained; writer results: $writes")
			assertEquals(3, result.observationsCommitted)
			assertEquals(rows.last().admissionOrdinal, result.lastCommittedOrdinal)
			assertTrue(rows.all { database.locationObservationDao().getBySourceEventId(it.eventId) != null })
			assertTrue(rows.all { database.locationObservationDecisionDao().getBySourceEventId(it.eventId) != null })
			assertEquals(0, database.pendingSignalDao().countAll())
			assertEquals(6, counting.decodes, "Three units parsed once by each independent qualifier")
			assertTrue(counting.encodes > counting.decodes, "Every per-point writer snapshot revalidates payload bytes")
		} finally {
			registry.detach(orchestrator)
			restoreFixture()
			orchestrator.shutdown(context)
		}
	}

	@Test
	fun `pure decode memo cannot hide changed sibling payload bytes`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false), locationPayload(false)))
		val counting = CountingLocationCodec(payloadCodec)
		subject = LocationWalQualificationAdapter(database, counting, planCodec, BootClockDomainProvider { BOOT_ID })
		assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val sibling = requireNotNull(database.sourceEventWalDao().getByEventId("location-event-sibling-1"))
		val encoded = payloadCodec.encode(locationPayload(false).copy(latitudeDegrees = 50.1), sibling.payloadVersion)
		val changed = sibling.copy(payload = encoded.bytes, payloadChecksum = encoded.checksum)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ?, payload_checksum = ?, integrity_identity = ? WHERE event_id = ?",
			arrayOf(encoded.bytes, encoded.checksum, changed.calculatedIntegrityIdentity(), changed.eventId))
		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH),
			subject.qualify(EVENT_ID))
		assertEquals(3, counting.decodes, "Changed bytes must be parsed again rather than trusting the old hint")
	}

	@Test
	fun `large valid deliveries remain usable when pure decode memo reaches its byte budget`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = List(17) { locationPayload(false).copy(provider = "p".repeat(65_500)) })
		val counting = CountingLocationCodec(payloadCodec)
		subject = LocationWalQualificationAdapter(database, counting, planCodec, BootClockDomainProvider { BOOT_ID })
		assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertTrue(counting.decodes > 17, "Uncached units use the original decoder without restricting valid data")
	}

	private suspend fun activateLocationFixture(): suspend () -> Unit {
		val dao = database.sourceSessionDao()
		val session = requireNotNull(dao.session(LOGICAL_ID))
		val run = requireNotNull(dao.serviceRun(RUN_ID))
		val stop = requireNotNull(dao.firstStopIntent(LOGICAL_ID))
		dao.updateSession(session.copy(state = "ACTIVE", lifecycleRevision = 1L, cutoffAtMs = null,
			cutoffElapsedNanos = null, completedAtMs = null, finalAdmissionOrdinal = null, failureCode = null,
			currentServiceRunId = RUN_ID, currentIntentRevision = 1L))
		dao.updateServiceRun(run.copy(state = "ACTIVE", completedAtMs = null, completionReason = null,
			runtimeAcknowledgement = "START_ACCEPTED", runtimeFailureCode = null, runRevision = 1L,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING, presentationAcknowledgedAtMs = null))
		database.openHelper.writableDatabase.execSQL("DELETE FROM session_lifecycle_intent_version WHERE intent_revision = 2")
		return {
			dao.insertLifecycleIntent(stop)
			dao.updateSession(session.copy(finalAdmissionOrdinal = database.sourceEventWalDao().maximumAdmissionOrdinal()))
			dao.updateServiceRun(run.copy(presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
				presentationAcknowledgedAtMs = null))
		}
	}

	private fun liveLocationOrchestrator(dispatchers: DispatchersProvider, persistence: PersistenceProcessor): TrackingOrchestrator {
		val settings = mockk<TrackingParamsRepository>()
		every { settings.data } returns flowOf(TrackingParamsState())
		val events = mockk<DomainEventRepository>(relaxed = true)
		return TrackingOrchestrator(DefaultTrackerServiceController().apply { updateServiceRunning(true) },
			setOf(persistence), events, dispatchers, database, settings, dailySummaryFallbackEnqueuer = {},
			enableNotifications = false, trackingHistoryRepository = realSavedLocationHistory())
	}

	private class CountingLocationCodec(private val delegate: SourcePayloadCodec) : SourcePayloadCodec by delegate {
		var decodes = 0
			private set
		var encodes = 0
			private set
		override fun encode(payload: SourcePayload, payloadVersion: Int): EncodedSourcePayload {
			if (payload is LocationFixPayload) {
				encodes++
			}
			return delegate.encode(payload, payloadVersion)
		}
		override fun decode(source: SourceKind, payloadVersion: Int, bytes: ByteArray): SourcePayload {
			if (source == SourceKind.LOCATION) {
				decodes++
			}
			return delegate.decode(source, payloadVersion, bytes)
		}
	}

	@Test
	fun `session teleport rejection preserves independently curated all-day point with one identity`() = roomTest {
		val (ordinal, writer) = rejectedSessionWriterFixture()
		val hints = mutableListOf<Long>()
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer, onSavedPointCommitted = hints::add)
		val result = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal))
		assertEquals(1, result.acceptedSamplesCommitted)
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val command = assertIs<LocationObservationQualification.Qualified>(evaluated.qualification).command
		val capture = assertIs<ProtectedLocationCanonicalReceipt.Complete>(database.readProtectedLocationCanonicalReceipt(command, evaluated.acquisitionMetadata))
		assertEquals(LocationObservationDecision.REJECTED, capture.decision.decision)
		assertEquals(null, capture.acceptedSample)
		assertEquals(null, capture.decision.acceptedSampleSourceSignalId)
		val ambient = assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID)).command
		assertEquals(true, database.readLocationAmbientReceipt(ambient))
		val sample = database.locationPurposeDao().visiblePathChunk(0, Long.MAX_VALUE, null, null, 10).single().sample
		assertEquals(EVENT_ID.value, sample.sourceEventId)
		assertEquals(OBSERVED_WALL_MS, sample.timeMs)
		assertEquals(OBSERVED_NANOS, sample.elapsedRealtimeNanos)
		assertEquals(listOf(SourceBrokerPurpose.AMBIENT_PRODUCT), database.locationPurposeDao().purposes(sample.id, 3).map { it.purpose })
		assertEquals(0, database.locationSampleDao().countBetween(0, Long.MAX_VALUE))
		assertEquals(listOf(sample.id), hints)
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal))
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(listOf(sample.id), hints)
	}

	@Test
	fun `interrupted all-day sibling commit retries rejected capture without duplication`() = roomTest {
		val (ordinal, writer) = rejectedSessionWriterFixture()
		var interrupt = true
		val interrupted = object : ProtectedLocationCanonicalWriter {
			override suspend fun write(command: LocationCapturedFactCommand, acquisitionMetadata: LocationWalAcquisitionMetadata) = writer.write(command, acquisitionMetadata)
			override suspend fun writeAmbient(command: LocationAmbientFactCommand): ProtectedLocationCanonicalWriteResult {
				val result = writer.writeAmbient(command)
				if (interrupt && result == ProtectedLocationCanonicalWriteResult.Committed) {
					interrupt = false
					throw CancellationException("commit returned before cursor acknowledgement")
				}
				return result
			}
		}
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, interrupted)
		assertFailsWith<CancellationException> { handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal) }
		assertEquals(ordinal - 1, database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE)?.contiguousAdmissionOrdinal)
		assertEquals(1L, database.locationSampleDao().countAll())
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal))
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(0, database.locationSampleDao().countBetween(0, Long.MAX_VALUE))
		val receipt = requireNotNull(database.locationPurposeDao().receiptByEvent(EVENT_ID.value))
		assertEquals(listOf(SourceBrokerPurpose.AMBIENT_PRODUCT), database.locationPurposeDao().purposes(receipt.sampleId, 3).map { it.purpose })
	}

	@Test
	fun `all-day Location admission does not require the session capture lane`() = roomTest {
		assertAmbientLocationAdmissionWithRejectedCapture("CLOSED_LANE")
	}

	@Test
	fun `all-day Location admission survives a missing session manifest`() = roomTest {
		assertAmbientLocationAdmissionWithRejectedCapture("MISSING_MANIFEST")
	}

	@Test
	fun `all-day Location after session cutoff remains independently qualified`() = roomTest {
		assertAmbientLocationAdmissionWithRejectedCapture("SESSION_CUTOFF")
	}

	@Test
	fun `canonical location WAL remains typed unverifiable without durable mock provenance`() = roomTest {
		installValidFixture(receivedWallTimeMs = null)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
		assertEquals(1L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `version two WAL qualifies exact non-mock provenance without writing a second fact`() = roomTest {
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
	fun `version two WAL retains positive mock provenance in qualified product effect`() = roomTest {
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
	fun `saved Location retries original facts after later lifecycle leases and clears its old failure`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false), locationPayload(false)))
		val originals = listOf(EVENT_ID.value, "location-event-sibling-1").map { eventId ->
			requireNotNull(database.sourceEventWalDao().getByEventId(eventId))
		}
		val firstOrdinal = originals.first().admissionOrdinal
		val lastOrdinal = originals.last().admissionOrdinal
		installProtectedHandoffAuthority(lastOrdinal, activationOrdinal = firstOrdinal)
		val sessions = database.sourceSessionDao()
		val run = requireNotNull(sessions.serviceRun(RUN_ID))
		val session = requireNotNull(sessions.session(LOGICAL_ID))
		// Reconfiguration and End advance operation authority; admitted facts retain their original generation.
		for (generation in 2L..3L) {
			sessions.updateServiceRun(run.copy(leaseGeneration = generation))
			sessions.updateSession(session.copy(lifecycleLeaseGeneration = generation,
				finalAdmissionOrdinal = lastOrdinal))
			assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		}
		val projectionId = SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID
		val projectionVersion = SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION
		database.sourceProjectionStateDao().saveFailure(SourceProjectionFailureEntity(
			projectionId, projectionVersion, firstOrdinal, 1,
			"LOCATION_ADAPTER_SESSION_MISMATCH", terminal = true, lastAttemptAtMs = 2_000L))
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer)

		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainHighWater())

		assertEquals(lastOrdinal, complete.lastCommittedOrdinal)
		assertEquals(null, database.sourceProjectionStateDao().failure(projectionId, projectionVersion, firstOrdinal))
		for (original in originals) {
			assertNotNull(database.locationObservationDao().getBySourceEventId(original.eventId))
			assertNotNull(database.locationObservationDecisionDao().getBySourceEventId(original.eventId))
			assertSavedWalUnchanged(original)
		}
		assertTrue(database.locationSampleDao().countAll() > 0L)
	}

	@Test
	@Suppress("LongMethod")
	fun `failed run missing presentation recovers saved Location without changing the newer run`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false), locationPayload(false)))
		val originals = listOf(EVENT_ID.value, "location-event-sibling-1").map {
			requireNotNull(database.sourceEventWalDao().getByEventId(it))
		}
		val unbound = removeFixturePresentation()
		val sessions = database.sourceSessionDao()
		val oldSession = requireNotNull(sessions.session(LOGICAL_ID))
		sessions.insertSession(oldSession.copy(logicalTrackingId = "new-logical", state = "ACTIVE",
			currentServiceRunId = "new-run", cutoffAtMs = null, cutoffElapsedNanos = null, completedAtMs = null))
		val newer = unbound.copy(logicalTrackingId = "new-logical", serviceRunId = "new-run", state = "ACTIVE",
			completedAtMs = null, completionReason = null, startDeliveryToken = "new-delivery", startCommandGeneration = 2L)
		sessions.insertServiceRun(newer)
		val lastOrdinal = originals.last().admissionOrdinal
		installProtectedHandoffAuthority(lastOrdinal, activationOrdinal = originals.first().admissionOrdinal)
		database.sourceProjectionStateDao().saveFailure(SourceProjectionFailureEntity(
			ProtectedLocationCanonicalHandoff.WRITER_ID, ProtectedLocationCanonicalHandoff.WRITER_VERSION,
			originals.first().admissionOrdinal, 8, "LOCATION_ADAPTER_MISSING_SEGMENT", true, 2_000L))
		assertEquals(LocationWalAdapterRejection.MISSING_SEGMENT,
			assertIs<LocationWalAdapterResult.Rejected>(subject.qualify(EVENT_ID)).reason)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer)

		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainHighWater())

		assertEquals(lastOrdinal, complete.lastCommittedOrdinal)
		val recovered = requireNotNull(sessions.serviceRun(RUN_ID))
		val recoveredSegment = requireNotNull(database.sessionSegmentDao().getById(requireNotNull(recovered.sessionSegmentId)))
		assertEquals(unbound.startedAtMs, recoveredSegment.startTimeMs)
		assertEquals(unbound.completedAtMs, recoveredSegment.endTimeMs)
		assertEquals(unbound.runRevision, recovered.runRevision)
		assertEquals(SourceServiceRunEntity.PRESENTATION_QUIESCED, recovered.presentationAcknowledgement)
		assertEquals(newer, sessions.serviceRun("new-run"))
		assertEquals(null, database.sourceProjectionStateDao().failure(ProtectedLocationCanonicalHandoff.WRITER_ID,
			ProtectedLocationCanonicalHandoff.WRITER_VERSION, originals.first().admissionOrdinal))
		for (original in originals) {
			assertNotNull(database.locationObservationDao().getBySourceEventId(original.eventId))
			assertNotNull(database.locationObservationDecisionDao().getBySourceEventId(original.eventId))
			assertSavedWalUnchanged(original)
		}
		assertTrue(database.locationSampleDao().countAll() > 0L)
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainHighWater())
		assertEquals(recovered.sessionSegmentId, sessions.serviceRun(RUN_ID)?.sessionSegmentId)
		assertEquals(1L, database.sessionSegmentDao().countTotal())
	}

	@Test
	fun `missing presentation recovery compares a saved cutoff only within its original boot`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		removeFixturePresentation()
		val dao = database.sourceSessionDao()
		val session = requireNotNull(dao.session(LOGICAL_ID))
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val sameBootCutoff = session.copy(cutoffElapsedNanos = wal.observedElapsedNanos - 1L)
		dao.updateSession(sameBootCutoff)
		val lifecycle = SessionPresentationLifecycle(database)

		assertEquals(false, lifecycle.recoverMissingForRetainedEvent(EVENT_ID.value))
		dao.updateSession(sameBootCutoff.copy(lifecycleBootId = "later-boot"))
		assertEquals(true, lifecycle.recoverMissingForRetainedEvent(EVENT_ID.value))
		assertEquals(1L, database.sessionSegmentDao().countTotal())
	}

	@Test
	fun `missing presentation recovery respects erase epoch retention and deleted event boundary`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		removeFixturePresentation()
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val lifecycle = SessionPresentationLifecycle(database)
		database.sourceEvidenceStateDao().updateLifecycle(1L, null, 3_000L)
		assertEquals(false, lifecycle.recoverMissingForRetainedEvent(EVENT_ID.value))
		database.sourceEvidenceStateDao().updateLifecycle(0L, requireNotNull(wal.wallTimeMs) + 1L, 3_001L)
		assertEquals(false, lifecycle.recoverMissingForRetainedEvent(EVENT_ID.value))
		database.sourceEvidenceStateDao().updateLifecycle(0L, null, 3_002L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_evidence_state SET deleted_source_event_high_water_ordinal = ? WHERE id = 1",
			arrayOf<Any>(wal.admissionOrdinal))
		assertEquals(false, lifecycle.recoverMissingForRetainedEvent(EVENT_ID.value))
		assertEquals(0L, database.sessionSegmentDao().countTotal())
		assertEquals(0L, database.locationSampleDao().countAll())
	}

	@Test
	fun `missing presentation recovery cannot lend another run identity or materialize a live start`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val unbound = removeFixturePresentation()
		val lifecycle = SessionPresentationLifecycle(database)
		database.sourceSessionDao().updateServiceRun(unbound.copy(logicalTrackingId = "another-logical"))
		assertEquals(false, lifecycle.recoverMissingForRetainedEvent(EVENT_ID.value))
		database.sourceSessionDao().updateServiceRun(unbound.copy(state = "STARTING", completedAtMs = null))
		assertEquals(false, lifecycle.recoverMissingForRetainedEvent(EVENT_ID.value))
		assertEquals(0L, database.sessionSegmentDao().countTotal())
	}

	private suspend fun removeFixturePresentation(): SourceServiceRunEntity {
		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ID))
		val unbound = run.copy(state = "FAILED", completionReason = "INITIALIZATION_FAILURE", sessionSegmentId = null,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING, presentationAcknowledgedAtMs = null)
		database.sourceSessionDao().updateServiceRun(unbound)
		database.sessionSegmentDao().deleteById(requireNotNull(run.sessionSegmentId))
		return unbound
	}

	@Test
	fun `later lifecycle lease cannot lend its generation to a different original authorization`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val sessions = database.sourceSessionDao()
		sessions.updateServiceRun(requireNotNull(sessions.serviceRun(RUN_ID)).copy(leaseGeneration = 3L))
		sessions.updateSession(requireNotNull(sessions.session(LOGICAL_ID)).copy(lifecycleLeaseGeneration = 3L))
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val mismatched = original.copy(lifecycleLeaseGeneration = 2L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET lifecycle_lease_generation = ?, integrity_identity = ? WHERE event_id = ?",
			arrayOf<Any>(2L, mismatched.calculatedIntegrityIdentity(), original.eventId))

		val rejected = assertIs<LocationWalAdapterResult.Rejected>(subject.qualify(EVENT_ID))

		assertEquals(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH, rejected.reason)
		assertEquals(0L, database.locationSampleDao().countAll())
	}

	@Test
	fun `retained Location tail survives unchanged capture grant across manifest refreshes`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		installCaptureRefresh(2L, 400L)
		installCaptureRefresh(3L, 450L)
		installProtectedHandoffAuthority(original.admissionOrdinal)
		val projectionId = ProtectedLocationCanonicalHandoff.WRITER_ID
		val projectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION
		database.sourceProjectionStateDao().saveFailure(SourceProjectionFailureEntity(
			projectionId, projectionVersion, original.admissionOrdinal, 1,
			"LOCATION_ADAPTER_AUTHORIZATION_MISMATCH", terminal = true, lastAttemptAtMs = 2_000L))

		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val command = assertIs<LocationObservationQualification.Qualified>(evaluated.qualification).command
		val authority = command.productEffect.durableEvidence.capturedAuthority
		assertEquals(AUTHORIZATION_REVISION, authority.authorizationRevision)
		assertEquals(MANIFEST_REVISION, authority.sessionManifestRevision)
		assertEquals(LEASE_GENERATION, authority.lifecycleLeaseGeneration)
		assertEquals(REGISTRATION_END_NANOS, authority.temporalAuthority.endExclusiveNanos)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			ProtectedLocationCanonicalHandoff(database, subject, writer).drainHighWater())

		assertEquals(original.admissionOrdinal, complete.lastCommittedOrdinal)
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(null, database.sourceProjectionStateDao().failure(projectionId, projectionVersion,
			original.admissionOrdinal))
		assertSavedWalUnchanged(original)
	}

	@Test
	fun `retained Location tail cannot cross a changed capture consent`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installCaptureRefresh(2L, 400L, consentEpoch = CONSENT_EPOCH + 1L)

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `retained Location tail cannot borrow a different source policy`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installCaptureRefresh(2L, 400L, policyRevision = POLICY_REVISION + 1L)

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `later capture grant cannot bridge an intervening authorization denial`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installCaptureRefresh(2L, 400L, captureAuthorized = false)
		installCaptureRefresh(3L, 450L)

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `retained Location tail cannot bridge a manifest with Location removed`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installCaptureRefresh(2L, 400L, includeLocation = false)
		installCaptureRefresh(3L, 450L)

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MANIFEST_MISMATCH),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `metadata refresh does not extend Location collection beyond End`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installCaptureRefresh(2L, 400L)
		val sessions = database.sourceSessionDao()
		sessions.updateSession(requireNotNull(sessions.session(LOGICAL_ID)).copy(cutoffElapsedNanos = OBSERVED_NANOS))

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `authenticated post End tail settles without saving a point or deleting its WAL`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installRecordedEnd()
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		installProtectedHandoffAuthority(original.admissionOrdinal)
		database.sourceProjectionStateDao().saveFailure(SourceProjectionFailureEntity(
			ProtectedLocationCanonicalHandoff.WRITER_ID, ProtectedLocationCanonicalHandoff.WRITER_VERSION,
			original.admissionOrdinal, 3, "LOCATION_ADAPTER_AUTHORIZATION_MISMATCH",
			terminal = true, lastAttemptAtMs = 2_000L))
		val writer = mockk<ProtectedLocationCanonicalWriter>()
		coEvery { writer.retryPendingCleanup() } returns true

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.AFTER_SESSION_CUTOFF),
			subject.qualify(EVENT_ID))
		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			ProtectedLocationCanonicalHandoff(database, subject, writer).drainHighWater())

		assertEquals(original.admissionOrdinal, complete.lastCommittedOrdinal)
		assertEquals(1, complete.lifecycleSettled)
		assertEquals(0L, database.locationSampleDao().countAll())
		assertEquals(null, database.locationObservationDao().getBySourceEventId(EVENT_ID.value))
		assertEquals(null, database.locationObservationDecisionDao().getBySourceEventId(EVENT_ID.value))
		assertEquals(null, database.sourceProjectionStateDao().failure(ProtectedLocationCanonicalHandoff.WRITER_ID,
			ProtectedLocationCanonicalHandoff.WRITER_VERSION, original.admissionOrdinal))
		assertSavedWalUnchanged(original)
	}

	@Test
	fun `post End capture rejection preserves an independently authorized all-day point`() = roomTest {
		val (_, writer) = rejectedSessionWriterFixture()
		installRecordedEnd(denyAtNanos = null)
		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.AFTER_SESSION_CUTOFF),
			subject.qualify(EVENT_ID))
		val ambient = assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID)).command
		database.sourceProjectionStateDao().saveFailure(SourceProjectionFailureEntity(
			ProtectedLocationCanonicalHandoff.WRITER_ID, ProtectedLocationCanonicalHandoff.WRITER_VERSION,
			ambient.wal.admissionOrdinal, 1, "LOCATION_ADAPTER_AUTHORIZATION_MISMATCH",
			terminal = true, lastAttemptAtMs = 2_000L))

		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			ProtectedLocationCanonicalHandoff(database, subject, writer).drainHighWater())

		assertEquals(1, complete.acceptedSamplesCommitted)
		assertEquals(true, database.readLocationAmbientReceipt(ambient))
		assertEquals(1L, database.locationSampleDao().countAll())
		val receipt = requireNotNull(database.locationPurposeDao().receiptByEvent(EVENT_ID.value))
		assertEquals(listOf(SourceBrokerPurpose.AMBIENT_PRODUCT),
			database.locationPurposeDao().purposes(receipt.sampleId, 3).map { it.purpose })
	}

	@Test
	fun `saved End rejects its late point while the physical listener still has its old grant`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installRecordedEnd(denyAtNanos = null)

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.AFTER_SESSION_CUTOFF),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `post End refreshed capture tail settles before a later independent run saves its point`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val valid = insertIndependentLocationRun(original)
		val ended = bindEventToPostEndRefresh(original)
		installProtectedHandoffAuthority(ended.admissionOrdinal)
		database.sourceSessionDao().saveCompleteness(requireNotNull(database.sourceSessionDao()
			.completenessForServiceRun(LOGICAL_ID, RUN_ID).singleOrNull()).copy(logicalTrackingId = requireNotNull(valid.logicalTrackingId),
			serviceRunId = requireNotNull(valid.serviceRunId), sourceInstanceId = valid.sourceInstanceId,
			registrationGeneration = valid.registrationGeneration, lastAdmissionOrdinal = valid.admissionOrdinal,
			lastSourceSequence = valid.sourceSequence))
		val projection = database.sourceProjectionStateDao()
		projection.saveFailure(SourceProjectionFailureEntity(ProtectedLocationCanonicalHandoff.WRITER_ID,
			ProtectedLocationCanonicalHandoff.WRITER_VERSION, ended.admissionOrdinal, 1,
			"LOCATION_ADAPTER_SESSION_MISMATCH", terminal = true, lastAttemptAtMs = 2_000L))
		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.AFTER_SESSION_CUTOFF),
			subject.qualify(EVENT_ID))
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(SourceEventId(valid.eventId)))
		assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer)

		val result = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainHighWater())
		assertEquals(valid.admissionOrdinal, result.lastCommittedOrdinal)
		assertEquals(1, result.lifecycleSettled)
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(null, database.locationObservationDao().getBySourceEventId(ended.eventId))
		assertTrue(database.locationObservationDao().getBySourceEventId(valid.eventId) != null)
		assertSavedWalUnchanged(ended)
		assertSavedWalUnchanged(valid)
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainHighWater())
		assertEquals(1L, database.locationSampleDao().countAll())
	}

	@Test
	fun `post End settlement cannot use a corrupt Stop checksum or another boot`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installRecordedEnd(bootId = "other-boot")
		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID))
		installRecordedEnd(denyAtNanos = null)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_lifecycle_intent_version SET intent_checksum = 'corrupt' WHERE intent_revision = 2")

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `post End refreshed capture cannot settle with an unknown Stop clock or checksum`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val ended = bindEventToPostEndRefresh(original)
		installProtectedHandoffAuthority(ended.admissionOrdinal)
		val writer = mockk<ProtectedLocationCanonicalWriter>()
		coEvery { writer.retryPendingCleanup() } returns true
		installRecordedEnd(denyAtNanos = null, bootId = "other-boot")
		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SESSION_MISMATCH),
			subject.qualify(EVENT_ID))
		installRecordedEnd(denyAtNanos = null)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_lifecycle_intent_version SET intent_checksum = 'corrupt' WHERE intent_revision = 2")

		val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
			ProtectedLocationCanonicalHandoff(database, subject, writer).drainHighWater())
		assertEquals(ended.admissionOrdinal - 1L, failed.lastCommittedOrdinal)
		assertEquals(0L, database.locationSampleDao().countAll())
		assertSavedWalUnchanged(ended)
	}

	@Test
	fun `exact observed authorization denial settles without borrowing a later End`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installRecordedEnd(cutoffNanos = 700L, denyAtNanos = 400L)

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.AFTER_AUTHORIZATION_DENIAL),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `denied Location tail retains its rejection while a later independent run saves its point`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installRecordedEnd(cutoffNanos = 700L, denyAtNanos = 400L)
		val denied = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val valid = insertIndependentLocationRun(denied)
		installProtectedHandoffAuthority(denied.admissionOrdinal)
		database.sourceSessionDao().saveCompleteness(requireNotNull(database.sourceSessionDao()
			.completenessForServiceRun(LOGICAL_ID, RUN_ID).singleOrNull()).copy(logicalTrackingId = requireNotNull(valid.logicalTrackingId),
			serviceRunId = requireNotNull(valid.serviceRunId), sourceInstanceId = valid.sourceInstanceId,
			registrationGeneration = valid.registrationGeneration, lastAdmissionOrdinal = valid.admissionOrdinal,
			lastSourceSequence = valid.sourceSequence))
		val projection = database.sourceProjectionStateDao()
		projection.saveFailure(SourceProjectionFailureEntity(ProtectedLocationCanonicalHandoff.WRITER_ID,
			ProtectedLocationCanonicalHandoff.WRITER_VERSION, denied.admissionOrdinal, 1,
			"LOCATION_ADAPTER_AUTHORIZATION_MISMATCH", terminal = true, lastAttemptAtMs = 2_000L))
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(SourceEventId(valid.eventId)))
		assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer)

		val result = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainHighWater())
		assertEquals(valid.admissionOrdinal, result.lastCommittedOrdinal)
		assertEquals(1, result.lifecycleSettled)
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(null, database.locationObservationDao().getBySourceEventId(denied.eventId))
		assertTrue(database.locationObservationDao().getBySourceEventId(valid.eventId) != null)
		assertEquals("LOCATION_ADAPTER_AFTER_AUTHORIZATION_DENIAL", projection.failure(
			ProtectedLocationCanonicalHandoff.WRITER_ID, ProtectedLocationCanonicalHandoff.WRITER_VERSION,
			denied.admissionOrdinal)?.failureCode)
		assertSavedWalUnchanged(denied)
		assertSavedWalUnchanged(valid)
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainHighWater())
		assertEquals(1L, database.locationSampleDao().countAll())
	}

	@Test
	fun `Location measured before denial remains qualified after delayed delivery`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installRecordedEnd(cutoffNanos = 700L, denyAtNanos = OBSERVED_NANOS + 1L)
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)
	}

	@Test
	fun `observed denial settles an original grant despite an intermediate metadata refresh`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installCaptureRefresh(2L, 300L)
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(LOCATION_SOURCE,
			REGISTRATION_GENERATION, 3L, emptyList(), BOOT_ID, 400L, 1_400L))

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.AFTER_AUTHORIZATION_DENIAL),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `unverifiable observed denial leaves the Location lane blocked`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installRecordedEnd(cutoffNanos = 700L, denyAtNanos = 400L)
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		installProtectedHandoffAuthority(original.admissionOrdinal)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_authorization SET authorization_fingerprint = 'unknown' WHERE authorization_revision = 2")
		val writer = mockk<ProtectedLocationCanonicalWriter>()
		coEvery { writer.retryPendingCleanup() } returns true

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.AUTHORIZATION_MISMATCH),
			subject.qualify(EVENT_ID))
		val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
			ProtectedLocationCanonicalHandoff(database, subject, writer).drainHighWater())
		assertEquals(original.admissionOrdinal - 1L, failed.lastCommittedOrdinal)
		assertEquals(0L, database.locationSampleDao().countAll())
		assertSavedWalUnchanged(original)
	}

	@Test
	fun `corrupt original Location identity remains blocked after a valid End`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		installRecordedEnd()
		database.openHelper.writableDatabase.execSQL("UPDATE source_event_wal SET integrity_identity = 'corrupt'")

		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.WAL_INTEGRITY_MISMATCH),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `late presentation close retains original End and exposes the real saved route`() = roomTest {
		writeNativeMovingSession()
		val segmentDao = database.sessionSegmentDao()
		val original = requireNotNull(segmentDao.getById(SEGMENT_ID))
		segmentDao.update(original.copy(endTimeMs = 45_000L, sampleCount = 0, distanceM = 0f))
		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ID))
		database.sourceSessionDao().updateServiceRun(run.copy(
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null))
		val rawBefore = database.sourceEventWalDao().eventsAfter(0L, 10)
		val pointsBefore = rawBefore.map { row -> requireNotNull(database.locationSampleDao()
			.getBySourceSignalId(ProtectedLocationCanonicalSignalIdentity.canonicalProduct(row.eventId))) }
		val history = realSavedLocationHistory()
		val measured = assertIs<SavedLocationSummaryRead.Snapshot>(
			history.readSavedLocationSummary(SavedLocationSummarySelection.Session(SEGMENT_ID)))
		assertEquals(2L, measured.summary.pointCount)
		assertTrue(requireNotNull(measured.summary.distanceMeters) > 1.0)
		assertTrue(database.tripDao().getBetween(0L, 50_000L).isEmpty())

		val binding = SessionPresentationBinding(LOGICAL_ID, RUN_ID, SEGMENT_ID)
		val closed = SessionPresentationLifecycle(database, history).closeExact(binding)

		assertEquals(original.copy(endTimeMs = 2_000L, sampleCount = 2,
			distanceM = requireNotNull(measured.summary.distanceMeters).toFloat()), closed)
		assertEquals(closed, segmentDao.getById(SEGMENT_ID))
		assertEquals(SEGMENT_ID, database.tripDao().getBetween(0L, 50_000L).single().id)
		assertEquals(rawBefore.map { it.eventId to it.integrityIdentity }, database.sourceEventWalDao()
			.eventsAfter(0L, 10).map { it.eventId to it.integrityIdentity })
		assertEquals(pointsBefore, rawBefore.map { row -> requireNotNull(database.locationSampleDao()
			.getBySourceSignalId(ProtectedLocationCanonicalSignalIdentity.canonicalProduct(row.eventId))) })
		assertEquals(2L, database.locationSampleDao().countAll())
	}

	@Test
	fun `retired interrupted Location drains admitted points without claiming complete acquisition`() = roomTest {
		val request = interruptedLocationDrain()
		val raw = database.sourceEventWalDao().eventsAfter(0L, 10)
		assertEquals(0L, database.locationSampleDao().countAll())
		assertFalse(request.memberships.single().appDrainComplete)
		assertEquals("PROCESS_RESTARTED", request.memberships.single().stopStatus)
		assertEquals(null, authenticateSourceProductDrainRequest(database, request))
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer)
		assertEquals("LOCATION_DRAIN_ENDPOINT_NOT_ACKNOWLEDGED", assertIs<ProtectedLocationCanonicalDrainResult.Deferred>(
			handoff.drainThrough(LOGICAL_ID, RUN_ID, request.sourceHighWaterAdmissionOrdinal)).reason)
		val router = RoomSourceProductDrainRouter(database, mockk(), mockk(), mockk(), mockk(), mockk(),
			ProtectedLocationCanonicalSourceDrain(handoff))

		val result = router.drainThrough(request)
		val drained = assertIs<SourceProductDrainResult.Complete>(result, result.toString())

		assertEquals(raw.last().admissionOrdinal, drained.lastMaterializedAdmissionOrdinal)
		assertEquals(2L, database.locationSampleDao().countAll())
		assertIs<SourceProductDrainResult.Complete>(router.drainThrough(request))
		assertEquals(raw.map { it.eventId to it.integrityIdentity }, database.sourceEventWalDao()
			.eventsAfter(0L, 10).map { it.eventId to it.integrityIdentity })
		val receipt = database.sourceSessionDao().rawRunRetirements(LOGICAL_ID, RUN_ID, LOCATION_SOURCE, 2)
			.single().validatedOrNull()
		assertEquals(SourceRunRetirementEntity.STATE_INTERRUPTED, receipt?.state)
		assertEquals(false, receipt?.appDrainComplete)
		assertEquals("PROVIDER_COMPLETENESS_UNOBSERVABLE", receipt?.providerCoverage)
		assertEquals(900_000_000L, database.sourceSessionDao().session(LOGICAL_ID)?.cutoffElapsedNanos)
	}

	@Test
	fun `interrupted Location requires the exact retired registration cutoff and retained endpoint`() = roomTest {
		val request = interruptedLocationDrain()
		val dao = database.sourceSessionDao()
		val receipt = requireNotNull(dao.rawRunRetirements(LOGICAL_ID, RUN_ID, LOCATION_SOURCE, 2)
			.single().validatedOrNull())
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val handoff = ProtectedLocationCanonicalHandoff(database, subject,
			ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
				newLocationPersistence(dispatchers, RoomPersistenceTransactor(database))))
		val drain = suspend {
			handoff.drainThrough(LOGICAL_ID, RUN_ID, request.sourceHighWaterAdmissionOrdinal, request)
		}
		val sql = database.openHelper.writableDatabase
		sql.execSQL("UPDATE provider_registration_generation SET status = 'ACTIVE'")
		assertNotNull(authenticateSourceProductDrainRequest(database, request))
		assertIs<ProtectedLocationCanonicalDrainResult.AuthorityChanged>(drain())
		sql.execSQL("UPDATE provider_registration_generation SET status = 'RETIRED'")
		dao.updateRunRetirement(receipt.copy(cutoffElapsedRealtimeNanos = receipt.cutoffElapsedRealtimeNanos - 1L))
		assertNotNull(authenticateSourceProductDrainRequest(database, request))
		assertIs<ProtectedLocationCanonicalDrainResult.AuthorityChanged>(drain())
		dao.updateRunRetirement(receipt.copy(leaseGeneration = receipt.leaseGeneration + 1L))
		assertNotNull(authenticateSourceProductDrainRequest(database, request))
		assertIs<ProtectedLocationCanonicalDrainResult.AuthorityChanged>(drain())
		dao.updateRunRetirement(receipt)
		assertEquals(null, authenticateSourceProductDrainRequest(database, request))
		val endpoint = requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(
			request.sourceHighWaterAdmissionOrdinal)).copy(sourceSequence = requireNotNull(receipt.lastSourceSequence) + 1L)
		sql.execSQL("UPDATE source_event_wal SET source_sequence = ?, integrity_identity = ? WHERE admission_ordinal = ?",
			arrayOf<Any>(endpoint.sourceSequence, endpoint.calculatedIntegrityIdentity(), endpoint.admissionOrdinal))
		assertNotNull(authenticateSourceProductDrainRequest(database, request))
		assertIs<ProtectedLocationCanonicalDrainResult.AuthorityChanged>(drain())
		assertEquals(0L, database.locationSampleDao().countAll())
	}

	@Test
	fun `real WAL handoff preserves capture policy after current policy changes`() = roomTest {
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
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
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
	fun `real WAL unknown commit reopens from receipt without rewriting product`() = roomTest {
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
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
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
	fun `real WAL crash-pending commands recover before replay`() = roomTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
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

	// Keep suspended recovery, cancellation cleanup debt and the successful retry under one lifecycle lease.
	@Suppress("LongMethod")
	@Test
	fun `offline cancellation during suspended recovery stops partial pipeline before lease release`() =
		roomTest {
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
			val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
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
			val draining = async {
				handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal)
			}

			recoveryEntered.await()
			draining.cancelAndJoin()

			assertEquals(1, database.pendingSignalDao().countAll())
			assertFalse(persistence.isPipelineActiveForPersistenceLifecycle())
			val liveAcquired = CompletableDeferred<Unit>()
			val liveWaiter = async {
				lifecycleLease.acquireLivePipeline().also {
					liveAcquired.complete(Unit)
				}
			}
			yield()
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
		roomTest {
			assertFallbackReceiptCancellation(cleanupFails = false)
		}

	@Test
	fun `fallback receipt cancellation retains failed cleanup for deterministic next-call retry`() =
		roomTest {
			assertFallbackReceiptCancellation(cleanupFails = true)
		}

	@Test
	fun `confirmed fallback commit waits for retained cleanup before handoff advancement`() =
		roomTest {
			assertConfirmedFallbackCommitCleanupRetry()
		}

	@Test
	fun `real WAL owner loss blocks writer before pending acknowledgement`() = roomTest {
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
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
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
	fun `real WAL deletion settlement advances without resurrecting product`() = roomTest {
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
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
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
	fun `real WAL chain rejects conflicting preexisting decision before receipt`() = roomTest {
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
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
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

	// Keep the original canonical write, conflicting sample injection and blocked replay receipt together.
	@Suppress("LongMethod")
	@Test
	fun `real WAL chain rejects conflicting preexisting derived sample before receipt`() = roomTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		val signalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(EVENT_ID.value)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
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
					arrayOf<Any?>(
						ProtectedLocationCanonicalHandoff.WRITER_ID,
						ProtectedLocationCanonicalHandoff.WRITER_VERSION,
					),
				)
				execSQL(
					"UPDATE source_product_projection_lane " +
						"SET contiguous_admission_ordinal = ? " +
						"WHERE source_kind = ? AND projection_id = ? " +
						"AND projection_version = ?",
					arrayOf<Any?>(
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
	fun `maximum canonical location payload qualifies through only SQL bounded blob reads`() = roomTest {
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
	fun `maximum modified UTF legacy provider remains mock provenance unverifiable`() = roomTest {
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
	fun `selected payload one byte beyond canonical maximum is rejected without loading a blob`() = roomTest {
		installValidFixture()
		replacePayloadWithZeroBlob(EVENT_ID.value, MAX_OPTIONAL_LOCATION_PAYLOAD_BYTES + 1)
		walPayloadQueries.clear()

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_TOO_LARGE),
			subject.qualify(EVENT_ID),
		)
		assertTrue(walPayloadQueries.isEmpty())
	}

	@Test
	fun `oversized required sibling is rejected before full delivery loading`() = roomTest {
		installValidFixture(
			deliveryPayloads = listOf(locationPayload(), locationPayload().copy(provider = "network")),
		)
		replacePayloadWithZeroBlob("location-event-sibling-1", MAX_OPTIONAL_LOCATION_PAYLOAD_BYTES + 1)
		walPayloadQueries.clear()

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_TOO_LARGE),
			subject.qualify(EVENT_ID),
		)
		assertOnlySelectedPayloadWasRead()
	}

	@Test
	fun `empty selected payload is malformed without loading a blob`() = roomTest {
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
	fun `empty required sibling is rejected before full delivery loading`() = roomTest {
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
	fun `maximum delivery cardinality qualifies without an unbounded sequence lookup`() = roomTest {
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
	fun `declared delivery overflow is rejected before full delivery loading`() = roomTest {
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
	fun `undeclared extra delivery member is discovered by cap plus one preflight`() = roomTest {
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
	fun `version two mock provenance participates in exact delivery identity`() = roomTest {
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
	fun `independent event and delivery queries preserve value identity before mock rejection`() = roomTest {
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
	fun `later mutable applied-plan pointer cannot replace exact historical plan binding`() = roomTest {
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
	fun `zero source sequence is the first durably allocated delivery`() = roomTest {
		installValidFixture(sourceSequence = 0L, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))

		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val qualified = assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)
		assertEquals(0L, qualified.command.productEffect.durableEvidence.sourceSequence)
	}

	@Test
	fun `source sequence at exhausted allocator sentinel fails closed`() = roomTest {
		installValidFixture(sourceSequence = Long.MAX_VALUE)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `next same-registration authorization may be sparse across global revisions`() = roomTest {
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
	fun `noncanonical WAL bytes fail before provider payload can reach qualifier`() = roomTest {
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
	fun `delivery identity must authenticate the complete canonical provider batch`() = roomTest {
		installValidFixture(deliveryIdentityOverride = "a".repeat(64))

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `full deletion high-water prevents dormant shadow qualification`() = roomTest {
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
	fun `desired plan whose exact bytes do not own registration fails closed`() = roomTest {
		val actualPlan = locationPlan(PLAN_REVISION)
		val differentPlan = actualPlan.copy(requestedIntervalMs = 2_000L)
		installValidFixture(plan = differentPlan, providerFingerprint = actualPlan.physicalConfigurationFingerprint())

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `complete canonical batch authenticates the exact selected indexed unit`() = roomTest {
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
	fun `missing middle sibling keeps the independently verified Location reading`() = roomTest {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = List(3) { locationPayload(false) },
			selectedUnitIndex = 2,
		)
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE delivery_identity = ? AND delivery_unit_index = 1",
			arrayOf(original.deliveryIdentity),
		)

		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)
		assertEquals(2L, database.sourceEventWalDao().countAll())
		assertSavedWalUnchanged(original)
		val sibling = requireNotNull(database.sourceEventWalDao().getByEventId("location-event-sibling-0"))
		val invalid = sibling.copy(deliveryUnitIndex = 3)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET delivery_unit_index = ?, integrity_identity = ? WHERE event_id = ?",
			arrayOf<Any>(3, invalid.calculatedIntegrityIdentity(), sibling.eventId))
		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MALFORMED_DELIVERY),
			subject.qualify(EVENT_ID))
	}

	@Test
	fun `late callback keeps and replays pre-End points when its post-End sibling was not admitted`() = roomTest {
		val delivery = installStoppingLocationDelivery()
		val lifecycle = mockk<CollectedDataLifecycleStore>()
		coEvery { lifecycle.snapshot() } returns CollectedDataLifecycleSnapshot(0, null)
		val startup = object : TrackingStartupGate {
			override val isReady = true
			override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0)
		}
		val ingress = RoomDurableSourceIngress(database, lifecycle, payloadCodec,
			ExecutableSourceLaneCatalog.explicit(ExecutableSourceLaneCatalog.LOCATION_CANONICAL_HANDOFF_V1),
			Provider { startup })
		val admitted = assertIs<DeliveryAdmissionResult.Admitted>(ingress.admit(delivery)).units
		assertEquals(listOf(0, 1), admitted.map { it.unitIndex })
		val retained = admitted.map { requireNotNull(database.sourceEventWalDao().getByEventId(it.eventId.value)) }
		for (wal in retained) {
			assertEquals(delivery.identity.value, wal.deliveryIdentity)
			assertEquals(3, wal.deliveryUnitCount)
			assertIs<LocationObservationQualification.Qualified>(
				assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(SourceEventId(wal.eventId))).qualification)
		}
		val last = retained.last()
		val sessions = database.sourceSessionDao()
		sessions.updateSession(requireNotNull(sessions.session(LOGICAL_ID)).copy(
			finalAdmissionOrdinal = last.admissionOrdinal))
		sessions.saveCompleteness(sessions.completenessForServiceRun(LOGICAL_ID, RUN_ID).single().copy(
			lastAdmissionOrdinal = last.admissionOrdinal, lastSourceSequence = last.sourceSequence))
		val projection = database.sourceProjectionStateDao()
		projection.saveFailure(SourceProjectionFailureEntity(ProtectedLocationCanonicalHandoff.WRITER_ID,
			ProtectedLocationCanonicalHandoff.WRITER_VERSION, retained.first().admissionOrdinal, 1,
			"LOCATION_ADAPTER_MALFORMED_DELIVERY", terminal = true, lastAttemptAtMs = 2_000L))
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer)

		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			handoff.drainThrough(LOGICAL_ID, RUN_ID, last.admissionOrdinal))
		assertEquals(last.admissionOrdinal, complete.lastCommittedOrdinal)
		assertEquals(2, complete.observationsCommitted)
		for (wal in retained) {
			assertNotNull(database.locationObservationDao().getBySourceEventId(wal.eventId))
			assertSavedWalUnchanged(wal)
		}
		assertEquals(null, projection.failure(ProtectedLocationCanonicalHandoff.WRITER_ID,
			ProtectedLocationCanonicalHandoff.WRITER_VERSION, retained.first().admissionOrdinal))
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			handoff.drainThrough(LOGICAL_ID, RUN_ID, last.admissionOrdinal))
		assertEquals(3L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `integrity-valid but malformed payload cannot be decoded as Location`() = roomTest {
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
	fun `manifest checksum mismatch fails before captured source authority is trusted`() = roomTest {
		installValidFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET qos_code = ? WHERE logical_tracking_id = ? " +
				"AND manifest_revision = ? AND source_kind = ?",
			arrayOf<Any?>(QOS_CODE - 1, LOGICAL_ID, MANIFEST_REVISION, LOCATION_SOURCE),
		)

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `service run and segment must preserve exact reverse binding`() = roomTest {
		installValidFixture(segmentRunId = "different-run")

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.SEGMENT_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `manifest stored zone must be a valid ZoneId`() = roomTest {
		installValidFixture(zoneId = "Not/AZone")

		assertEquals(
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.INVALID_STORED_ZONE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `retention floor rejects an uncertainty interval that can precede it`() = roomTest {
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
	fun `retention floor exactly at earliest possible wall preserves eligibility`() = roomTest {
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
			arrayOf<Any?>(byteCount, eventId),
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

	@Test
	fun `adaptive actual v2 receipt preserves separate requested capture plan`() = roomTest {
		val requested = locationPlan(PLAN_REVISION).copy(mode = LocationMode.BALANCED, adaptiveProfileVersion = 1)
		val actual = requested.copy(mode = LocationMode.HIGH_ACCURACY)
		installValidFixture(plan = requested, providerFingerprint = actual.physicalConfigurationFingerprint(), actualPlan = actual,
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION, deliveryPayloads = listOf(locationPayload(false)))
		val result = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertIs<LocationObservationQualification.Qualified>(result.qualification)
		assertEquals(LocationRequestPriority.HIGH_ACCURACY, result.acquisitionMetadata.requestPriority)
	}

	@Test
	fun `current boot ambient registration qualifies prior boot durable consent with v2 actual plan`() = roomTest {
		installValidFixture(plan = locationPlan(PLAN_REVISION).copy(adaptiveProfileVersion = 1), ambientOnly = true,
			priorBootPolicy = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val command = assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID)).command
		assertEquals(null, command.wal.logicalTrackingId)
		assertEquals(null, command.wal.serviceRunId)
		assertEquals(null, database.sourceSessionDao().session(LOGICAL_ID))
		assertEquals(CONSENT_EPOCH, command.authority.consentEpoch)
	}

	@Test
	fun `current boot capture registration qualifies prior boot durable capture consent`() = roomTest {
		installValidFixture(priorBootPolicy = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		assertIs<LocationObservationQualification.Qualified>(assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID)).qualification)
	}

	@Test
	fun `ambient Location preserves measured data after a provider wall clock change`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET accepted_at_ms = ? WHERE source_kind = ?",
			arrayOf<Any?>(RECEIVED_WALL_MS + 1L, LOCATION_SOURCE),
		)
		val qualified = assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID))
		assertEquals(OBSERVED_WALL_MS, qualified.command.wal.wallTimeMs)
		assertEquals(OBSERVED_NANOS, qualified.command.wal.observedElapsedNanos)
	}

	@Test
	fun `ambient Location cannot prove an open registration from a different boot`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET status = 'ACTIVE', retired_at_ms = NULL, " +
				"retired_elapsed_realtime_nanos = NULL WHERE source_kind = ?", arrayOf(LOCATION_SOURCE))
		val previousBootReader = LocationWalQualificationAdapter(database, payloadCodec, planCodec,
			BootClockDomainProvider { "different-boot" })
		assertEquals(LocationAmbientQualification.Rejected(LocationWalAdapterRejection.REGISTRATION_MISMATCH),
			previousBootReader.qualifyAmbient(EVENT_ID))
	}

	@Test
	fun `ambient writer commits once without session and legacy capture queries exclude its points`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val command = assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID)).command
		installProtectedHandoffAuthority(command.wal.admissionOrdinal)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationAmbientCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		assertEquals(ProtectedLocationCanonicalWriteResult.Committed, writer.write(command))
		assertEquals(ProtectedLocationCanonicalWriteResult.Committed, writer.write(command))
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(0, database.locationSampleDao().countBetween(0, Long.MAX_VALUE))
		assertEquals(1, database.locationPurposeDao().visiblePathChunk(0, Long.MAX_VALUE, null, null, 10).size)
		val receipt = requireNotNull(database.locationPurposeDao().receiptByEvent(EVENT_ID.value))
		val purpose = database.locationPurposeDao().purposes(receipt.sampleId, 3).single()
		assertTrue(com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(receipt))
		assertTrue(com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(purpose, receipt))
		assertEquals(SourceBrokerPurpose.AMBIENT_PRODUCT, purpose.purpose)
		assertEquals(null, purpose.zoneId)
		assertEquals(null, purpose.sessionSegmentId)
		assertEquals(null, database.sourceSessionDao().session(LOGICAL_ID))
		database.withTransaction {
			database.openHelper.writableDatabase.execSQL("UPDATE source_consent_epoch SET eligible = 0, persistence_eligible = 0 WHERE purpose = 'AMBIENT_PRODUCT'")
			database.removeLocationAmbientPurposeInCurrentTransaction()
		}
		assertEquals(0L, database.locationSampleDao().countAll())
		assertEquals(emptyList(), database.locationPurposeDao().visiblePathChunk(0, Long.MAX_VALUE, null, null, 10))
		assertIs<ProtectedLocationCanonicalWriteResult.Failed>(writer.write(command))
	}

	@Test
	fun `turning ambient Location off keeps its saved point and prior admitted WAL`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val command = assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID)).command
		installProtectedHandoffAuthority(command.wal.admissionOrdinal)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationAmbientCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		assertEquals(ProtectedLocationCanonicalWriteResult.Committed, writer.write(command))
		val (previous, previousConsent) = turnAmbientLocationOff()
		val policyDao = database.sourcePolicyDao()
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(1, database.locationPurposeDao().visiblePathChunk(0, Long.MAX_VALUE, null, null, 10).size)
		assertEquals(1, database.locationPurposeDao().savedFileChunk(0, Long.MAX_VALUE, null, null, 10).size)
		assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID))
		policyDao.insertPolicies(listOf(previous.copy(
			policyRevision = POLICY_REVISION + 2L,
			ambientPersistenceEligible = true,
			ambientConsentEpoch = CONSENT_EPOCH + 2L,
			effectiveElapsedRealtimeNanos = RECEIVED_NANOS + 2L,
			effectiveWallTimeMs = RECEIVED_WALL_MS + 2L,
			changeReason = "TEST_ON_AGAIN",
		)))
		policyDao.insertConsentEpochs(listOf(previousConsent.copy(
			epoch = CONSENT_EPOCH + 2L,
			policyRevision = POLICY_REVISION + 2L,
			effectiveElapsedRealtimeNanos = RECEIVED_NANOS + 2L,
			effectiveWallTimeMs = RECEIVED_WALL_MS + 2L,
			changeReason = "TEST_ON_AGAIN",
		)))
		assertEquals(1, policyDao.compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = POLICY_REVISION + 1L,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = POLICY_REVISION + 2L,
			legacySettingsFingerprint = null,
			updatedAtMs = RECEIVED_WALL_MS + 2L,
		))
		database.locationPurposeDao().insertRetentionAuthority(AmbientLocationRetentionAuthorityIntegrity.create(
			AmbientLocationRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, 3,
			AmbientLocationRetentionAuthorityEntity.STATE_ACTIVE, "location-retention",
			POLICY_REVISION + 2L, CONSENT_EPOCH + 2L, 0, BOOT_ID,
			RECEIVED_NANOS + 2L, RECEIVED_WALL_MS + 2L, null,
		))
		assertEquals(1, database.locationPurposeDao().visiblePathChunk(0, Long.MAX_VALUE, null, null, 10).size)
		assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID))
		val floor = requireNotNull(database.locationPurposeDao().receiptByEvent(EVENT_ID.value))
			.observedWallTimeMs + 1L
		database.withTransaction {
			database.sourceEvidenceStateDao().updateLifecycle(0, floor, floor)
			database.pruneLocationAmbientPurposeInCurrentTransaction(floor)
		}
		assertEquals(0L, database.locationSampleDao().countAll())
		assertEquals(emptyList(), database.locationPurposeDao().visiblePathChunk(0, Long.MAX_VALUE, null, null, 10))
		assertIs<LocationAmbientQualification.Rejected>(subject.qualifyAmbient(EVENT_ID))
	}

	@Test
	fun `pre admitted ambient Location first materializes after tracking is turned off`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val command = assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID)).command
		installProtectedHandoffAuthority(command.wal.admissionOrdinal)
		turnAmbientLocationOff()
		assertEquals(0L, database.locationSampleDao().countAll())
		val historical = assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID)).command
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationAmbientCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))

		assertEquals(ProtectedLocationCanonicalWriteResult.Committed, writer.write(historical))
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(1, database.locationPurposeDao().visiblePathChunk(0, Long.MAX_VALUE, null, null, 10).size)
	}

	@Test
	fun `one physical delivery preserves ambient-only old unit and dual-purpose fresh unit`() = roomTest {
		installValidFixture(mixedPurposes = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false), locationPayload(false)),
			observedTimes = listOf(500L, 1_000_000_500L), receivedNanos = 1_500_000_500L)
		// Keep both observations within the same historical registration and capture interval.
		database.openHelper.writableDatabase.execSQL("UPDATE provider_registration_generation SET retired_elapsed_realtime_nanos = 2000000000")
		database.openHelper.writableDatabase.execSQL("UPDATE source_demand SET retire_elapsed_realtime_nanos = 2000000000")
		database.openHelper.writableDatabase.execSQL("UPDATE logical_tracking_session SET cutoff_elapsed_nanos = 2000000000")
		val old = assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID)).command
		val freshId = SourceEventId("location-event-sibling-1")
		val fresh = assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(freshId)).command
		assertEquals(old.wal.deliveryIdentity, fresh.wal.deliveryIdentity)
		assertEquals(old.wal.authorizationRevision, fresh.wal.authorizationRevision)
		assertEquals(SourceBrokerPurpose.MASK_AMBIENT_PRODUCT, old.wal.authorizationPurposeEligibilityMask)
		assertEquals(null, old.wal.logicalTrackingId)
		assertEquals(SourceBrokerPurpose.MASK_AMBIENT_PRODUCT or SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			fresh.wal.authorizationPurposeEligibilityMask)
		assertEquals(LOGICAL_ID, fresh.wal.logicalTrackingId)
	}

	@Test
	fun `session end drains physical ambient prefix without attributing it to capture`() = roomTest {
		installValidFixture(mixedPurposes = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false), locationPayload(false)),
			observedTimes = listOf(500L, 501L), receivedNanos = 1_500_000_500L)
		val endpoint = requireNotNull(database.sourceEventWalDao().getByEventId("location-event-sibling-1"))
		installProtectedHandoffAuthority(endpoint.admissionOrdinal, activationOrdinal = 1L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE logical_tracking_session SET final_admission_ordinal = ?",
			arrayOf(endpoint.admissionOrdinal),
		)
		database.openHelper.writableDatabase.execSQL("UPDATE source_session_completeness SET last_source_sequence = 1")
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val ambientWriter = ProtectedLocationAmbientCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		val writer = object : ProtectedLocationCanonicalWriter {
			override suspend fun write(command: LocationCapturedFactCommand, acquisitionMetadata: LocationWalAcquisitionMetadata): ProtectedLocationCanonicalWriteResult =
				error("Ambient-only observations must never invoke capture persistence")
			override suspend fun writeAmbient(command: LocationAmbientFactCommand) = ambientWriter.write(command)
		}
		val result = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			ProtectedLocationCanonicalHandoff(database, subject, writer).drainThrough(LOGICAL_ID, RUN_ID, endpoint.admissionOrdinal))
		assertEquals(endpoint.admissionOrdinal, result.lastCommittedOrdinal)
		assertEquals(0, database.locationSampleDao().countBetween(0, Long.MAX_VALUE))
	}

	@Test
	fun `legacy requested plan does not borrow wider contract freshness from joined physical request`() = roomTest {
		val requested = locationPlan(PLAN_REVISION).copy(requestedIntervalMs = 10_000L, maximumBatchDelayMs = 50_000L)
		val physical = requested.copy(requestedIntervalMs = 1_000L, adaptiveProfileVersion = 1)
		installValidFixture(plan = requested, actualPlan = physical, providerFingerprint = physical.physicalConfigurationFingerprint(),
			captureMaximumAgeMs = 120_000L, receivedNanos = OBSERVED_NANOS + 60_000_000_000L,
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION, deliveryPayloads = listOf(locationPayload(false)))
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertEquals(LocationStaleReason.TOO_OLD, assertIs<LocationObservationQualification.Stale>(evaluated.qualification).reason)
	}


    @Test
    fun `version three capture WAL uses the canonical writer with unknown optional fields`() = roomTest {
        val payload = locationPayload(false).copy(horizontalAccuracyMeters = null, altitudeMeters = null,
            verticalAccuracyMeters = null, speedMetersPerSecond = null, bearingDegrees = null)
        installValidFixture(payloadVersion = LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION,
            deliveryPayloads = listOf(payload))
        val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value)).admissionOrdinal
        installProtectedHandoffAuthority(ordinal)
        val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
        val writer = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
            newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
        val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer)
        assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal))
        val sample = requireNotNull(database.locationSampleDao().getBySourceSignalId(
            ProtectedLocationCanonicalSignalIdentity.canonicalProduct(EVENT_ID.value)))
        assertEquals(null, sample.hAccM)
        assertEquals(null, sample.speedMps)
        assertEquals(500_870_000, sample.latE7)
        assertEquals(OBSERVED_WALL_MS, sample.timeMs)
        assertEquals("COARSE", sample.quality.name)
        assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal))
        assertEquals(1L, database.locationSampleDao().countAll())
    }

    @Test
    fun `version three background WAL saves unknown accuracy without needing a session`() = roomTest {
        val payload = locationPayload(false).copy(horizontalAccuracyMeters = null, altitudeMeters = null,
            verticalAccuracyMeters = null, speedMetersPerSecond = null, bearingDegrees = null)
        installValidFixture(ambientOnly = true, payloadVersion = LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION,
            deliveryPayloads = listOf(payload))
        val command = assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID)).command
        installProtectedHandoffAuthority(command.wal.admissionOrdinal)
        val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
        val writer = ProtectedLocationAmbientCanonicalWriter(context, database, dispatchers,
            newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
        assertEquals(ProtectedLocationCanonicalWriteResult.Committed, writer.write(command))
        val sample = database.locationPurposeDao().visiblePathChunk(0, Long.MAX_VALUE, null, null, 10).single().sample
        assertEquals(null, sample.hAccM)
        assertEquals(OBSERVED_WALL_MS, sample.timeMs)
        assertEquals(null, database.sourceSessionDao().session(LOGICAL_ID))
        assertEquals(ProtectedLocationCanonicalWriteResult.Committed, writer.write(command))
        assertEquals(1L, database.locationSampleDao().countAll())
    }


	@Test
	fun `final manual Location WAL keeps original manifest binding two`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)), captureWriterBinding = 2L)
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertEquals(2L, assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)
			.command.authority.writerBindingGeneration)
	}

	@Test
	fun `final automatic Location WAL qualifies the original explicit binding`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)), captureWriterBinding = 2L, sessionMode = "AUTOMATIC")
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertEquals(2L, assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)
			.command.authority.writerBindingGeneration)
	}

	@Test
	fun `unstamped manual Location WAL retains binding one`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertEquals(1L, assertIs<LocationObservationQualification.Qualified>(evaluated.qualification)
			.command.authority.writerBindingGeneration)
	}

	@Test
	fun `old protected binding does not borrow final automatic capability`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)), sessionMode = "AUTOMATIC")
		assertEquals(LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.MANIFEST_MISMATCH),
			subject.qualify(EVENT_ID))
		assertEquals(1L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `final automatic Location persists and replays exact binding two with the real writer`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)), captureWriterBinding = 2L, sessionMode = "AUTOMATIC")
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value)).admissionOrdinal
		installProtectedHandoffAuthority(ordinal, bindingGeneration = 2L)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val persistence = newLocationPersistence(dispatchers, RoomPersistenceTransactor(database))
		val handoff = ProtectedLocationCanonicalHandoff(database, subject,
			ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers, persistence))
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal))
		val decision = requireNotNull(database.locationObservationDecisionDao().getBySourceEventId(EVENT_ID.value))
		assertEquals(2L, decision.writerBindingGeneration)
		assertEquals(1L, decision.writerOwnerGeneration)
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(0, database.pendingSignalDao().countAll())
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val command = assertIs<LocationObservationQualification.Qualified>(evaluated.qualification).command
		assertIs<ProtectedLocationCanonicalReceipt.Complete>(database.withTransaction {
			database.readProtectedLocationCanonicalReceipt(command, evaluated.acquisitionMetadata)
		})
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal))
		assertEquals(decision, database.locationObservationDecisionDao().getBySourceEventId(EVENT_ID.value))
		assertEquals(1L, database.locationSampleDao().countAll())
	}

	@Test
	fun `final Location command cannot persist through an original active lane`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(true)), captureWriterBinding = 2L)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value)).admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val command = assertIs<LocationObservationQualification.Qualified>(evaluated.qualification).command
		assertFailsWith<IllegalStateException> { database.withTransaction {
			database.prepareProtectedLocationCanonicalCurationState(command,
				com.adsamcik.tracker.tracker.data.collection.LocationCanonicalCurationState.EMPTY,
				com.adsamcik.tracker.tracker.data.collection.LocationCanonicalCurationState.EMPTY,
				ProtectedLocationPreparedCanonicalOutput.fromSignal(command,
					command.toProtectedLocationMockRejectionSignal(evaluated.acquisitionMetadata,
						evaluated.acquisitionMetadata.policyTier, evaluated.acquisitionMetadata.policyName)))
		} }
		assertEquals(0L, database.locationSampleDao().countAll())
	}


	@Test
	fun `fresh atomic setup carries pending ambient originals into final binding and the real shared writer`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		seedLocationSetupRollout()
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val persistence = newLocationPersistence(dispatchers, RoomPersistenceTransactor(database))
		val lease = ExclusiveTrackingPersistenceLifecycleLease()
		val recovery = io.mockk.mockk<com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery>(relaxed = true)
		val setup = newLocationSetup(persistence, lease, recovery)
		assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Applied>(setup.ensureFinalWriter(2_000L))
		val final = requireNotNull(database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE))
		assertEquals(2L, final.bindingGeneration)
		assertEquals(3L, final.captureModeMask)
		assertEquals(original.admissionOrdinal, final.activationOrdinal)
		assertSavedWalUnchanged(original)
		assertEquals("lease-released", lease.tryWithOfflineLocationRecovery { "lease-released" })
		io.mockk.verify(exactly = 1) { recovery.requestProtectedLocationCanonicalDrain() }
		val ambientWriter = ProtectedLocationAmbientCanonicalWriter(context, database, dispatchers, persistence)
		val writer = object : ProtectedLocationCanonicalWriter {
			override suspend fun write(command: LocationCapturedFactCommand, acquisitionMetadata: LocationWalAcquisitionMetadata): ProtectedLocationCanonicalWriteResult =
				error("Sessionless originals must not become capture")
			override suspend fun writeAmbient(command: LocationAmbientFactCommand) = ambientWriter.write(command)
		}
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(ProtectedLocationCanonicalHandoff(database, subject, writer).drainHighWater())
		assertEquals(1L, database.locationSampleDao().countAll())
		val sample = requireNotNull(database.locationPurposeDao().receiptByEvent(EVENT_ID.value))
		val purposes = database.locationPurposeDao().purposes(sample.sampleId, 3)
		assertEquals(1, purposes.size)
		assertTrue(purposes.all { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT })
		assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.AlreadyApplied>(setup.ensureFinalWriter(2_001L))
		assertEquals(final.bindingGeneration, database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE)?.bindingGeneration)
	}

	@Test
	fun `atomic setup retires a no-capture inert shadow without discarding its ambient original`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		seedLocationSetupRollout(shadow = true)
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val persistence = newLocationPersistence(dispatchers, RoomPersistenceTransactor(database))
		val setup = newLocationSetup(persistence, ExclusiveTrackingPersistenceLifecycleLease())
		assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Applied>(setup.ensureFinalWriter(2_000L))
		val old = requireNotNull(database.sourceProjectionStateDao().productLane(LOCATION_SOURCE, 1L,
			ProtectedLocationCanonicalHandoff.WRITER_ID, ProtectedLocationCanonicalHandoff.WRITER_VERSION))
		assertEquals(SourceProductProjectionLaneEntity.STATUS_RETIRED, old.status)
		assertEquals(original.admissionOrdinal, old.captureAdmissionCutoffOrdinal)
		assertEquals(original.admissionOrdinal, old.contiguousAdmissionOrdinal)
		assertEquals(original.admissionOrdinal, database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE)?.activationOrdinal)
		assertSavedWalUnchanged(original)
		assertEquals(1L, database.sourceDestinationOwnerDao().get(LOCATION_SOURCE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION)?.ownerGeneration)
	}

	@Test
	fun `inert shadow with captured original stays pinned as repair debt and keeps sibling rollout unchanged`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		seedLocationSetupRollout(shadow = true)
		val before = requireNotNull(database.trackingRolloutStateDao().get()).toModel()
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val setup = newLocationSetup(newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
			ExclusiveTrackingPersistenceLifecycleLease())
		val blocked = assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Blocked>(setup.ensureFinalWriter(2_000L))
		assertEquals(com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionBlocker.SHADOW_CURSOR_BEHIND, blocked.blocker)
		val held = requireNotNull(database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE))
		assertEquals(1L, held.bindingGeneration)
		assertTrue(held.retentionRequired)
		assertEquals(0L, held.contiguousAdmissionOrdinal)
		assertSavedWalUnchanged(original)
		val after = requireNotNull(database.trackingRolloutStateDao().get()).toModel()
		for (kind in SourceKind.entries.filter { it != SourceKind.LOCATION }) {
			assertEquals(before.sourceOwners[kind], after.sourceOwners[kind])
			assertEquals(before.productProjectionStages[kind], after.productProjectionStages[kind])
			assertEquals(before.captureModeMasks[kind], after.captureModeMasks[kind])
		}
	}

	@Test
	fun `late dual-purpose original beyond retired cutoff keeps valid ambient without lending final capture authority`() = roomTest {
		installValidFixture(mixedPurposes = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)), captureWriterBinding = 1L)
		seedLocationSetupRollout()
		database.sourceProjectionStateDao().installProductLane(SourceProductProjectionLaneEntity(
			sourceKind = LOCATION_SOURCE, bindingGeneration = 1L, projectionId = ProtectedLocationCanonicalHandoff.WRITER_ID,
			projectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION, captureModeMask = 1L,
			productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL, activatedRolloutRevision = ROLLOUT_REVISION,
			activationOrdinal = 1L, contiguousAdmissionOrdinal = 0L, captureAdmissionCutoffOrdinal = 0L,
			retentionRequired = false, status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
			installedAtMs = 1_400L, updatedAtMs = 1_900L,
			terminalDisposition = SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN, terminalAtMs = 1_900L))
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value)).admissionOrdinal
		val original = assertIs<LocationObservationQualification.Qualified>(
			assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID)).qualification).command
		assertEquals(1L, original.authority.writerBindingGeneration)
		assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(EVENT_ID))
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val persistence = newLocationPersistence(dispatchers, RoomPersistenceTransactor(database))
		val setup = newLocationSetup(persistence, ExclusiveTrackingPersistenceLifecycleLease())
		assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Applied>(setup.ensureFinalWriter(2_000L))
		val ambientWriter = ProtectedLocationAmbientCanonicalWriter(context, database, dispatchers, persistence)
		var captureWrites = 0
		val writer = object : ProtectedLocationCanonicalWriter {
			override suspend fun write(command: LocationCapturedFactCommand, acquisitionMetadata: LocationWalAcquisitionMetadata): ProtectedLocationCanonicalWriteResult {
				captureWrites++; error("Retired original capture must not borrow final Location2")
			}
			override suspend fun writeAmbient(command: LocationAmbientFactCommand) = ambientWriter.write(command)
		}
		val handoff = ProtectedLocationCanonicalHandoff(database, subject, writer)
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainHighWater())
		assertEquals(0, captureWrites)
		val sample = database.locationSampleDao().getBySourceEventIdBounded(EVENT_ID.value, 2).singleOrNull()
		assertTrue(sample != null)
		val purposes = database.locationPurposeDao().purposes(requireNotNull(sample).id, limit = 3)
		assertEquals(1, purposes.size)
		assertEquals(SourceBrokerPurpose.AMBIENT_PRODUCT, purposes.single().purpose)
		assertEquals(null, purposes.single().logicalTrackingId)
		assertEquals(null, database.locationObservationDecisionDao().getBySourceEventId(EVENT_ID.value)?.writerBindingGeneration)
		assertEquals(ordinal, database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE)?.contiguousAdmissionOrdinal)
		assertEquals(2L, database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE)?.bindingGeneration)
		assertEquals(0L, database.sourceProjectionStateDao().productLane(LOCATION_SOURCE, 1L,
			ProtectedLocationCanonicalHandoff.WRITER_ID, ProtectedLocationCanonicalHandoff.WRITER_VERSION)?.contiguousAdmissionOrdinal)
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainHighWater())
		assertEquals(1L, database.locationSampleDao().countAll())
	}

	@Test
	fun `original canonical writer drains before final setup and its complete capture receipt remains readable`() = roomTest {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)), captureWriterBinding = 1L)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value)).admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		seedLocationSetupRollout(canonical = true, installOwnerOrLane = false)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val persistence = newLocationPersistence(dispatchers, RoomPersistenceTransactor(database))
		val lease = ExclusiveTrackingPersistenceLifecycleLease()
		val recovery = io.mockk.mockk<com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery>(relaxed = true)
		val setup = newLocationSetup(persistence, lease, recovery)
		val pending = assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Blocked>(setup.ensureFinalWriter(2_000L))
		assertEquals(com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionBlocker.ROLLBACK_CURSOR_BEHIND, pending.blocker)
		assertEquals(1L, database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE)?.bindingGeneration)
		io.mockk.verify(exactly = 1) { recovery.requestProtectedLocationCanonicalDrain() }
		val writer = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers, persistence, lease)
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(ProtectedLocationCanonicalHandoff(database, subject, writer).drainHighWater())
		val originalDecision = requireNotNull(database.locationObservationDecisionDao().getBySourceEventId(EVENT_ID.value))
		assertEquals(1L, originalDecision.writerBindingGeneration)
		assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Applied>(setup.ensureFinalWriter(2_001L))
		assertEquals(2L, database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE)?.bindingGeneration)
		val evaluated = assertIs<LocationWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val command = assertIs<LocationObservationQualification.Qualified>(evaluated.qualification).command
		assertIs<ProtectedLocationCanonicalReceipt.Complete>(database.withTransaction {
			database.readProtectedLocationCanonicalReceipt(command, evaluated.acquisitionMetadata) })
		assertEquals(originalDecision, database.locationObservationDecisionDao().getBySourceEventId(EVENT_ID.value))
		assertEquals(1L, database.locationSampleDao().countAll())
	}

	@Test
	fun `occupied offline cleanup returns Busy without setup mutation and permits an explicit retry`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		seedLocationSetupRollout()
		val before = requireNotNull(database.trackingRolloutStateDao().get())
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val lease = ExclusiveTrackingPersistenceLifecycleLease()
		val cleanup = lease.acquireOfflineLocationRecovery()
		val setup = newLocationSetup(newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)), lease)
		assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Busy>(setup.ensureFinalWriter(2_000L))
		assertEquals(before, database.trackingRolloutStateDao().get())
		assertEquals(null, database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE))
		cleanup.release()
		assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Applied>(setup.ensureFinalWriter(2_001L))
	}

	@Test
	fun `missing post-clear receipt cannot be inferred from a missing Location lane`() = roomTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 1L, updatedAtMs = 1L))
		database.sourceEvidenceStateDao().updateLifecycle(1L, null, 1L)
		seedLocationSetupRollout()
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val setup = newLocationSetup(newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
			ExclusiveTrackingPersistenceLifecycleLease())
		val result = assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Blocked>(setup.ensureFinalWriter(2_000L))
		assertEquals(com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionBlocker.FULL_DELETION_PROOF_UNAVAILABLE, result.blocker)
		assertEquals(null, database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE))
	}

	@Test
	fun `actual completed clear reuses final Location2 and permanent owner1 without resurrecting the old epoch`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		seedLocationSetupRollout()
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val owner = requireNotNull(database.sourceDestinationOwnerDao().get(LOCATION_SOURCE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION))
		AppDatabase.deleteAllCollectedDataForTesting(database, "atomic-location-clear", 1L, 2_000L, 2_000L)
		val lifecycle = io.mockk.mockk<com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore>()
		io.mockk.coEvery { lifecycle.snapshot() } returns
			com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot(1L, 2_000L)
		val ready = io.mockk.mockk<com.adsamcik.tracker.shared.base.startup.TrackingStartupGate>()
		io.mockk.every { ready.isReady } returns true
		val catalog = com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog()
		com.adsamcik.tracker.tracker.source.coordinator.SharedSourceWriterDeletionRearm(database, lifecycle,
			com.adsamcik.tracker.tracker.source.coordinator.StepsWriterDeletionRearm(
				com.adsamcik.tracker.tracker.source.coordinator.StepsWriterTransitionState(database, catalog),
				com.adsamcik.tracker.tracker.source.coordinator.StepsWriterTransitionRuntimeHooks.forTest(
					com.adsamcik.tracker.tracker.source.coordinator.StepsWriterTransitionTestDependencies(ready))),
			bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
		).rearmAfterFullDeletion("atomic-location-clear", 1L, 2_001L)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val setup = newLocationSetup(newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
			ExclusiveTrackingPersistenceLifecycleLease())
		assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Applied>(setup.ensureFinalWriter(3_000L))
		val final = requireNotNull(database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE))
		assertEquals(2L, final.bindingGeneration)
		assertEquals(3L, final.captureModeMask)
		assertEquals(original.admissionOrdinal + 1L, final.activationOrdinal)
		assertEquals(owner, database.sourceDestinationOwnerDao().get(LOCATION_SOURCE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION))
		assertEquals(1L, database.sourceEvidenceStateDao().get()?.collectedDataEpoch)
		assertEquals(original.admissionOrdinal, database.sourceEvidenceStateDao().get()?.deletedSourceEventHighWaterOrdinal)
		assertEquals(null, database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		assertEquals(0L, database.locationSampleDao().countAll())
		assertEquals(com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity.PHASE_WRITERS_REARMED,
			database.collectedDataDeletionOperationDao().get("atomic-location-clear")?.phase)
	}

	@Test
	fun `actual clear remains pending until its writer rearm receipt is completed`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		seedLocationSetupRollout()
		AppDatabase.deleteAllCollectedDataForTesting(database, "unfinished-location-clear", 1L, 2_000L, 2_000L)
		seedLocationSetupRollout(installOwnerOrLane = false)
		val before = requireNotNull(database.trackingRolloutStateDao().get())
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val setup = newLocationSetup(newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
			ExclusiveTrackingPersistenceLifecycleLease())
		val result = assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Blocked>(setup.ensureFinalWriter(3_000L))
		assertEquals(com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionBlocker.FULL_DELETION_PROOF_UNAVAILABLE, result.blocker)
		assertEquals(before, database.trackingRolloutStateDao().get())
		assertEquals(null, database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE))
		assertEquals(com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED,
			database.collectedDataDeletionOperationDao().get("unfinished-location-clear")?.phase)
	}

	@Test
	fun `pending legacy command keeps atomic Location setup blocked without consuming either queue`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		seedLocationSetupRollout()
		val pending = com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity(
			signalId = "unclassified-legacy-command", sessionId = 0L, signalJson = "{}", createdAt = 1_800L)
		database.pendingSignalDao().insertAll(listOf(pending))
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val setup = newLocationSetup(newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
			ExclusiveTrackingPersistenceLifecycleLease())
		val result = assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Blocked>(setup.ensureFinalWriter(2_000L))
		assertEquals(com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionBlocker.LEGACY_WRITER_NOT_QUIESCENT, result.blocker)
		assertEquals(1, database.pendingSignalDao().countAll())
		assertEquals(1L, database.sourceEventWalDao().countAll())
		assertEquals(null, database.sourceProjectionStateDao().activeProductLane(LOCATION_SOURCE))
	}

	@Test
	fun `unknown Location destination owner is preserved and cannot be overwritten by setup`() = roomTest {
		installValidFixture(ambientOnly = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		seedLocationSetupRollout()
		assertEquals(1, database.sourceDestinationOwnerDao().compareAndSetOwner(LOCATION_SOURCE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
			SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE, 1L, "unknown-location-owner", 2L, 1_901L))
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val setup = newLocationSetup(newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)),
			ExclusiveTrackingPersistenceLifecycleLease())
		val result = assertIs<com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult.Blocked>(setup.ensureFinalWriter(2_000L))
		assertEquals(com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT, result.blocker)
		assertEquals("unknown-location-owner", database.sourceDestinationOwnerDao().get(LOCATION_SOURCE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION)?.owner)
		assertEquals(1L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `actual Location setup processor mutex is finite and cancellation releases its recovery boundary`() = roomTest {
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val persistence = newLocationPersistence(dispatchers, RoomPersistenceTransactor(database))
		val entered = CompletableDeferred<Unit>()
		val held = async { persistence.withLocationWriterSetupQuiesced {
			entered.complete(Unit); awaitCancellation()
		} }
		kotlinx.coroutines.withTimeout(5_000L) { entered.await() }
		var secondEntered = false
		assertEquals(null, persistence.withLocationWriterSetupQuiesced { secondEntered = true; "unexpected" })
		assertTrue(!secondEntered)
		held.cancelAndJoin()
		assertEquals("released", persistence.withLocationWriterSetupQuiesced { "released" })
	}

	private suspend fun assertSavedWalUnchanged(original: SourceEventWalEntity) {
		val retained = requireNotNull(database.sourceEventWalDao().getByEventId(original.eventId))
		assertContentEquals(original.payload, retained.payload)
		assertEquals(original.copy(payload = retained.payload), retained)
	}

	private suspend fun seedLocationSetupRollout(shadow: Boolean = false, canonical: Boolean = false,
		installOwnerOrLane: Boolean = true,
	) {
		val contained = com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState.contained(revision = ROLLOUT_REVISION)
		val rollout = if (!canonical) {
			contained
		} else {
			contained.copy(
			sourceOwners = contained.sourceOwners + (SourceKind.LOCATION to com.adsamcik.tracker.tracker.source.coordinator.SourceOwner.EVENT),
			productProjectionStages = contained.productProjectionStages + (SourceKind.LOCATION to com.adsamcik.tracker.tracker.source.coordinator.ProductProjectionStage.EVENT_CANONICAL),
			captureModeMasks = contained.captureModeMasks + (SourceKind.LOCATION to 1L))
		}
		database.trackingRolloutStateDao().save(rollout.toEntity(updatedAtMs = 1_900L))
		if (!installOwnerOrLane) {
			return
		}
		database.sourceDestinationOwnerDao().insertIfAbsent(SourceDestinationOwnerEntity(LOCATION_SOURCE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
			SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE, 1L, 1_900L))
		if (shadow) {
			database.sourceProjectionStateDao().installProductLane(SourceProductProjectionLaneEntity(
			sourceKind = LOCATION_SOURCE, bindingGeneration = 1L, projectionId = ProtectedLocationCanonicalHandoff.WRITER_ID,
			projectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION, captureModeMask = 1L,
			productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW, activatedRolloutRevision = ROLLOUT_REVISION,
			activationOrdinal = 1L, contiguousAdmissionOrdinal = 0L, retentionRequired = true,
			status = SourceProductProjectionLaneEntity.STATUS_ACTIVE, installedAtMs = 1_900L, updatedAtMs = 1_900L))
		}
	}

	private fun newLocationSetup(persistence: PersistenceProcessor, lease: ExclusiveTrackingPersistenceLifecycleLease,
		recovery: com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery = io.mockk.mockk(relaxed = true),
	): com.adsamcik.tracker.tracker.source.coordinator.ProtectedLocationWriterSetupCoordinator {
		val ready = object : com.adsamcik.tracker.shared.base.startup.TrackingStartupGate {
			override val isReady: Boolean = true
			override val currentGeneration: Long = 1L
			override suspend fun reconcile(retryFailedStorage: Boolean): com.adsamcik.tracker.shared.base.startup.TrackingStartupResult =
				com.adsamcik.tracker.shared.base.startup.TrackingStartupResult.Ready(false, 0L)
		}
		val boundary = com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionBoundary.forTest(database,
			com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionTestDependencies(ready,
				BootClockDomainProvider { BOOT_ID }, com.adsamcik.tracker.shared.base.time.FixedClock(2_000L, 2_000L)))
		return com.adsamcik.tracker.tracker.source.coordinator.ProtectedLocationWriterSetupCoordinator(database,
			com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog(), boundary, lease,
			Provider { persistence }, Provider { subject }, Provider { recovery })
	}

	private suspend fun writeNativeMovingSession(drain: Boolean = true) {
		val cutoffNanos = 900_000_000L
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false),
				locationPayload(false).copy(latitudeDegrees = 50.0871)),
			captureWriterBinding = 1L,
			observedTimes = listOf(OBSERVED_NANOS, 250_000_500L),
			observedWallTimes = listOf(OBSERVED_WALL_MS, 1_750L),
			receivedNanos = 300_000_500L, receivedWallTimeMs = 1_800L)
		val sql = database.openHelper.writableDatabase
		sql.execSQL("UPDATE provider_registration_generation SET retired_elapsed_realtime_nanos = 800000000")
		sql.execSQL("UPDATE source_demand SET retire_elapsed_realtime_nanos = ?", arrayOf<Any>(cutoffNanos))
		val sessions = database.sourceSessionDao()
		sessions.updateSession(requireNotNull(sessions.session(LOGICAL_ID)).copy(cutoffElapsedNanos = cutoffNanos))
		val stop = requireNotNull(sessions.firstStopIntent(LOGICAL_ID))
		val checksum = SessionLifecycleIntegrity.checksum(LOGICAL_ID, stop.intentRevision, stop.manifestRevision,
			stop.desiredState, stop.stopReason, BOOT_ID, cutoffNanos, cutoffNanos)
		sql.execSQL("UPDATE session_lifecycle_intent_version SET requested_elapsed_realtime_nanos = ?, " +
			"stop_deadline_elapsed_realtime_nanos = ?, intent_checksum = ? WHERE intent_revision = 2",
			arrayOf<Any>(cutoffNanos, cutoffNanos, checksum))
		val rows = database.sourceEventWalDao().eventsAfter(0L, 10)
		val endpoint = rows.last()
		sessions.updateSession(requireNotNull(sessions.session(LOGICAL_ID)).copy(
			finalAdmissionOrdinal = endpoint.admissionOrdinal))
		installProtectedHandoffAuthority(endpoint.admissionOrdinal, activationOrdinal = rows.first().admissionOrdinal)
		sql.execSQL("UPDATE source_session_completeness SET last_source_sequence = ?",
			arrayOf<Any>(endpoint.sourceSequence))
		if (!drain) {
			return
		}
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val writer = ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers,
			newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(ProtectedLocationCanonicalHandoff(
			database, subject, writer).drainThrough(LOGICAL_ID, RUN_ID, endpoint.admissionOrdinal))
	}

	private suspend fun interruptedLocationDrain(): SourceProductDrainRequest {
		writeNativeMovingSession(drain = false)
		val endpoint = database.sourceEventWalDao().eventsAfter(0L, 10).last()
		val dao = database.sourceSessionDao()
		val session = requireNotNull(dao.session(LOGICAL_ID))
		dao.updateSession(session.copy(state = "STOPPING", currentServiceRunId = RUN_ID, completedAtMs = null))
		dao.updateServiceRun(requireNotNull(dao.serviceRun(RUN_ID)).copy(state = "STOPPING", completedAtMs = null,
			runtimeAcknowledgement = "PENDING", presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null))
		val receipt = SourceRunRetirementEntity(LOGICAL_ID, RUN_ID, LOCATION_SOURCE, SOURCE_INSTANCE,
			REGISTRATION_GENERATION, "interrupted-location", 1, LEASE_GENERATION, 900_000_000L, 2_000L,
			SourceRunRetirementEntity.STATE_INTERRUPTED, null, endpoint.sourceSequence, endpoint.sourceSequence,
			endpoint.admissionOrdinal, 1L, 1L, endpoint.sourceSequence, "REMOVED", "NOT_REQUESTED",
			"PROVIDER_COMPLETENESS_UNOBSERVABLE", false, "PROCESS_RESTARTED", 2_000L)
		dao.insertRunRetirementIntent(receipt)
		dao.saveCompleteness(SourceSessionCompletenessEntity(LOGICAL_ID, RUN_ID, LOCATION_SOURCE, SOURCE_INSTANCE,
			REGISTRATION_GENERATION, endpoint.admissionOrdinal, endpoint.sourceSequence, false,
			"PROVIDER_COMPLETENESS_UNOBSERVABLE", "PROCESS_RESTARTED", 1L, endpoint.sourceSequence, 2_000L))
		val claim = SourceDrainRetirementClaim(SourceKind.LOCATION, SOURCE_INSTANCE, REGISTRATION_GENERATION,
			receipt.actionId, receipt.attemptCount, receipt.leaseGeneration, cleanupOnly = false)
		return assertIs<SourceProductDrainPlan.Ready>(buildSourceProductDrainPlan(database, LOGICAL_ID, RUN_ID,
			900_000_000L, 2_000L, endpoint.admissionOrdinal, SourceProductDrainAuthority(
				mapOf(LOCATION_SOURCE to dao.manifestSources(LOGICAL_ID, MANIFEST_REVISION)), listOf(claim))))
			.requests.single()
	}

	// stats:data is already runtime-only here; use its real finite Location reader without a module cycle.
	private fun realSavedLocationHistory(): TrackingHistoryRepository {
		val implementation = Class.forName(
			"com.adsamcik.tracker.stats.data.repository.DefaultTrackingHistoryRepository")
		val constructor = implementation.declaredConstructors.single {
			it.parameterTypes.size == 11 && it.parameterTypes[7] == BootClockDomainProvider::class.java
		}.apply { isAccessible = true }
		val arguments = constructor.parameterTypes.map { type ->
			when (type) {
				AppDatabase::class.java -> database
				CoroutineDispatcher::class.java -> Dispatchers.Unconfined
				BootClockDomainProvider::class.java -> BootClockDomainProvider { BOOT_ID }
				else -> mockkClass(type.kotlin, relaxed = true)
			}
		}.toTypedArray()
		return constructor.newInstance(*arguments) as TrackingHistoryRepository
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
		ambientOnly: Boolean = false,
		captureWriterBinding: Long? = null,
		sessionMode: String = "MANUAL",
		priorBootPolicy: Boolean = false,
		actualPlan: LocationPlan? = null,
		mixedPurposes: Boolean = false,
		observedTimes: List<Long> = List(deliveryPayloads.size) { OBSERVED_NANOS },
		receivedNanos: Long = RECEIVED_NANOS,
		captureMaximumAgeMs: Long = 1_000L,
		observedWallTimes: List<Long> = List(deliveryPayloads.size) { OBSERVED_WALL_MS },
	) {
		require(selectedUnitIndex in deliveryPayloads.indices)
		database.sourceEvidenceStateDao().ensure(evidenceState)
		insertPlan(plan)
		installLocationFixturePolicyAndSession(ambientOnly, mixedPurposes, priorBootPolicy,
			segmentRunId, zoneId, captureWriterBinding, sessionMode)
		val demands = locationFixtureDemands(ambientOnly, mixedPurposes, captureMaximumAgeMs)
		val authorization = installLocationFixtureRegistration(
			plan, actualPlan, providerFingerprint, ambientOnly || mixedPurposes, demands,
		)

		val deliveryIdentity = deliveryIdentityOverride ?: sourceDeliveryIdentity(
			canonicalLocationDelivery(deliveryPayloads, payloadVersion, observedTimes, wallTimeUncertaintyMs,
				observedWallTimes),
		).value
		val walRows = locationFixtureWalRows(
			deliveryPayloads = deliveryPayloads,
			payloadVersion = payloadVersion,
			selectedUnitIndex = selectedUnitIndex,
			declaredUnitCount = declaredUnitCount,
			deliveryIdentity = deliveryIdentity,
			providerFingerprint = providerFingerprint,
			authorization = authorization,
			sourceSequence = sourceSequence,
			observedTimes = observedTimes,
			receivedNanos = receivedNanos,
			receivedWallTimeMs = receivedWallTimeMs,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			ambientOnly = ambientOnly,
			mixedPurposes = mixedPurposes,
			captureMaximumAgeMs = demands.first().maximumAgeMs,
			observedWallTimes = observedWallTimes,
		)
		assertEquals(walRows.size, database.sourceEventWalDao().insertDeliveryUnits(walRows).size)
	}

	// One delivery keeps the original fixture values explicit; no extra mutable fixture model.
	@Suppress("LongParameterList")
	private fun locationFixtureWalRows(
		deliveryPayloads: List<LocationFixPayload>,
		payloadVersion: Int,
		selectedUnitIndex: Int,
		declaredUnitCount: Int,
		deliveryIdentity: String,
		providerFingerprint: String,
		authorization: List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity>,
		sourceSequence: Long,
		observedTimes: List<Long>,
		receivedNanos: Long,
		receivedWallTimeMs: Long?,
		wallTimeUncertaintyMs: Long,
		ambientOnly: Boolean,
		mixedPurposes: Boolean,
		captureMaximumAgeMs: Long,
		observedWallTimes: List<Long>,
	): List<SourceEventWalEntity> {
		return deliveryPayloads.mapIndexed { index, payload ->
			val encodedPayload = payloadCodec.encode(payload, payloadVersion)
			val captureEligible = !ambientOnly && receivedNanos - observedTimes[index] <= captureMaximumAgeMs * 1_000_000L
			val unsignedWal = SourceEventWalEntity(
				eventId = if (index == selectedUnitIndex) {
					EVENT_ID.value
				} else {
					"location-event-sibling-$index"
				},
				providerDedupKey = null,
				deliveryIdentity = deliveryIdentity,
				deliveryUnitIndex = index,
				deliveryUnitCount = declaredUnitCount,
				logicalTrackingId = LOGICAL_ID.takeIf { captureEligible },
				serviceRunId = RUN_ID.takeIf { captureEligible },
				sourceKind = LOCATION_SOURCE,
				sourceInstanceId = SOURCE_INSTANCE,
				registrationGeneration = REGISTRATION_GENERATION,
				physicalConfigurationFingerprint = providerFingerprint,
				authorizationRevision = AUTHORIZATION_REVISION,
				authorizationPurposeEligibilityMask = locationFixturePurposeMask(ambientOnly || mixedPurposes, captureEligible),
				authorizationFingerprint = authorization.first().authorizationFingerprint,
				sourceSequence = Math.addExact(sourceSequence, index.toLong()),
				configRevision = PLAN_REVISION,
				planAttribution = (if (captureEligible) {
					PlanAttribution.CAPTURED_REGISTRATION
				} else {
					PlanAttribution.RECEIVE_TIME_ONLY
				}).ordinal,
				clockDomainId = BOOT_ID,
				observedElapsedNanos = observedTimes[index],
				observedIntervalStartNanos = observedTimes[index],
				receivedElapsedNanos = receivedNanos,
				receivedWallTimeMs = receivedWallTimeMs,
				wallTimeMs = observedWallTimes[index],
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
				capturedCollectedDataEpoch = 0L,
				sourcePolicyRevision = POLICY_REVISION.takeIf { captureEligible },
				captureConsentEpoch = CONSENT_EPOCH.takeIf { captureEligible },
				sessionManifestRevision = MANIFEST_REVISION.takeIf { captureEligible },
				lifecycleLeaseGeneration = LEASE_GENERATION.takeIf { captureEligible },
				acquiredAtMs = observedWallTimes[index],
				qualityFlags = SourceQuality().toStableFlags(),
				qualityConfidence = null,
				payloadVersion = payloadVersion,
				payload = encodedPayload.bytes,
				payloadChecksum = encodedPayload.checksum,
				createdAtMs = 1_600L,
			)
			unsignedWal.copy(integrityIdentity = unsignedWal.calculatedIntegrityIdentity())
		}
	}

	private fun locationFixturePurposeMask(ambientGranted: Boolean, captureEligible: Boolean): Long =
		(if (ambientGranted) {
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT
		} else {
			0
		}) or (if (captureEligible) {
			SourceBrokerPurpose.MASK_SESSION_CAPTURE
		} else {
			0
		})

	// Keep the existing fixture choices explicit without a second configuration model.
	@Suppress("LongParameterList")
	private suspend fun installLocationFixturePolicyAndSession(
		ambientOnly: Boolean,
		mixedPurposes: Boolean,
		priorBootPolicy: Boolean,
		segmentRunId: String,
		zoneId: String,
		captureWriterBinding: Long?,
		sessionMode: String,
	) {
		installPolicyAndConsent(ambientOnly || mixedPurposes, priorBootPolicy)
		if (mixedPurposes) {
			val ambientConsent = requireNotNull(database.sourcePolicyDao().consentEpoch(LOCATION_SOURCE, SourceBrokerPurpose.AMBIENT_PRODUCT, CONSENT_EPOCH))
			database.sourcePolicyDao().insertConsentEpochs(listOf(ambientConsent.copy(purpose = SourceBrokerPurpose.SESSION_CAPTURE)))
		}
		if (!ambientOnly) {
			val segmentId = database.sessionSegmentDao().insert(segment(segmentRunId))
			assertEquals(SEGMENT_ID, segmentId)
			installSessionAndManifest(segmentId, zoneId, captureWriterBinding, sessionMode)
		}
	}

	private fun locationFixtureDemands(
		ambientOnly: Boolean,
		mixedPurposes: Boolean,
		captureMaximumAgeMs: Long,
	): List<SourceDemandEntity> {
		val demand = if (ambientOnly) {
			demand().copy(consumerId = "ambient:location",
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, logicalTrackingId = null, serviceRunId = null,
			manifestRevision = null, lifecycleLeaseGeneration = null, liveAmbientRetentionPolicyId = "location-retention",
			liveAmbientRetentionApprovalRevision = 1)
		} else {
			demand().copy(maximumAgeMs = captureMaximumAgeMs)
		}
		val demands = if (mixedPurposes) {
			listOf(demand, demand.copy(demandId = "ambient-demand", consumerId = "ambient:location",
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, logicalTrackingId = null, serviceRunId = null,
			manifestRevision = null, lifecycleLeaseGeneration = null, maximumAgeMs = 120_000L,
			liveAmbientRetentionPolicyId = "location-retention", liveAmbientRetentionApprovalRevision = 1))
		} else {
			listOf(demand)
		}
		return demands
	}

	private suspend fun installLocationFixtureRegistration(
		plan: LocationPlan,
		actualPlan: LocationPlan?,
		providerFingerprint: String,
		ambientGranted: Boolean,
		demands: List<SourceDemandEntity>,
	): List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity> {
		database.sourceBrokerDao().insertDemands(demands)
		database.sourceBrokerDao().insertRegistration(registration(providerFingerprint))
		val authorization = SourceBrokerAuthorization.rows(
			sourceKind = LOCATION_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			demands = demands,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = 1_050L,
		)
		database.sourceBrokerDao().insertAuthorizations(authorization)
		if (ambientGranted) {
			database.locationPurposeDao().insertRetentionAuthority(AmbientLocationRetentionAuthorityIntegrity.create(
			AmbientLocationRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT, 1, AmbientLocationRetentionAuthorityEntity.STATE_ACTIVE,
			"location-retention", POLICY_REVISION, CONSENT_EPOCH, 0, BOOT_ID, AUTHORIZATION_START_NANOS, 1_050, null))
		}
		(actualPlan ?: plan.takeIf { ambientGranted })?.let { actual ->
			val encoded = planCodec.encode(actual)
			database.locationPurposeDao().insertRegistrationPlanIfAbsent(LocationRegistrationPlanIntegrity.create(
				REGISTRATION_GENERATION, SOURCE_INSTANCE, PLAN_REVISION, AUTHORIZATION_REVISION,
				providerFingerprint, if (actual.adaptiveProfileVersion == null) {
					1
				} else {
					2
				}, encoded.checksum, encoded.bytes))
		}
		return authorization
	}

	private suspend fun CoroutineScope.rejectedSessionWriterFixture(): Pair<Long, ProtectedLocationCanonicalWriter> {
		installValidFixture(mixedPurposes = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value)).admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		// The session has an existing route anchor; independent all-day curation has none yet.
		val prior = LocationCanonicalCurationState.EMPTY.copy(lastAccepted = LocationCanonicalCurationPoint(
			provider = "gps", timeMs = OBSERVED_WALL_MS - 1, elapsedRealtimeNanos = OBSERVED_NANOS - 1,
			latitude = 50.087, longitude = 16.4208, accuracyM = 5f, altitudeM = null,
			verticalAccuracyM = null, speedMps = null, speedAccuracyMps = null,
			bearingDegrees = null, bearingAccuracyDegrees = null,
		))
		database.sourceProjectionStateDao().saveJoinState(SourceProjectionJoinStateEntity(
			ProtectedLocationCanonicalHandoff.WRITER_ID, ProtectedLocationCanonicalHandoff.WRITER_VERSION,
			"curation-state:$LOGICAL_ID:$RUN_ID", LOGICAL_ID, ordinal,
			ProtectedLocationCanonicalCurationStateCodec.VERSION,
			ProtectedLocationCanonicalCurationStateCodec.encode(prior), RECEIVED_WALL_MS,
		))
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val persistence = newLocationPersistence(dispatchers, RoomPersistenceTransactor(database))
		return ordinal to ProtectedLocationCanonicalWriterChain({ null },
			ProtectedLocationOfflineCanonicalWriter(context, database, dispatchers, persistence),
			ProtectedLocationAmbientCanonicalWriter(context, database, dispatchers, persistence))
	}

	private suspend fun assertAmbientLocationAdmissionWithRejectedCapture(rejection: String) {
		installValidFixture(mixedPurposes = true, payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		when (rejection) {
			"MISSING_MANIFEST" -> database.openHelper.writableDatabase.execSQL("DELETE FROM session_manifest_version")
			"SESSION_CUTOFF" -> {
				database.openHelper.writableDatabase.execSQL("UPDATE logical_tracking_session SET state = 'STOPPING', cutoff_elapsed_nanos = 499, cutoff_at_ms = 1499")
				database.openHelper.writableDatabase.execSQL("UPDATE source_service_run SET state = 'STOPPING'")
			}
		}
		database.sourceRegistrationStateDao().insertIfAbsent(SourceRegistrationStateEntity(
			LOCATION_SOURCE, "LOCATION_RUNTIME", SOURCE_INSTANCE, BOOT_ID, REGISTRATION_GENERATION,
			1, PLAN_REVISION, 0, RECEIVED_WALL_MS,
		))
		val lifecycle = mockk<CollectedDataLifecycleStore>()
		coEvery { lifecycle.snapshot() } returns CollectedDataLifecycleSnapshot(0, null)
		val startup = object : TrackingStartupGate {
			override val isReady = true
			override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0)
		}
		val ingress = RoomDurableSourceIngress(database, lifecycle, payloadCodec,
			ExecutableSourceLaneCatalog.explicit(ExecutableSourceLaneCatalog.LOCATION_CANONICAL_HANDOFF_V1), Provider { startup })
		val payload = locationPayload(false)
		val observed = OBSERVED_NANOS + 1
		val candidate = SourceEvidenceCandidate(providerDedupKey = null, logicalTrackingId = null, serviceRunId = null,
			source = SourceKind.LOCATION, sourceInstanceId = SourceInstanceId(SOURCE_INSTANCE), registrationGeneration = REGISTRATION_GENERATION,
			physicalConfigurationFingerprint = original.physicalConfigurationFingerprint, authorizationRevision = AUTHORIZATION_REVISION,
			registrationPurposeEligibilityMask = original.authorizationPurposeEligibilityMask, registrationEligibilityFingerprint = original.authorizationFingerprint,
			sourceSequence = 0, configRevision = PLAN_REVISION, planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = BOOT_ID, observedElapsedRealtimeNanos = observed, receivedElapsedRealtimeNanos = RECEIVED_NANOS,
			receivedWallTimeMs = RECEIVED_WALL_MS, wallTimeMs = OBSERVED_WALL_MS, wallTimeUncertaintyMs = 0,
			capturedCollectedDataEpoch = 0, acquiredAtMs = OBSERVED_WALL_MS, quality = SourceQuality(),
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION, payload = payload)
		val delivery = SourceDeliveryCandidate(sourceDeliveryIdentity(canonicalLocationDelivery(listOf(payload),
			LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION, listOf(observed))), listOf(SourceDeliveryUnit(0, candidate)))
		val admitted = assertIs<DeliveryAdmissionResult.Admitted>(ingress.admit(delivery)).units.single()
		assertIs<LocationAmbientQualification.Qualified>(subject.qualifyAmbient(admitted.eventId))
		if (rejection != "CLOSED_LANE") {
			val capture = subject.qualify(admitted.eventId)
			assertFalse(capture is LocationWalAdapterResult.Evaluated && capture.qualification is LocationObservationQualification.Qualified)
		}
		assertEquals(2L, database.sourceEventWalDao().countAll())
	}

	private suspend fun installStoppingLocationDelivery(): SourceDeliveryCandidate {
		installValidFixture(payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(false)))
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		installRecordedEnd(cutoffNanos = SESSION_END_NANOS)
		val sessions = database.sourceSessionDao()
		sessions.updateSession(requireNotNull(sessions.session(LOGICAL_ID)).copy(state = "STOPPING",
			currentServiceRunId = RUN_ID, completedAtMs = null, finalAdmissionOrdinal = null))
		sessions.updateServiceRun(requireNotNull(sessions.serviceRun(RUN_ID)).copy(state = "STOPPING",
			completedAtMs = null, completionReason = null, runtimeAcknowledgement = "PENDING",
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null))
		seedLocationSetupRollout(canonical = true)
		installProtectedHandoffAuthority(original.admissionOrdinal + 2L,
			activationOrdinal = original.admissionOrdinal + 1L)
		database.sourceRegistrationStateDao().insertIfAbsent(SourceRegistrationStateEntity(
			LOCATION_SOURCE, "LOCATION_RUNTIME", SOURCE_INSTANCE, BOOT_ID, REGISTRATION_GENERATION,
			1L, PLAN_REVISION, 0L, RECEIVED_WALL_MS))
		val observed = listOf(500L, 600L, 950L)
		val locations = observed.mapIndexed { index, nanos ->
			Location("gps").apply {
				latitude = 50.087 + index * 0.0001
				longitude = 16.4208
				accuracy = 5f
				time = OBSERVED_WALL_MS + index * 100L
				elapsedRealtimeNanos = nanos
			}
		}
		return locationDeliveryCandidate(locations, observed, 1_000L, RECEIVED_WALL_MS,
			LocationDeliveryAttribution(SourceInstanceId(SOURCE_INSTANCE), REGISTRATION_GENERATION,
				requireNotNull(original.physicalConfigurationFingerprint), AUTHORIZATION_REVISION,
				original.authorizationPurposeEligibilityMask,
				requireNotNull(original.authorizationFingerprint), PLAN_REVISION, BOOT_ID, 0L), approximate = false)
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
					payloadVersion = if (plan.adaptiveProfileVersion == null) {
						1
					} else {
						2
					},
					payload = encoded.bytes,
					payloadChecksum = encoded.checksum,
				),
			),
		)
	}

	private suspend fun insertIndependentLocationRun(original: SourceEventWalEntity): SourceEventWalEntity {
		val logicalId = "independent-location"
		val runId = "independent-location-run"
		copyIndependentLocationSession(logicalId, runId)
		val broker = database.sourceBrokerDao()
		val nextGeneration = original.registrationGeneration + 1L
		val nextInstance = "independent-location-instance"
		broker.insertRegistration(registration(requireNotNull(original.physicalConfigurationFingerprint)).copy(
			registrationGeneration = nextGeneration, sourceInstanceId = nextInstance))
		val nextDemand = demand().copy(demandId = "independent-location-demand", consumerId = "session:$logicalId",
			logicalTrackingId = logicalId, serviceRunId = runId)
		broker.insertDemands(listOf(nextDemand))
		val authorization = SourceBrokerAuthorization.rows(LOCATION_SOURCE, nextGeneration, AUTHORIZATION_REVISION,
			listOf(nextDemand), BOOT_ID, AUTHORIZATION_START_NANOS, 1_050L)
		broker.insertAuthorizations(authorization)
		val identity = sourceDeliveryIdentity(canonicalLocationDelivery(listOf(locationPayload(false)),
			LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION, listOf(700L)))
		val unsigned = original.copy(admissionOrdinal = 0, eventId = "independent-location-event",
			deliveryIdentity = identity.value, logicalTrackingId = logicalId, serviceRunId = runId,
			sourceInstanceId = nextInstance, registrationGeneration = nextGeneration,
			authorizationFingerprint = authorization.single().authorizationFingerprint,
			observedElapsedNanos = 700L, observedIntervalStartNanos = 700L, receivedElapsedNanos = 750L)
		database.sourceEventWalDao().insertDeliveryUnits(listOf(unsigned.copy(
			integrityIdentity = unsigned.calculatedIntegrityIdentity())))
		return requireNotNull(database.sourceEventWalDao().getByEventId(unsigned.eventId))
	}

	private suspend fun copyIndependentLocationSession(logicalId: String, runId: String) {
		val sessions = database.sourceSessionDao()
		val segmentId = database.sessionSegmentDao().insert(segment(runId).copy(id = 0, logicalTrackingId = logicalId))
		sessions.insertSession(requireNotNull(sessions.session(LOGICAL_ID)).copy(logicalTrackingId = logicalId,
			cutoffAtMs = 2_000L, cutoffElapsedNanos = SESSION_END_NANOS, finalAdmissionOrdinal = 2L))
		sessions.insertServiceRun(requireNotNull(sessions.serviceRun(RUN_ID)).copy(serviceRunId = runId,
			logicalTrackingId = logicalId, sessionSegmentId = segmentId, startDeliveryToken = "independent-location-start"))
		val sources = sessions.manifestSources(LOGICAL_ID, MANIFEST_REVISION).map { it.copy(logicalTrackingId = logicalId) }
		val manifest = requireNotNull(sessions.manifest(LOGICAL_ID, MANIFEST_REVISION)).copy(
			logicalTrackingId = logicalId, serviceRunId = runId, manifestChecksum = "")
		sessions.insertManifest(manifest.copy(manifestChecksum = SessionManifestIntegrity.compute(manifest, sources)))
		sessions.insertManifestSources(sources)
		val start = requireNotNull(sessions.lifecycleIntent(LOGICAL_ID, 1L)).copy(logicalTrackingId = logicalId,
			intentChecksum = SessionLifecycleIntegrity.checksum(logicalId, 1L, MANIFEST_REVISION,
				"ACTIVE", START_ORIGIN, BOOT_ID, RUN_START_NANOS, RUN_START_WALL_MS, null, null, null, null))
		val stop = requireNotNull(sessions.lifecycleIntent(LOGICAL_ID, 2L)).copy(logicalTrackingId = logicalId,
			requestedElapsedRealtimeNanos = SESSION_END_NANOS, requestedWallTimeMs = 2_000L,
			stopDeadlineElapsedRealtimeNanos = SESSION_END_NANOS,
			intentChecksum = SessionLifecycleIntegrity.checksum(logicalId, 2L, MANIFEST_REVISION,
				"FINALIZED", "USER_STOP", BOOT_ID, SESSION_END_NANOS, SESSION_END_NANOS))
		sessions.insertLifecycleIntent(start)
		sessions.insertLifecycleIntent(stop)
	}

	private suspend fun bindEventToPostEndRefresh(original: SourceEventWalEntity): SourceEventWalEntity {
		installRecordedEnd(denyAtNanos = null)
		installCaptureRefresh(2L, 450L)
		val authorization = database.sourceBrokerDao().authorizationRevision(LOCATION_SOURCE, REGISTRATION_GENERATION, 2L)
		val refreshed = original.copy(authorizationRevision = 2L,
			authorizationFingerprint = authorization.single().authorizationFingerprint,
			configRevision = 2L, sessionManifestRevision = 2L, lifecycleLeaseGeneration = 2L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_revision = ?, authorization_fingerprint = ?, config_revision = ?, " +
				"session_manifest_revision = ?, lifecycle_lease_generation = ?, integrity_identity = ? WHERE event_id = ?",
			arrayOf<Any>(2L, requireNotNull(refreshed.authorizationFingerprint), 2L, 2L, 2L,
				refreshed.calculatedIntegrityIdentity(), original.eventId))
		return requireNotNull(database.sourceEventWalDao().getByEventId(original.eventId))
	}

	private suspend fun installRecordedEnd(
		cutoffNanos: Long = 400L,
		denyAtNanos: Long? = cutoffNanos,
		bootId: String = BOOT_ID,
	) {
		val sessions = database.sourceSessionDao()
		val session = requireNotNull(sessions.session(LOGICAL_ID))
		val stop = requireNotNull(sessions.firstStopIntent(LOGICAL_ID))
		val deadline = cutoffNanos + 100L
		val checksum = SessionLifecycleIntegrity.checksum(LOGICAL_ID, stop.intentRevision, stop.manifestRevision,
			stop.desiredState, stop.stopReason, bootId, cutoffNanos, deadline)
		sessions.updateSession(session.copy(cutoffElapsedNanos = cutoffNanos, cutoffAtMs = 1_000L + cutoffNanos))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_lifecycle_intent_version SET request_boot_id = ?, requested_elapsed_realtime_nanos = ?, " +
				"requested_wall_time_ms = ?, stop_deadline_boot_id = ?, stop_deadline_elapsed_realtime_nanos = ?, " +
				"intent_checksum = ? WHERE logical_tracking_id = ? AND intent_revision = ?",
			arrayOf<Any>(bootId, cutoffNanos, 1_000L + cutoffNanos, bootId, deadline, checksum, LOGICAL_ID, stop.intentRevision))
		if (denyAtNanos != null) {
			database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(LOCATION_SOURCE,
				REGISTRATION_GENERATION, AUTHORIZATION_REVISION + 1L, emptyList(), BOOT_ID,
				denyAtNanos, 1_000L + denyAtNanos))
		}
	}

	private suspend fun installCaptureRefresh(
		revision: Long,
		effectiveNanos: Long,
		policyRevision: Long = POLICY_REVISION,
		consentEpoch: Long = CONSENT_EPOCH,
		captureAuthorized: Boolean = true,
		includeLocation: Boolean = true,
	) {
		val sessions = database.sourceSessionDao()
		val original = requireNotNull(sessions.manifest(LOGICAL_ID, MANIFEST_REVISION))
		val source = sessions.manifestSources(LOGICAL_ID, MANIFEST_REVISION).single().copy(
			manifestRevision = revision, consentEpoch = consentEpoch)
		val sources = listOf(source).takeIf { includeLocation }.orEmpty()
		val unsigned = original.copy(manifestRevision = revision, acquisitionPlanRevision = revision,
			sourcePolicyRevision = policyRevision, startOrigin = "POLICY_RECONCILIATION",
			effectiveElapsedRealtimeNanos = effectiveNanos, effectiveWallTimeMs = 1_000L + effectiveNanos,
			changeReason = "POLICY_RECONCILIATION", manifestChecksum = "")
		insertPlan(locationPlan(revision))
		sessions.insertManifest(unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources)))
		sessions.insertManifestSources(sources)
		sessions.updateServiceRun(requireNotNull(sessions.serviceRun(RUN_ID)).copy(
			desiredPlanRevision = revision, leaseGeneration = revision))
		sessions.updateSession(requireNotNull(sessions.session(LOGICAL_ID)).copy(
			desiredPlanRevision = revision, currentManifestRevision = revision, lifecycleLeaseGeneration = revision))
		val refreshed = demand().copy(demandId = "$DEMAND_ID-refresh-$revision", manifestRevision = revision,
			lifecycleLeaseGeneration = revision, sourcePolicyRevision = policyRevision, consentEpoch = consentEpoch,
			requestedElapsedRealtimeNanos = effectiveNanos, requestedAtMs = 1_000L + effectiveNanos)
		database.sourceBrokerDao().insertDemands(listOf(refreshed))
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(LOCATION_SOURCE,
			REGISTRATION_GENERATION, revision, listOf(refreshed).takeIf { captureAuthorized }.orEmpty(),
			BOOT_ID, effectiveNanos, 1_000L + effectiveNanos))
	}

	// Keep the Location policy authority, matching policy and consent epoch fixture installation in order.
	@Suppress("LongMethod")
	private suspend fun installPolicyAndConsent(ambientOnly: Boolean = false, priorBootPolicy: Boolean = false) {
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
					ambientPersistenceEligible = ambientOnly,
					captureConsentEpoch = CONSENT_EPOCH,
					controlConsentEpoch = null,
					ambientConsentEpoch = CONSENT_EPOCH.takeIf { ambientOnly },
					effectiveBootId = if (priorBootPolicy) {
						"previous-boot"
					} else {
						BOOT_ID
					},
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
					purpose = if (ambientOnly) {
						SourceBrokerPurpose.AMBIENT_PRODUCT
					} else {
						SourceBrokerPurpose.SESSION_CAPTURE
					},
					epoch = CONSENT_EPOCH,
					eligible = true,
					persistenceEligible = true,
					policyRevision = POLICY_REVISION,
					effectiveBootId = if (priorBootPolicy) {
						"previous-boot"
					} else {
						BOOT_ID
					},
					effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
					effectiveWallTimeMs = 1_000L,
					changeReason = "TEST",
				),
			),
		)
	}

	private suspend fun turnAmbientLocationOff(): Pair<SourcePolicyEntity, SourceConsentEpochEntity> {
		val policyDao = database.sourcePolicyDao()
		val previous = requireNotNull(policyDao.policyAtRevision(POLICY_REVISION, LOCATION_SOURCE))
		val previousConsent = requireNotNull(policyDao.consentEpoch(LOCATION_SOURCE,
			SourceBrokerPurpose.AMBIENT_PRODUCT, CONSENT_EPOCH))
		policyDao.insertPolicies(listOf(previous.copy(
			policyRevision = POLICY_REVISION + 1L,
			enabled = false,
			capturePersistenceEligible = false,
			captureConsentEpoch = null,
			ambientPersistenceEligible = false,
			ambientConsentEpoch = null,
			effectiveElapsedRealtimeNanos = RECEIVED_NANOS + 1L,
			effectiveWallTimeMs = RECEIVED_WALL_MS + 1L,
			changeReason = "TEST_OFF",
		)))
		policyDao.insertConsentEpochs(listOf(previousConsent.copy(
			epoch = CONSENT_EPOCH + 1L,
			eligible = false,
			persistenceEligible = false,
			policyRevision = POLICY_REVISION + 1L,
			effectiveElapsedRealtimeNanos = RECEIVED_NANOS + 1L,
			effectiveWallTimeMs = RECEIVED_WALL_MS + 1L,
			changeReason = "TEST_OFF",
		)))
		assertEquals(1, policyDao.compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = POLICY_REVISION,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = POLICY_REVISION + 1L,
			legacySettingsFingerprint = null,
			updatedAtMs = RECEIVED_WALL_MS + 1L,
		))
		assertIs<AmbientRadioRetentionAuthorityResult.Applied>(database.applyAmbientLocationRetentionDecision(
			AmbientRadioRetentionDecision.Revoke(
				scope = AmbientLocationRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				expectedCollectedDataEpoch = 0,
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = RECEIVED_NANOS + 1L,
				effectiveWallTimeMs = RECEIVED_WALL_MS + 1L,
				expectedPreviousApprovalRevision = 1,
			),
		))
		return previous to previousConsent
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

	private suspend fun installProtectedHandoffAuthority(
		admissionOrdinal: Long,
		bindingGeneration: Long = 1L,
		activationOrdinal: Long = admissionOrdinal,
	) {
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
				bindingGeneration = bindingGeneration,
				projectionId =
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
				projectionVersion =
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
				captureModeMask = requireNotNull(com.adsamcik.tracker.shared.base.database.data
					.SourceWriterGenerationContract.captureModeMaskForKnownBinding(bindingGeneration)),
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = ROLLOUT_REVISION,
				activationOrdinal = activationOrdinal,
				contiguousAdmissionOrdinal = activationOrdinal - 1L,
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
		guardProvider: Provider<ProtectedLocationCanonicalPersistenceGuard>? = null,
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
			protectedLocationCanonicalPersistenceGuardProvider = guardProvider ?: Provider { guard },
		)
	}

	// Keep the real fallback receipt cancellation, retained failure chain, cleanup retry and lease-release
	// assertions together.
	@Suppress("LongMethod")
	private suspend fun CoroutineScope.assertFallbackReceiptCancellation(cleanupFails: Boolean) {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val lifecycleLease = ExclusiveTrackingPersistenceLifecycleLease()
		val persistence = spyk(newLocationPersistence(dispatchers, RoomPersistenceTransactor(database)))
		val originalWriteFailure = IllegalStateException("original offline write failure")
		val cleanupRetryFailure = IllegalStateException("offline cleanup retry failure")
		var stopCalls = 0
		coEvery { persistence.onStop() } coAnswers {
			stopCalls++
			val failure = if (stopCalls == 1) {
				originalWriteFailure
			} else {
				cleanupRetryFailure
			}
			if (!cleanupFails || stopCalls == 2) {
				// Remove only the failure injection; the spy then runs the real suspending cleanup.
				clearMocks(persistence, answers = true, recordedCalls = false, childMocks = false)
			}
			throw failure
		}
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
		val propagatedCancellation = CompletableDeferred<CancellationException>()
		val draining = async {
			try {
				handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal)
			} catch (cancelled: CancellationException) {
				propagatedCancellation.complete(cancelled)
				throw cancelled
			}
		}
		fallbackReceiptEntered.await()

		draining.cancel()
		draining.join()
		assertFailsWith<CancellationException> { draining.await() }
		val cancellation = propagatedCancellation.await()
		assertTrue(cancellation.suppressed.isNotEmpty(), cancellation.failureChainForTest())
		assertTrue(cancellation.suppressed.any { suppressed ->
			generateSequence(suppressed) { it.cause }.any { it === originalWriteFailure }
		}, "Cancellation must retain the injected write failure: ${cancellation.failureChainForTest()}")

		if (cleanupFails) {
			assertTrue(database.pendingSignalDao().countAll() > 0)
			assertTrue(cancellation.suppressed.size >= 2)
			assertTrue(cancellation.suppressed.any { suppressed ->
				generateSequence(suppressed) { it.cause }.any { it === cleanupRetryFailure }
			}, "Cancellation must retain the injected cleanup failure: ${cancellation.failureChainForTest()}")
			val liveAcquired = CompletableDeferred<Unit>()
			val liveWaiter = async {
				lifecycleLease.acquireLivePipeline().also {
					liveAcquired.complete(Unit)
				}
			}
			yield()
			assertFalse(liveAcquired.isCompleted)
			liveWaiter.cancelAndJoin()

			val retry = handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal)
			val recovered = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(retry, "$retry")
			assertEquals(ordinal, recovered.lastCommittedOrdinal)
			assertEquals(0, database.pendingSignalDao().countAll())
		}
		val livePermit = withTimeoutOrNull(5_000L) { lifecycleLease.acquireLivePipeline() }
		assertNotNull(livePermit, "Retained offline cleanup must release its lifecycle permit").release()
	}

	private fun Throwable.failureChainForTest(): String =
		(listOf(this) + suppressed).joinToString("; ") { failure ->
			generateSequence(failure) { it.cause }.take(16).joinToString(" -> ") {
				"${it.javaClass.simpleName}: ${it.message}"
			}
		}

	// Keep the confirmed fallback commit failure and its subsequent cleanup retry/lease-release assertions
	// together.
	@Suppress("LongMethod")
	private suspend fun CoroutineScope.assertConfirmedFallbackCommitCleanupRetry() {
		installValidFixture(
			payloadVersion = LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
			deliveryPayloads = listOf(locationPayload(isMock = false)),
		)
		val ordinal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
			.admissionOrdinal
		installProtectedHandoffAuthority(ordinal)
		val dispatchers = TestDispatchersProvider(Dispatchers.Unconfined)
		val lifecycleLease = ExclusiveTrackingPersistenceLifecycleLease()
		val persistence = newLocationPersistence(
			dispatchers,
			RoomPersistenceTransactor(database),
		)
		val altitudeProcessor = spyk(AltitudeProcessor())
		var resetCalls = 0
		every { altitudeProcessor.reset() } answers {
			resetCalls++
			if (resetCalls == 1) {
				error("simulated committed cleanup failure")
			}
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
				evaluated.acquisitionMetadata,
			),
		)
		assertEquals(
			ordinal - 1L,
			database.sourceProjectionStateDao()
				.activeProductLane(SourceKind.LOCATION.stableCode)
				?.contiguousAdmissionOrdinal,
		)
		val liveAcquired = CompletableDeferred<Unit>()
		val liveWaiter = async {
			lifecycleLease.acquireLivePipeline().also {
				liveAcquired.complete(Unit)
			}
		}
		yield()
		assertFalse(liveAcquired.isCompleted)
		liveWaiter.cancelAndJoin()

		val retry = handoff.drainThrough(LOGICAL_ID, RUN_ID, ordinal)
		val recovered = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(retry, "$retry")
		assertEquals(ordinal, recovered.lastCommittedOrdinal)
		assertEquals(1L, database.locationSampleDao().countAll())
		assertEquals(
			ordinal,
			database.sourceProjectionStateDao()
				.activeProductLane(SourceKind.LOCATION.stableCode)
				?.contiguousAdmissionOrdinal,
		)
		val livePermit = withTimeoutOrNull(5_000L) { lifecycleLease.acquireLivePipeline() }
		assertNotNull(livePermit, "Retained offline cleanup must release its lifecycle permit").release()
	}

	// Keep one Location session/run, active and stop intents, capture membership and checksum-bound manifest
	// together.
	@Suppress("LongMethod")
	private suspend fun installSessionAndManifest(segmentId: Long, zoneId: String,
		captureWriterBinding: Long? = null, sessionMode: String = "MANUAL",
	) {
		val startOrigin = if (sessionMode == "AUTOMATIC") {
			"AUTOMATIC_BACKGROUND_START"
		} else {
			START_ORIGIN
		}
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = ROLLOUT_REVISION,
				startOrigin = startOrigin,
				clockDomainId = BOOT_ID,
				startedAtMs = RUN_START_WALL_MS,
				startedElapsedNanos = RUN_START_NANOS,
				cutoffAtMs = 2_000L,
				cutoffElapsedNanos = SESSION_END_NANOS,
				completedAtMs = 2_000L,
				finalAdmissionOrdinal = 1L,
				failureCode = null,
				sessionMode = sessionMode,
				currentManifestRevision = MANIFEST_REVISION,
				currentIntentRevision = 2L,
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
				startOrigin = startOrigin,
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
				startIsUserInitiated = sessionMode == "MANUAL",
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = 2_000L,
			),
		)
		val activeIntent = SessionLifecycleIntentVersionEntity(
			logicalTrackingId = LOGICAL_ID, intentRevision = 1L, manifestRevision = MANIFEST_REVISION,
			desiredState = "ACTIVE", startOrigin = startOrigin, requestBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = RUN_START_NANOS, requestedWallTimeMs = RUN_START_WALL_MS,
			automationEpoch = null, triggerId = null, triggerKind = null, triggerBootId = null,
			triggerObservedElapsedRealtimeNanos = null, triggerReceivedElapsedRealtimeNanos = null,
			triggerExpiresElapsedRealtimeNanos = null, stopReason = null, stopDeadlineBootId = null,
			stopDeadlineElapsedRealtimeNanos = null,
			intentChecksum = SessionLifecycleIntegrity.checksum(LOGICAL_ID, 1L, MANIFEST_REVISION,
				"ACTIVE", startOrigin, BOOT_ID, RUN_START_NANOS, RUN_START_WALL_MS, null, null, null, null),
		)
		val stopIntent = activeIntent.copy(
			intentRevision = 2L, desiredState = "FINALIZED", startOrigin = "POLICY_RECONCILIATION",
			requestedElapsedRealtimeNanos = SESSION_END_NANOS, requestedWallTimeMs = 2_000L,
			stopReason = "USER_STOP", stopDeadlineBootId = BOOT_ID,
			stopDeadlineElapsedRealtimeNanos = SESSION_END_NANOS,
			intentChecksum = SessionLifecycleIntegrity.checksum(LOGICAL_ID, 2L, MANIFEST_REVISION,
				"FINALIZED", "USER_STOP", BOOT_ID, SESSION_END_NANOS, SESSION_END_NANOS),
		)
		database.sourceSessionDao().insertLifecycleIntent(activeIntent)
		database.sourceSessionDao().insertLifecycleIntent(stopIntent)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = LOCATION_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION.takeIf { captureWriterBinding != null },
			writerOwner = SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE.takeIf { captureWriterBinding != null },
			writerOwnerGeneration = 1L.takeIf { captureWriterBinding != null },
			writerProjectionId = SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID.takeIf { captureWriterBinding != null },
			writerProjectionVersion = SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION.takeIf { captureWriterBinding != null },
			writerBindingGeneration = captureWriterBinding,
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = RUN_ID,
			sessionMode = sessionMode,
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = PLAN_REVISION,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = startOrigin,
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

	// Keep the explicit fixture binary layout for supported Location versions and optional fields.
	@Suppress("CyclomaticComplexMethod")
	private fun canonicalLocationDelivery(
		payloads: List<LocationFixPayload>,
		payloadVersion: Int = LEGACY_LOCATION_PAYLOAD_VERSION,
		observedTimes: List<Long> = List(payloads.size) { OBSERVED_NANOS },
		wallTimeUncertaintyMs: Long = 0L,
		observedWallTimes: List<Long> = List(payloads.size) { OBSERVED_WALL_MS },
	): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(0x4c4f4342)
				output.writeInt(payloadVersion)
				output.writeInt(payloads.size)
				payloads.forEachIndexed { index, payload ->
					val provider = payload.provider.encodeToByteArray()
					output.writeInt(provider.size)
					output.write(provider)
					output.writeLong(observedTimes[index])
					output.writeLong(if (wallTimeUncertaintyMs == 1L) {
						0L
					} else {
						observedWallTimes[index]
					})
					output.writeLong(java.lang.Double.doubleToRawLongBits(payload.latitudeDegrees))
					output.writeLong(java.lang.Double.doubleToRawLongBits(payload.longitudeDegrees))
                    if (payloadVersion >= LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION) {
                        output.writeBoolean(payload.horizontalAccuracyMeters != null)
                        payload.horizontalAccuracyMeters?.let { output.writeInt(java.lang.Float.floatToRawIntBits(it)) }
                    } else {
                        output.writeInt(java.lang.Float.floatToRawIntBits(requireNotNull(payload.horizontalAccuracyMeters)))
                    }
                    output.writeBoolean(payload.altitudeMeters != null)
                    payload.altitudeMeters?.let { output.writeLong(java.lang.Double.doubleToRawLongBits(it)) }
                    output.writeBoolean(payload.verticalAccuracyMeters != null)
                    payload.verticalAccuracyMeters?.let { output.writeInt(java.lang.Float.floatToRawIntBits(it)) }
                    output.writeBoolean(payload.speedMetersPerSecond != null)
                    payload.speedMetersPerSecond?.let { output.writeInt(java.lang.Float.floatToRawIntBits(it)) }
                    output.writeBoolean(payload.bearingDegrees != null)
                    payload.bearingDegrees?.let { output.writeInt(java.lang.Float.floatToRawIntBits(it)) }
					if (payloadVersion >= LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION) {
						output.writeBoolean(requireNotNull(payload.isMock))
					}
				}
			}
			bytes.toByteArray()
		}

	// These tests use native Room executors; virtual deadlines can expire before Room resumes.
	private fun roomTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
		try {
			block()
		} finally {
			coroutineContext.cancelChildren()
		}
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
		const val SOURCE_SEQUENCE = 0L
		const val LEGACY_LOCATION_PAYLOAD_VERSION = 1
		const val MAX_LOCATION_PAYLOAD_BYTES = 65_586
		const val MAX_OPTIONAL_LOCATION_PAYLOAD_BYTES = 65_587
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
