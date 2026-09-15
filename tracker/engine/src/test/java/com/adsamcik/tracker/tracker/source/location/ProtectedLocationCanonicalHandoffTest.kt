package com.adsamcik.tracker.tracker.source.location

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.LocationAcquisitionMode
import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.base.data.LocationRequestPriority
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.dao.recordFullDeletion
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.data.collection.LocationCanonicalCurationState
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass", "TooManyFunctions")
class ProtectedLocationCanonicalHandoffTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure()
		installCanonicalLane()
		installDrainEndpointAuthority(ADMISSION_ORDINAL)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `source WAL drains through canonical writer and exact production receipt`() = runTest {
		val command = command()
		insertWal(command)
		val writer = ReceiptWriter(database, accepted = true)
		val handoff = handoff(command, writer)

		val result = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			handoff.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL),
		)

		assertEquals(ADMISSION_ORDINAL, result.lastCommittedOrdinal)
		assertEquals(1, result.observationsCommitted)
		assertEquals(1, result.acceptedSamplesCommitted)
		assertEquals(1, writer.writeCount)
		val receipt = assertIs<ProtectedLocationCanonicalReceipt.Complete>(
			database.withTransaction {
				database.readProtectedLocationCanonicalReceipt(
					command,
					TEST_ACQUISITION_METADATA,
				)
			},
		)
		assertEquals(WALL_TIME_MS, receipt.observation.fixTimeMs)
		assertEquals(RECEIVED_WALL_TIME_MS, receipt.observation.receivedAtMs)
		assertEquals(5_000L, receipt.observation.deliveryAgeMs)
		assertEquals(
			ProtectedLocationCanonicalSignalIdentity.canonicalProduct(EVENT_ID),
			database.locationObservationDecisionDao()
				.getBySourceEventId(EVENT_ID)
				?.acceptedSampleSourceSignalId,
		)
	}

	@Test
	fun `unknown commit cancellation reopens from exact receipt without duplicate writer call`() =
		runTest {
			val command = command()
			insertWal(command)
			val writer = ReceiptWriter(
				database = database,
				accepted = true,
				afterCommit = { throw CancellationException("commit outcome unknown") },
			)
			val handoff = handoff(command, writer)

			assertFailsWith<CancellationException> {
				handoff.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL)
			}
			assertEquals(
				ADMISSION_ORDINAL - 1L,
				database.sourceProjectionStateDao()
					.activeProductLane(SourceKind.LOCATION.stableCode)
					?.contiguousAdmissionOrdinal,
			)

			val replay = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
				handoff.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL),
			)
			assertEquals(ADMISSION_ORDINAL, replay.lastCommittedOrdinal)
			assertEquals(1, writer.writeCount)
		}

	@Test
	fun `raw observation alone is deferred and never forged complete`() = runTest {
		val command = command()
		insertWal(command)
		val writer = ReceiptWriter(database, accepted = false, omitDecision = true)
		val result = handoff(command, writer)
			.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL)

		val deferred = assertIs<ProtectedLocationCanonicalDrainResult.Deferred>(result)
		assertEquals("LOCATION_CANONICAL_DECISION_PENDING", deferred.reason)
		assertEquals(ADMISSION_ORDINAL - 1L, deferred.lastCommittedOrdinal)
		assertNull(database.locationObservationDecisionDao().getBySourceEventId(EVENT_ID))
	}

	@Test
	fun `curation rejection commits observation and decision without alternate sample`() = runTest {
		val command = command()
		insertWal(command)
		val result = handoff(command, ReceiptWriter(database, accepted = false))
			.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL)

		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(result)
		assertEquals(1, complete.rejectedObservationsCommitted)
		assertEquals(0, complete.acceptedSamplesCommitted)
		assertNull(
			database.locationSampleDao().getBySourceSignalId(
				ProtectedLocationCanonicalSignalIdentity.canonicalProduct(EVENT_ID),
			),
		)
		assertEquals(
			LocationObservationDecision.REJECTED,
			database.locationObservationDecisionDao()
				.getBySourceEventId(EVENT_ID)
				?.decision,
		)
	}

	@Test
	fun `every stored receipt field mutation invalidates product proof`() = runTest {
		val command = command()
		insertWal(command)
		val writer = ReceiptWriter(database, accepted = true)
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			handoff(command, writer).drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL),
		)
		val sqlite = database.openHelper.writableDatabase
		val mutations = listOf(
			"UPDATE location_observation SET created_at = created_at + 1" to
				"UPDATE location_observation SET created_at = created_at - 1",
			"UPDATE location_observation SET estimator_version = estimator_version + 1" to
				"UPDATE location_observation SET estimator_version = estimator_version - 1",
			"UPDATE location_observation_decision SET clock_domain_id = 'wrong-clock'" to
				"UPDATE location_observation_decision SET clock_domain_id = '$CLOCK_ID'",
			"UPDATE location_observation_decision SET decided_at_ms = decided_at_ms + 1" to
				"UPDATE location_observation_decision SET decided_at_ms = decided_at_ms - 1",
			"UPDATE location_observation_decision SET decision_version = decision_version + 1" to
				"UPDATE location_observation_decision SET decision_version = decision_version - 1",
			"UPDATE location_observation_decision SET reason = 'corrupt'" to
				"UPDATE location_observation_decision SET reason = NULL",
			"UPDATE location_sample SET v_acc_m = v_acc_m + 1" to
				"UPDATE location_sample SET v_acc_m = v_acc_m - 1",
			"UPDATE location_sample SET delivery_age_ms = delivery_age_ms + 1" to
				"UPDATE location_sample SET delivery_age_ms = delivery_age_ms - 1",
			"UPDATE location_sample SET bearing_deg = 45" to
				"UPDATE location_sample SET bearing_deg = 90",
			"UPDATE location_sample SET policy = 'wrong-policy'" to
				"UPDATE location_sample SET policy = 'SOURCE_QOS_RESPONSIVE'",
		)

		mutations.forEach { (corrupt, restore) ->
			sqlite.execSQL(corrupt)
			assertIs<ProtectedLocationCanonicalReceipt.Invalid>(
				database.readProtectedLocationCanonicalReceipt(
					command,
					TEST_ACQUISITION_METADATA,
				),
			)
			sqlite.execSQL(restore)
			assertIs<ProtectedLocationCanonicalReceipt.Complete>(
				database.readProtectedLocationCanonicalReceipt(
					command,
					TEST_ACQUISITION_METADATA,
				),
			)
		}
	}

	@Test
	fun `preexisting same-id sample with wrong derived output cannot receive a receipt`() = runTest {
		val command = command()
		insertWal(command)
		val correctSample = command.toSample(TEST_ACQUISITION_METADATA)
		database.locationSampleDao().insert(
			correctSample.copy(
				altitudeM = 9_999f,
				speedMps = 999f,
			),
		)

		val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
			handoff(command, ReceiptWriter(database, accepted = true))
				.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL),
		)

		assertFalse(failed.terminal)
		assertEquals(ADMISSION_ORDINAL - 1L, failed.lastCommittedOrdinal)
		assertNull(database.sourceProjectionStateDao().joinState(
			ProtectedLocationCanonicalHandoff.WRITER_ID,
			ProtectedLocationCanonicalHandoff.WRITER_VERSION,
			"canonical-receipt:$EVENT_ID",
		))
	}

	@Test
	fun `same-id rejection with wrong nonblank reason cannot receive a receipt`() = runTest {
		val command = command()
		insertWal(command)
		val signalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(EVENT_ID)
		database.locationObservationDecisionDao().insert(
			listOf(
				LocationObservationDecision(
					observationSourceEventId = EVENT_ID,
					decision = LocationObservationDecision.REJECTED,
					reason = "WRONG_NONBLANK_REASON",
					acceptedSampleSourceSignalId = null,
					sourceSignalId = signalId,
					clockDomainId = CLOCK_ID,
					decidedAtMs = WALL_TIME_MS,
				),
			),
		)

		val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
			handoff(command, ReceiptWriter(database, accepted = false))
				.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL),
		)

		assertFalse(failed.terminal)
		assertEquals(ADMISSION_ORDINAL - 1L, failed.lastCommittedOrdinal)
		assertNull(database.sourceProjectionStateDao().joinState(
			ProtectedLocationCanonicalHandoff.WRITER_ID,
			ProtectedLocationCanonicalHandoff.WRITER_VERSION,
			"canonical-receipt:$EVENT_ID",
		))
	}

	@Test
	fun `prepared event output is immutable across changed recomputation`() = runTest {
		val command = command()
		val signalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(EVENT_ID)
		val first = ProtectedLocationPreparedCanonicalOutput.fromDestinations(
			LocationObservationDecision(
				observationSourceEventId = EVENT_ID,
				decision = LocationObservationDecision.REJECTED,
				reason = "FIRST_REASON",
				acceptedSampleSourceSignalId = null,
				sourceSignalId = signalId,
				clockDomainId = CLOCK_ID,
				decidedAtMs = WALL_TIME_MS,
			),
			null,
		)
		val changed = ProtectedLocationPreparedCanonicalOutput.fromDestinations(
			LocationObservationDecision(
				observationSourceEventId = EVENT_ID,
				decision = LocationObservationDecision.REJECTED,
				reason = "CHANGED_REASON",
				acceptedSampleSourceSignalId = null,
				sourceSignalId = signalId,
				clockDomainId = CLOCK_ID,
				decidedAtMs = WALL_TIME_MS,
			),
			null,
		)
		database.prepareProtectedLocationCanonicalCurationState(
			command,
			LocationCanonicalCurationState.EMPTY,
			LocationCanonicalCurationState.EMPTY,
			first,
		)

		assertTrue(runCatching {
			database.prepareProtectedLocationCanonicalCurationState(
				command,
				LocationCanonicalCurationState.EMPTY,
				LocationCanonicalCurationState.EMPTY,
				changed,
			)
		}.isFailure)
		val stored = requireNotNull(
			database.loadPreparedProtectedLocationCanonicalCurationState(command),
		)
		assertTrue(stored.expectedOutput.contentEquals(first))
		assertFalse(stored.expectedOutput.contentEquals(changed))
	}

	@Test
	fun `wrong requested run reports authority change without invoking writer`() = runTest {
		val command = command()
		insertWal(command)
		val writer = ReceiptWriter(database, accepted = true)

		val result = handoff(command, writer)
			.drainThrough("different-logical", "different-run", ADMISSION_ORDINAL)

		val changed = assertIs<ProtectedLocationCanonicalDrainResult.AuthorityChanged>(result)
		assertEquals("LOCATION_DRAIN_ENDPOINT_SESSION_CHANGED", changed.reason)
		assertEquals(0, writer.writeCount)
	}

	@Test
	fun `endpoint above allocator is deferred without cursor movement`() = runTest {
		val command = command()
		insertWal(command)

		val result = handoff(command, ReceiptWriter(database, accepted = true))
			.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL + 1L)

		val deferred = assertIs<ProtectedLocationCanonicalDrainResult.Deferred>(result)
		assertEquals("LOCATION_DRAIN_ENDPOINT_ABOVE_ALLOCATOR_HIGH_WATER", deferred.reason)
		assertEquals(ADMISSION_ORDINAL - 1L, deferred.lastCommittedOrdinal)
	}

	@Test
	fun `wrong-source endpoint fails without consuming earlier Location`() = runTest {
		val command = command()
		insertWal(command)
		insertOtherSourceEvent(ADMISSION_ORDINAL + 1L)
		installDrainEndpointAuthority(ADMISSION_ORDINAL + 1L)

		val result = handoff(command, ReceiptWriter(database, accepted = true))
			.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL + 1L)

		val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(result)
		assertEquals("LOCATION_DRAIN_ENDPOINT_IDENTITY_MISMATCH", failed.failureCode)
		assertEquals(ADMISSION_ORDINAL - 1L, failed.lastCommittedOrdinal)
	}

	@Test
	fun `missing endpoint above deletion high water fails without cursor movement`() = runTest {
		val command = command()
		insertWal(command)
		insertOtherSourceEvent(ADMISSION_ORDINAL + 1L)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE admission_ordinal = ?",
			arrayOf(ADMISSION_ORDINAL + 1L),
		)
		installDrainEndpointAuthority(ADMISSION_ORDINAL + 1L)

		val result = handoff(command, ReceiptWriter(database, accepted = true))
			.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL + 1L)

		val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(result)
		assertEquals("LOCATION_DRAIN_ENDPOINT_MISSING", failed.failureCode)
		assertEquals(ADMISSION_ORDINAL - 1L, failed.lastCommittedOrdinal)
	}

	@Test
	fun `missing interior Location sequence blocks despite later endpoint and other-source gap`() =
		runTest {
			val missing = command()
			insertWal(missing)
			insertOtherSourceEvent(ADMISSION_ORDINAL + 1L)
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM source_event_wal WHERE admission_ordinal = ?",
				arrayOf(ADMISSION_ORDINAL),
			)
			val endpoint = command(
				eventId = "location-endpoint-12",
				admissionOrdinal = ADMISSION_ORDINAL + 2L,
				wallTimeMs = WALL_TIME_MS + 2_000L,
				observedNanos = OBSERVED_NANOS + 2_000_000_000L,
				receivedNanos = RECEIVED_NANOS + 2_000_000_000L,
				sourceSequence = 2L,
			)
			insertWal(endpoint)
			installDrainEndpointAuthority(ADMISSION_ORDINAL + 2L)
			updateDrainCompleteness(
				lastAdmissionOrdinal = ADMISSION_ORDINAL + 2L,
				lastSourceSequence = endpoint.productEffect.durableEvidence.sourceSequence,
			)

			val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
				handoff(endpoint, ReceiptWriter(database, accepted = true))
					.drainThrough(
						LOGICAL_ID,
						RUN_ID,
						ADMISSION_ORDINAL + 2L,
					),
			)

			assertEquals("LOCATION_DRAIN_INTERIOR_SEQUENCE_GAP", failed.failureCode)
			assertEquals(ADMISSION_ORDINAL - 1L, failed.lastCommittedOrdinal)
		}

	@Test
	fun `deleted endpoint advances only through allocator and deletion proof`() = runTest {
		val command = command()
		insertWal(command)
		database.sourceEventWalDao().deleteAll()
		database.sourceEvidenceStateDao().recordFullDeletion(
			epoch = 1L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = ADMISSION_ORDINAL,
			updatedAtMs = 20_000L,
		)

		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			handoff(command, ReceiptWriter(database, accepted = true))
				.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL),
		)

		assertEquals(ADMISSION_ORDINAL, complete.lastCommittedOrdinal)
		assertEquals(1, complete.lifecycleSettled)
	}

	@Test
	fun `proven global ordinal gap advances between exact Location receipts`() = runTest {
		val first = command()
		val second = command(
			eventId = "location-event-12",
			admissionOrdinal = ADMISSION_ORDINAL + 2L,
			wallTimeMs = WALL_TIME_MS + 2_000L,
			observedNanos = OBSERVED_NANOS + 2_000_000_000L,
			receivedNanos = RECEIVED_NANOS + 2_000_000_000L,
			sourceSequence = 2L,
		)
		insertWal(first)
		insertOtherSourceEvent(ADMISSION_ORDINAL + 1L)
		insertWal(second)
		installDrainEndpointAuthority(ADMISSION_ORDINAL + 2L)
		updateDrainCompleteness(
			lastAdmissionOrdinal = ADMISSION_ORDINAL + 2L,
			lastSourceSequence = 2L,
		)
		val commands = mapOf(
			first.mutation.identity.sourceEventId.value to first,
			second.mutation.identity.sourceEventId.value to second,
		)
		val writer = ReceiptWriter(database, accepted = true)
		val handoff = ProtectedLocationCanonicalHandoff(
			database = database,
			qualifier = ProtectedLocationWalQualifier { eventId ->
				LocationWalAdapterResult.Evaluated(
					LocationObservationQualification.Qualified(
						requireNotNull(commands[eventId.value]),
					),
					TEST_ACQUISITION_METADATA,
				)
			},
			canonicalWriter = writer,
		)

		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			handoff.drainThrough(
				LOGICAL_ID,
				RUN_ID,
				ADMISSION_ORDINAL + 2L,
			),
		)

		assertEquals(ADMISSION_ORDINAL + 2L, complete.lastCommittedOrdinal)
		assertEquals(2, complete.acceptedSamplesCommitted)
		assertEquals(2, writer.writeCount)
	}

	@Test
	fun `global drain rejects a missing interior Location sequence`() = runTest {
		val missing = command()
		insertWal(missing)
		insertOtherSourceEvent(ADMISSION_ORDINAL + 1L)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE admission_ordinal = ?",
			arrayOf(ADMISSION_ORDINAL),
		)
		val endpoint = command(
			eventId = "global-location-endpoint",
			admissionOrdinal = ADMISSION_ORDINAL + 2L,
			wallTimeMs = WALL_TIME_MS + 2_000L,
			observedNanos = OBSERVED_NANOS + 2_000_000_000L,
			receivedNanos = RECEIVED_NANOS + 2_000_000_000L,
			sourceSequence = 2L,
		)
		insertWal(endpoint)

		val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
			handoff(endpoint, ReceiptWriter(database, accepted = true)).drainHighWater(),
		)

		assertEquals("LOCATION_DRAIN_INTERIOR_SEQUENCE_GAP", failed.failureCode)
		assertEquals(ADMISSION_ORDINAL - 1L, failed.lastCommittedOrdinal)
	}

	@Test
	fun `global drain rejects a missing Location from another registration`() = runTest {
		val missing = command()
		insertWal(missing)
		insertOtherSourceEvent(ADMISSION_ORDINAL + 1L)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE admission_ordinal = ?",
			arrayOf(ADMISSION_ORDINAL),
		)
		val differentRegistration = command(
			eventId = "global-location-second-registration",
			admissionOrdinal = ADMISSION_ORDINAL + 2L,
			wallTimeMs = WALL_TIME_MS + 2_000L,
			observedNanos = OBSERVED_NANOS + 2_000_000_000L,
			receivedNanos = RECEIVED_NANOS + 2_000_000_000L,
			sourceSequence = 1L,
			sourceInstanceId = "location-runtime-2",
			registrationGeneration = 4L,
		)
		insertWal(differentRegistration)
		val writer = ReceiptWriter(database, accepted = true)

		val failed = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
			handoff(differentRegistration, writer).drainHighWater(),
		)

		assertEquals("LOCATION_DRAIN_INTERIOR_SEQUENCE_GAP", failed.failureCode)
		assertEquals(ADMISSION_ORDINAL - 1L, failed.lastCommittedOrdinal)
		assertEquals(0, writer.writeCount)
		assertEquals(
			ADMISSION_ORDINAL - 1L,
			database.sourceProjectionStateDao()
				.activeProductLane(SourceKind.LOCATION.stableCode)
				?.contiguousAdmissionOrdinal,
		)
	}

	@Test
	fun `global drain does not cross an unproven trailing Location range`() = runTest {
		val vanished = command()
		insertWal(vanished)
		insertOtherSourceEvent(ADMISSION_ORDINAL + 1L)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE admission_ordinal = ?",
			arrayOf(ADMISSION_ORDINAL),
		)
		val writer = ReceiptWriter(database, accepted = true)

		val deferred = assertIs<ProtectedLocationCanonicalDrainResult.Deferred>(
			handoff(vanished, writer).drainHighWater(),
		)

		assertEquals("LOCATION_DRAIN_TRAILING_RANGE_UNPROVEN", deferred.reason)
		assertEquals(ADMISSION_ORDINAL - 1L, deferred.lastCommittedOrdinal)
		assertEquals(ADMISSION_ORDINAL, deferred.deferredOrdinal)
		assertEquals(0, writer.writeCount)
		assertEquals(
			ADMISSION_ORDINAL - 1L,
			database.sourceProjectionStateDao()
				.activeProductLane(SourceKind.LOCATION.stableCode)
				?.contiguousAdmissionOrdinal,
		)
	}

	@Test
	fun `global drain crosses an explicitly known non Location ordinal`() = runTest {
		val command = command()
		insertWal(command)
		insertOtherSourceEvent(ADMISSION_ORDINAL + 1L)
		val writer = ReceiptWriter(database, accepted = true)

		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			handoff(command, writer).drainHighWater(),
		)

		assertEquals(ADMISSION_ORDINAL + 1L, complete.lastCommittedOrdinal)
		assertEquals(1, writer.writeCount)
		assertEquals(
			ADMISSION_ORDINAL + 1L,
			database.sourceProjectionStateDao()
				.activeProductLane(SourceKind.LOCATION.stableCode)
				?.contiguousAdmissionOrdinal,
		)
	}

	@Test
	fun `global drain uses durable sequence anchor after prior WAL pruning`() = runTest {
		val first = command()
		insertWal(first)
		val commands = mutableMapOf(EVENT_ID to first)
		val writer = ReceiptWriter(database, accepted = true)
		val handoff = ProtectedLocationCanonicalHandoff(
			database,
			ProtectedLocationWalQualifier { eventId ->
				LocationWalAdapterResult.Evaluated(
					LocationObservationQualification.Qualified(
						requireNotNull(commands[eventId.value]),
					),
					TEST_ACQUISITION_METADATA,
				)
			},
			writer,
		)
		assertIs<ProtectedLocationCanonicalDrainResult.Complete>(handoff.drainHighWater())
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE admission_ordinal = ?",
			arrayOf(ADMISSION_ORDINAL),
		)
		val next = command(
			eventId = "location-after-prune",
			admissionOrdinal = ADMISSION_ORDINAL + 1L,
			wallTimeMs = WALL_TIME_MS + 1_000L,
			observedNanos = OBSERVED_NANOS + 1_000_000_000L,
			receivedNanos = RECEIVED_NANOS + 1_000_000_000L,
			sourceSequence = 2L,
		)
		commands[next.mutation.identity.sourceEventId.value] = next
		insertWal(next)

		val complete = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			handoff.drainHighWater(),
		)

		assertEquals(ADMISSION_ORDINAL + 1L, complete.lastCommittedOrdinal)
	}

	@Test
	fun `explicit stopped drain defers when observed registration lacks completeness`() = runTest {
		val first = command()
		insertWal(first)
		val secondRegistration = command(
			eventId = "location-second-registration",
			admissionOrdinal = ADMISSION_ORDINAL + 1L,
			wallTimeMs = WALL_TIME_MS + 1_000L,
			observedNanos = OBSERVED_NANOS + 1_000_000_000L,
			receivedNanos = RECEIVED_NANOS + 1_000_000_000L,
			sourceSequence = 1L,
			sourceInstanceId = "location-runtime-2",
			registrationGeneration = 4L,
		)
		insertWal(secondRegistration)
		installDrainEndpointAuthority(ADMISSION_ORDINAL + 1L)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_session_completeness WHERE logical_tracking_id = ? " +
				"AND service_run_id = ? AND source_kind = ?",
			arrayOf(LOGICAL_ID, RUN_ID, SourceKind.LOCATION.stableCode),
		)
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				sourceKind = SourceKind.LOCATION.stableCode,
				sourceInstanceId = "location-runtime-2",
				registrationGeneration = 4L,
				lastAdmissionOrdinal = ADMISSION_ORDINAL + 1L,
				lastSourceSequence = 1L,
				appDrainComplete = true,
				providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = 20_002L,
			),
		)
		val commands = mapOf(
			first.mutation.identity.sourceEventId.value to first,
			secondRegistration.mutation.identity.sourceEventId.value to secondRegistration,
		)

		val deferred = assertIs<ProtectedLocationCanonicalDrainResult.Deferred>(
			ProtectedLocationCanonicalHandoff(
				database,
				ProtectedLocationWalQualifier { eventId ->
					LocationWalAdapterResult.Evaluated(
						LocationObservationQualification.Qualified(
							requireNotNull(commands[eventId.value]),
						),
						TEST_ACQUISITION_METADATA,
					)
				},
				ReceiptWriter(database, accepted = true),
			).drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL + 1L),
		)

		assertEquals(
			"LOCATION_DRAIN_OBSERVED_REGISTRATION_MISSING_COMPLETENESS",
			deferred.reason,
		)
		assertEquals(ADMISSION_ORDINAL - 1L, deferred.lastCommittedOrdinal)
	}

	@Test
	fun `full deletion settles rejected WAL without product resurrection`() = runTest {
		val command = command()
		insertWal(command)
		database.sourceEvidenceStateDao().recordFullDeletion(
			epoch = 1L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = ADMISSION_ORDINAL,
			updatedAtMs = 20_000L,
		)
		val writer = ReceiptWriter(database, accepted = true)
		val qualifier = ProtectedLocationWalQualifier {
			LocationWalAdapterResult.Rejected(LocationWalAdapterRejection.DELETED_EVIDENCE)
		}
		val handoff = ProtectedLocationCanonicalHandoff(database, qualifier, writer)

		val result = assertIs<ProtectedLocationCanonicalDrainResult.Complete>(
			handoff.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL),
		)

		assertEquals(1, result.lifecycleSettled)
		assertEquals(0, writer.writeCount)
		assertNull(database.locationObservationDao().getBySourceEventId(EVENT_ID))
		assertNull(database.locationObservationDecisionDao().getBySourceEventId(EVENT_ID))
	}

	@Test
	fun `destination owner race preserves receipt but reports authority change before cursor`() =
		runTest {
			val command = command()
			insertWal(command)
			val writer = ReceiptWriter(
				database = database,
				accepted = true,
				afterCommit = {
					check(database.sourceDestinationOwnerDao().compareAndSetOwner(
						sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
						destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
						expectedOwner =
							SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
						expectedOwnerGeneration =
							SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
						newOwner = "TEST_OTHER_LOCATION_OWNER",
						newOwnerGeneration = 2L,
						updatedAtMs = 30_000L,
					) == 1)
				},
			)

			val changed = assertIs<ProtectedLocationCanonicalDrainResult.AuthorityChanged>(
				handoff(command, writer)
					.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL),
			)

			assertEquals(ADMISSION_ORDINAL - 1L, changed.lastCommittedOrdinal)
			assertIs<ProtectedLocationCanonicalReceipt.Complete>(
				database.readProtectedLocationCanonicalReceipt(
					command,
					TEST_ACQUISITION_METADATA,
				),
			)
		}

	@Test
	fun `v1 mock unverifiable remains terminal and cursor stable`() = runTest {
		val command = command()
		insertWal(command)
		val handoff = ProtectedLocationCanonicalHandoff(
			database = database,
			qualifier = ProtectedLocationWalQualifier {
				LocationWalAdapterResult.Rejected(
					LocationWalAdapterRejection.MOCK_PROVENANCE_UNVERIFIABLE,
				)
			},
			canonicalWriter = ReceiptWriter(database, accepted = true),
		)

		val result = assertIs<ProtectedLocationCanonicalDrainResult.Failed>(
			handoff.drainThrough(LOGICAL_ID, RUN_ID, ADMISSION_ORDINAL),
		)

		assertTrue(result.terminal)
		assertEquals(ADMISSION_ORDINAL - 1L, result.lastCommittedOrdinal)
		assertEquals(
			"LOCATION_ADAPTER_MOCK_PROVENANCE_UNVERIFIABLE",
			result.failureCode,
		)
	}

	@Test
	fun `manual canonical cycle contains only exact location input and no fake zero motion`() {
		val command = command(speedMetersPerSecond = null)

		val cycle = command.toProtectedLocationTrackingCycle(
			TEST_ACQUISITION_METADATA,
			TEST_ACQUISITION_METADATA.toCanonicalCurationContext(
				LocationCanonicalCurationState.EMPTY,
			),
		)

		assertNull(cycle.activity)
		assertNull(cycle.stepDelta)
		assertNull(cycle.pressure)
		assertNull(cycle.cellScan)
		assertNull(cycle.wifiScan)
		assertTrue(cycle.locationObservations.isEmpty())
		val locationData = requireNotNull(cycle.location)
		assertFalse(locationData.lastLocation.hasSpeed())
		assertEquals(EVENT_ID, locationData.lastFixMetadata.sourceEventId)
	}

	private fun handoff(
		command: LocationCapturedFactCommand,
		writer: ProtectedLocationCanonicalWriter,
	): ProtectedLocationCanonicalHandoff = ProtectedLocationCanonicalHandoff(
		database = database,
		qualifier = ProtectedLocationWalQualifier {
			LocationWalAdapterResult.Evaluated(
				LocationObservationQualification.Qualified(command),
				TEST_ACQUISITION_METADATA,
			)
		},
		canonicalWriter = writer,
	)

	private suspend fun installCanonicalLane() {
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
				owner = SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
				ownerGeneration =
					SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
				updatedAtMs = 1_000L,
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
				activatedRolloutRevision = 1L,
				activationOrdinal = ADMISSION_ORDINAL,
				contiguousAdmissionOrdinal = ADMISSION_ORDINAL - 1L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)
	}

	private suspend fun installDrainEndpointAuthority(lastAdmissionOrdinal: Long) {
		val sessionDao = database.sourceSessionDao()
		val existingSession = sessionDao.session(LOGICAL_ID)
		if (existingSession == null) {
			sessionDao.insertSession(
				LogicalTrackingSessionEntity(
					logicalTrackingId = LOGICAL_ID,
					state = SessionLifecycleState.STOPPING.name,
					lifecycleRevision = 1L,
					desiredPlanRevision = 1L,
					rolloutRevision = 1L,
					startOrigin = "MANUAL_FOREGROUND_START",
					clockDomainId = CLOCK_ID,
					startedAtMs = 1_000L,
					startedElapsedNanos = 1L,
					cutoffAtMs = 20_000L,
					cutoffElapsedNanos = Long.MAX_VALUE - 1L,
					completedAtMs = null,
					finalAdmissionOrdinal = null,
					failureCode = null,
					currentServiceRunId = RUN_ID,
					lifecycleLeaseGeneration = 1L,
					lifecycleBootId = CLOCK_ID,
				),
			)
			sessionDao.insertServiceRun(
				SourceServiceRunEntity(
					serviceRunId = RUN_ID,
					logicalTrackingId = LOGICAL_ID,
					state = SessionLifecycleState.STOPPING.name,
					desiredPlanRevision = 1L,
					rolloutRevision = 1L,
					foregroundCapabilityFlags = 0L,
					startedAtMs = 1_000L,
					startedElapsedNanos = 1L,
					completedAtMs = null,
					completionReason = null,
					bootId = CLOCK_ID,
					leaseGeneration = 1L,
				),
			)
		}

		sessionDao.saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				sourceKind = SourceKind.LOCATION.stableCode,
				sourceInstanceId = "location-runtime",
				registrationGeneration = 3L,
				lastAdmissionOrdinal = lastAdmissionOrdinal,
				lastSourceSequence = 1L,
				appDrainComplete = true,
				providerCoverage = "COMPLETE",
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = 20_000L,
			),
		)
	}

	private suspend fun updateDrainCompleteness(
		lastAdmissionOrdinal: Long,
		lastSourceSequence: Long?,
		stopStatus: String = "COMPLETE",
		unresolvedSequenceStart: Long? = null,
		unresolvedSequenceEnd: Long? = null,
	) {
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				sourceKind = SourceKind.LOCATION.stableCode,
				sourceInstanceId = "location-runtime",
				registrationGeneration = 3L,
				lastAdmissionOrdinal = lastAdmissionOrdinal,
				lastSourceSequence = lastSourceSequence,
				appDrainComplete = true,
				providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
				stopStatus = stopStatus,
				unresolvedSequenceStart = unresolvedSequenceStart,
				unresolvedSequenceEnd = unresolvedSequenceEnd,
				updatedAtMs = 20_001L,
			),
		)
	}

	private suspend fun insertOtherSourceEvent(admissionOrdinal: Long) {
		val row = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID))
		val other = row.copy(
			admissionOrdinal = admissionOrdinal,
			eventId = "other-source-endpoint",
			sourceKind = SourceKind.ACTIVITY.stableCode,
			sourceInstanceId = "activity-runtime",
			sourceSequence = 2L,
			deliveryIdentity = null,
			deliveryUnitIndex = null,
			deliveryUnitCount = null,
		)
		database.sourceEventWalDao().insertDeliveryUnits(
			listOf(other.copy(integrityIdentity = other.calculatedIntegrityIdentity())),
		)
	}

	private suspend fun insertWal(command: LocationCapturedFactCommand) {
		val payload = byteArrayOf(1)
		database.sourceEventWalDao().insertDeliveryUnits(
			listOf(
				SourceEventWalEntity(
					admissionOrdinal = command.mutation.identity.sourceAdmissionOrdinal,
					eventId = command.mutation.identity.sourceEventId.value,
					providerDedupKey = null,
					deliveryIdentity = command.mutation.identity.sourceDeliveryIdentity.value,
					deliveryUnitIndex = 0,
					deliveryUnitCount = 1,
					logicalTrackingId = LOGICAL_ID,
					serviceRunId = RUN_ID,
					sourceKind = SourceKind.LOCATION.stableCode,
					sourceInstanceId = command.authority.sourceInstanceId.value,
					registrationGeneration = command.authority.registrationGeneration,
					physicalConfigurationFingerprint =
						command.authority.physicalConfigurationFingerprint,
					authorizationRevision = command.authority.authorizationRevision,
					authorizationPurposeEligibilityMask =
						command.authority.purposeEligibilityMask,
					authorizationFingerprint = command.authority.authorizationFingerprint,
					sourceSequence = command.productEffect.durableEvidence.sourceSequence,
					configRevision = command.authority.configurationRevision,
					planAttribution = 1,
					clockDomainId = command.authority.clockDomainId,
					observedElapsedNanos =
						command.productEffect.durableEvidence.clockAuthority
							.observedElapsedRealtimeNanos,
					observedIntervalStartNanos =
						command.productEffect.durableEvidence.clockAuthority
							.observedElapsedRealtimeNanos,
					receivedElapsedNanos =
						command.productEffect.durableEvidence.clockAuthority
							.receivedElapsedRealtimeNanos,
					receivedWallTimeMs =
						command.productEffect.durableEvidence.clockAuthority.receivedWallTimeMs,
					wallTimeMs =
						command.productEffect.durableEvidence.clockAuthority.observedWallTimeMs,
					wallTimeUncertaintyMs = 0L,
					capturedCollectedDataEpoch =
						command.authority.capturedCollectedDataEpoch,
					sourcePolicyRevision = command.authority.sourcePolicyRevision,
					captureConsentEpoch = command.authority.captureConsentEpoch,
					sessionManifestRevision = command.authority.sessionManifestRevision,
					lifecycleLeaseGeneration = command.authority.lifecycleLeaseGeneration,
					acquiredAtMs =
						command.productEffect.durableEvidence.clockAuthority.observedWallTimeMs,
					qualityFlags = 0L,
					qualityConfidence = null,
					payloadVersion = 2,
					payload = payload,
					payloadChecksum = payload.sha256(),
					createdAtMs = 10_000L,
				),
			),
		)
	}

	private fun command(
		eventId: String = EVENT_ID,
		admissionOrdinal: Long = ADMISSION_ORDINAL,
		wallTimeMs: Long = WALL_TIME_MS,
		observedNanos: Long = OBSERVED_NANOS,
		receivedNanos: Long = RECEIVED_NANOS,
		sourceSequence: Long = 1L,
		sourceInstanceId: String = "location-runtime",
		registrationGeneration: Long = 3L,
		speedMetersPerSecond: Float? = 1.25f,
	): LocationCapturedFactCommand {
		val temporal = LocationCaptureTemporalAuthority(
			providerRegistration = interval(),
			authorization = interval(),
			sourcePolicy = interval(),
			captureConsent = interval(),
			sessionManifest = interval(),
			lifecycleLease = interval(),
		)
		val authority = LocationCaptureAuthority(
			logicalTrackingId = LogicalTrackingId(LOGICAL_ID),
			serviceRunId = ServiceRunId(RUN_ID),
			sessionSegmentId = 7L,
			capturedSources = setOf(SourceKind.LOCATION),
			controlSources = emptySet(),
			sourceInstanceId = SourceInstanceId(sourceInstanceId),
			registrationGeneration = registrationGeneration,
			configurationRevision = 5L,
			physicalConfigurationFingerprint = "location-fingerprint",
			authorizationRevision = 4L,
			authorizationFingerprint = "authorization-fingerprint",
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = 6L,
			captureConsentEpoch = 2L,
			sessionManifestRevision = 8L,
			lifecycleLeaseGeneration = 9L,
			capturedCollectedDataEpoch = 0L,
			clockDomainId = CLOCK_ID,
			zoneId = "Europe/Prague",
			permissionPrecision = LocationPermissionPrecision.PRECISE,
			temporalAuthority = temporal,
			acquisitionConfiguration = LocationHistoricalAcquisitionConfiguration(
				maximumObservationAgeNanos = 5_000_000_000L,
				maximumHorizontalAccuracyMeters = 50f,
			),
		)
		val evidence = LocationDurableObservationEvidence(
			sourceEventId = SourceEventId(eventId),
			sourceAdmissionOrdinal = admissionOrdinal,
			sourceSequence = sourceSequence,
			walIntegrityIdentity = "wal:$eventId".encodeToByteArray().sha256(),
			sourceDeliveryIdentity = SourceDeliveryIdentity(
				"delivery:$eventId".encodeToByteArray().sha256(),
			),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			capturedAuthority = authority,
			clockAuthority = LocationDurableClockAuthority(
				clockDomainId = CLOCK_ID,
				observedElapsedRealtimeNanos = observedNanos,
				receivedElapsedRealtimeNanos = receivedNanos,
				observedWallTimeMs = wallTimeMs,
				receivedWallTimeMs = wallTimeMs + 5_000L,
				wallTimeUncertaintyMs = 0L,
			),
			payloadVersion = 2,
			payload = LocationFixPayload(
				latitudeDegrees = 50.087,
				longitudeDegrees = 14.421,
				horizontalAccuracyMeters = 4f,
				altitudeMeters = 242.5,
				verticalAccuracyMeters = 3f,
				speedMetersPerSecond = speedMetersPerSecond,
				bearingDegrees = 90f,
				provider = "gps",
				isMock = false,
			),
			quality = SourceQuality(),
			isMock = false,
		)
		val identity = LocationCapturedFactIdentity(
			sourceEventId = evidence.sourceEventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			walIntegrityIdentity = evidence.walIntegrityIdentity,
			sourceDeliveryIdentity = requireNotNull(evidence.sourceDeliveryIdentity),
			deliveryUnitIndex = 0,
			logicalTrackingId = authority.logicalTrackingId,
			serviceRunId = authority.serviceRunId,
			sessionSegmentId = authority.sessionSegmentId,
			sessionManifestRevision = authority.sessionManifestRevision,
			capturedCollectedDataEpoch = authority.capturedCollectedDataEpoch,
		)
		return LocationCapturedFactCommand(
			mutation = LocationCapturedFactMutation(identity, 1L, null),
			authority = authority,
			productEffect = LocationCapturedProductEffect(
				durableEvidence = evidence,
				derivedQualification = LocationDerivedQualification(
					qualifierVersion = 1,
					deliveryAgeNanos = receivedNanos - observedNanos,
					maximumObservationAgeNanos =
						authority.acquisitionConfiguration.maximumObservationAgeNanos,
					maximumHorizontalAccuracyMeters =
						authority.acquisitionConfiguration.maximumHorizontalAccuracyMeters,
					earliestPossibleWallTimeMs = wallTimeMs,
					latestPossibleWallTimeMs = wallTimeMs,
				),
			),
		)
	}

	private fun interval() = LocationProviderTimeInterval(1L, Long.MAX_VALUE)

	private companion object {
		const val LOGICAL_ID = "logical-location"
		const val RUN_ID = "run-location"
		const val EVENT_ID = "location-event"
		const val ADMISSION_ORDINAL = 10L
		const val OBSERVED_NANOS = 2_000_000_000L
		const val RECEIVED_NANOS = 7_000_000_000L
		const val WALL_TIME_MS = 10_000L
		const val RECEIVED_WALL_TIME_MS = 15_000L
		const val CLOCK_ID = "boot-location"
	}
}

private class ReceiptWriter(
	private val database: AppDatabase,
	private val accepted: Boolean,
	private val omitDecision: Boolean = false,
	private val afterCommit: suspend () -> Unit = {},
) : ProtectedLocationCanonicalWriter {
	var writeCount: Int = 0
		private set

	override suspend fun write(
		command: LocationCapturedFactCommand,
		acquisitionMetadata: LocationWalAcquisitionMetadata,
	): ProtectedLocationCanonicalWriteResult {
		writeCount++
		database.withTransaction {
			val eventId = command.mutation.identity.sourceEventId.value
			val signalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(eventId)
			val sample = command.toSample(acquisitionMetadata).takeIf { accepted }
			val decision = LocationObservationDecision(
				observationSourceEventId = eventId,
				decision = if (accepted) {
					LocationObservationDecision.ACCEPTED
				} else {
					LocationObservationDecision.REJECTED
				},
				reason = if (accepted) null else "CURATED_LOCATION_REJECTED",
				acceptedSampleSourceSignalId = signalId.takeIf { accepted },
				sourceSignalId = signalId,
				clockDomainId = command.authority.clockDomainId,
				decidedAtMs =
					command.productEffect.durableEvidence.clockAuthority.observedWallTimeMs,
			)
			val expectedOutput = ProtectedLocationPreparedCanonicalOutput.fromDestinations(
				decision,
				sample,
			)
			database.prepareProtectedLocationCanonicalCurationState(
				command,
				LocationCanonicalCurationState.EMPTY,
				LocationCanonicalCurationState.EMPTY,
				expectedOutput,
			)
			database.locationObservationDao().insert(
				command.toObservation(acquisitionMetadata),
			)
			if (!omitDecision) {
				sample?.let { database.locationSampleDao().insert(it) }
				database.locationObservationDecisionDao().insert(
					listOf(decision),
				)
				database.recordProtectedLocationCanonicalReceiptInCurrentTransaction(
					ProtectedLocationVerifiedWrite(
						command,
						acquisitionMetadata,
						expectedOutput,
					),
				)
			}
		}
		afterCommit()
		return ProtectedLocationCanonicalWriteResult.Committed
	}
}

private fun LocationCapturedFactCommand.toObservation(
	acquisitionMetadata: LocationWalAcquisitionMetadata,
): LocationObservation {
	val evidence = productEffect.durableEvidence
	val clock = evidence.clockAuthority
	val payload = evidence.payload
	return LocationObservation(
		fixTimeMs = clock.observedWallTimeMs,
		fixElapsedRealtimeNanos = clock.observedElapsedRealtimeNanos,
		receivedAtMs = requireNotNull(clock.receivedWallTimeMs),
		receivedElapsedRealtimeNanos = clock.receivedElapsedRealtimeNanos,
		deliveryAgeMs =
			(clock.receivedElapsedRealtimeNanos - clock.observedElapsedRealtimeNanos) / 1_000_000L,
		latE7 = (payload.latitudeDegrees * 10_000_000.0).toInt(),
		lonE7 = (payload.longitudeDegrees * 10_000_000.0).toInt(),
		rawAltitudeM = payload.altitudeMeters?.toFloat(),
		hAccM = payload.horizontalAccuracyMeters,
		vAccM = payload.verticalAccuracyMeters,
		speedMps = payload.speedMetersPerSecond,
		speedAccuracyMps = null,
		bearingDeg = payload.bearingDegrees,
		bearingAccuracyDeg = null,
		provider = payload.provider,
		acquisitionMode = acquisitionMetadata.acquisitionMode.name,
		requestPriority = acquisitionMetadata.requestPriority.name,
		permissionPrecision = authority.permissionPrecision.name,
		batchIndex = evidence.deliveryUnitIndex,
		batchSize = evidence.deliveryUnitCount,
		isMock = evidence.isMock,
		ingressDisposition = "DELIVERED_VALID",
		estimatorVersion = 1,
		calibrationVersion = 0,
		createdAt = clock.observedWallTimeMs,
		sourceSignalId =
			ProtectedLocationCanonicalSignalIdentity.rawObservation(evidence.sourceEventId.value),
		sourceEventId = evidence.sourceEventId.value,
		callbackId = evidence.sourceDeliveryIdentity?.value,
		clockDomainId = clock.clockDomainId,
		bootClockDomainId = clock.clockDomainId,
	)
}

private fun LocationCapturedFactCommand.toSample(
	acquisitionMetadata: LocationWalAcquisitionMetadata,
): LocationSample {
	val evidence = productEffect.durableEvidence
	val clock = evidence.clockAuthority
	val payload = evidence.payload
	return LocationSample(
		timeMs = clock.observedWallTimeMs,
		elapsedRealtimeNanos = clock.observedElapsedRealtimeNanos,
		latE7 = (payload.latitudeDegrees * 10_000_000.0).toInt(),
		lonE7 = (payload.longitudeDegrees * 10_000_000.0).toInt(),
		altitudeM = null,
		rawGpsAltitudeM = payload.altitudeMeters?.toFloat(),
		hAccM = payload.horizontalAccuracyMeters,
		vAccM = payload.verticalAccuracyMeters,
		speedMps = payload.speedMetersPerSecond,
		speedAccuracyMps = null,
		provider = payload.provider,
		quality = SampleQuality.HIGH,
		motionState = null,
		policy = acquisitionMetadata.policyName,
		bucketId = null,
		createdAt = clock.observedWallTimeMs,
		receivedElapsedRealtimeNanos = clock.receivedElapsedRealtimeNanos,
		deliveryAgeMs =
			(clock.receivedElapsedRealtimeNanos - clock.observedElapsedRealtimeNanos) / 1_000_000L,
		acquisitionMode = acquisitionMetadata.acquisitionMode.name,
		requestPriority = acquisitionMetadata.requestPriority.name,
		permissionPrecision = authority.permissionPrecision.name,
		batchIndex = evidence.deliveryUnitIndex,
		batchSize = evidence.deliveryUnitCount,
		isMock = false,
		estimatorVersion = acquisitionMetadata.altitudeEstimatorVersion,
		calibrationVersion = 0,
		sourceSignalId =
			ProtectedLocationCanonicalSignalIdentity.canonicalProduct(evidence.sourceEventId.value),
		sourceEventId = evidence.sourceEventId.value,
		clockDomainId = clock.clockDomainId,
		altitudeModelVersion = acquisitionMetadata.altitudeModelVersion,
		rawGpsAltitudeDatum = if (payload.altitudeMeters != null) {
			com.adsamcik.tracker.shared.model.AltitudeDatum.WGS84_ELLIPSOID
		} else {
			com.adsamcik.tracker.shared.model.AltitudeDatum.UNKNOWN_LEGACY
		},
		rawPlatformSpeedMps = payload.speedMetersPerSecond,
		bearingDeg = payload.bearingDegrees,
		bootClockDomainId = clock.clockDomainId,
	)
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
	.digest(this)
	.joinToString("") { byte -> "%02x".format(byte) }

private val TEST_ACQUISITION_METADATA = LocationWalAcquisitionMetadata(
	acquisitionMode = LocationAcquisitionMode.FUSED,
	requestPriority = LocationRequestPriority.HIGH_ACCURACY,
	requiredAccuracyMeters = 50f,
	policyTier = com.adsamcik.tracker.stats.api.PolicyTier.PRECISION,
	policyName = "SOURCE_QOS_RESPONSIVE",
	curationVersion = PROTECTED_LOCATION_CANONICAL_CURATION_VERSION,
	altitudeModelVersion = com.adsamcik.tracker.shared.model.AltitudeContractVersions.MODEL_VERSION,
	altitudeEstimatorVersion =
		com.adsamcik.tracker.shared.model.AltitudeContractVersions.ESTIMATOR_VERSION,
	altitudeCalibrationVersion =
		com.adsamcik.tracker.shared.model.AltitudeContractVersions.CALIBRATION_VERSION,
)
