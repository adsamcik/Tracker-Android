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
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.dao.recordFullDeletion
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
		assertIs<ProtectedLocationCanonicalReceipt.Complete>(
			database.withTransaction {
				database.readProtectedLocationCanonicalReceipt(
					command,
					TEST_ACQUISITION_METADATA,
				)
			},
		)
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
				"UPDATE location_sample SET bearing_deg = NULL",
			"UPDATE location_sample SET policy = 'wrong-policy'" to
				"UPDATE location_sample SET policy = NULL",
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
	fun `historical run without its canonical session is explicitly deferred`() = runTest {
		val command = command()
		insertWal(command)
		val writer = ReceiptWriter(database, accepted = true)

		val result = handoff(command, writer)
			.drainThrough("different-logical", "different-run", ADMISSION_ORDINAL)

		val deferred = assertIs<ProtectedLocationCanonicalDrainResult.Deferred>(result)
		assertEquals("HISTORICAL_LOCATION_CANONICAL_SESSION_NOT_ACTIVE", deferred.reason)
		assertEquals(0, writer.writeCount)
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

		val cycle = command.toProtectedLocationTrackingCycle(TEST_ACQUISITION_METADATA)

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

	private suspend fun insertWal(command: LocationCapturedFactCommand) {
		val payload = byteArrayOf(1)
		database.sourceEventWalDao().insertDeliveryUnits(
			listOf(
				SourceEventWalEntity(
					admissionOrdinal = ADMISSION_ORDINAL,
					eventId = EVENT_ID,
					providerDedupKey = null,
					deliveryIdentity = DELIVERY_IDENTITY,
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
					sourceSequence = 1L,
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
			sourceInstanceId = SourceInstanceId("location-runtime"),
			registrationGeneration = 3L,
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
			sourceEventId = SourceEventId(EVENT_ID),
			sourceAdmissionOrdinal = ADMISSION_ORDINAL,
			walIntegrityIdentity = "a".repeat(64),
			sourceDeliveryIdentity = SourceDeliveryIdentity(DELIVERY_IDENTITY),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			capturedAuthority = authority,
			clockAuthority = LocationDurableClockAuthority(
				clockDomainId = CLOCK_ID,
				observedElapsedRealtimeNanos = OBSERVED_NANOS,
				receivedElapsedRealtimeNanos = RECEIVED_NANOS,
				observedWallTimeMs = WALL_TIME_MS,
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
			sourceAdmissionOrdinal = ADMISSION_ORDINAL,
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
					deliveryAgeNanos = RECEIVED_NANOS - OBSERVED_NANOS,
					maximumObservationAgeNanos =
						authority.acquisitionConfiguration.maximumObservationAgeNanos,
					maximumHorizontalAccuracyMeters =
						authority.acquisitionConfiguration.maximumHorizontalAccuracyMeters,
					earliestPossibleWallTimeMs = WALL_TIME_MS,
					latestPossibleWallTimeMs = WALL_TIME_MS,
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
		const val RECEIVED_NANOS = 2_250_000_000L
		const val WALL_TIME_MS = 10_000L
		const val CLOCK_ID = "boot-location"
		val DELIVERY_IDENTITY = "b".repeat(64)
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
			database.locationObservationDao().insert(
				command.toObservation(acquisitionMetadata),
			)
			if (!omitDecision) {
				if (accepted) {
					database.locationSampleDao().insert(
						command.toSample(acquisitionMetadata),
					)
				}
				database.locationObservationDecisionDao().insert(
					listOf(
						LocationObservationDecision(
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
								command.productEffect.durableEvidence.clockAuthority
									.observedWallTimeMs,
						),
					),
				)
				database.recordProtectedLocationCanonicalReceiptInCurrentTransaction(
					ProtectedLocationVerifiedWrite(command, acquisitionMetadata),
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
		receivedAtMs = clock.observedWallTimeMs,
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
		motionState = MotionState.UNKNOWN,
		policy = null,
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
		sourceSignalId =
			ProtectedLocationCanonicalSignalIdentity.canonicalProduct(evidence.sourceEventId.value),
		sourceEventId = evidence.sourceEventId.value,
		clockDomainId = clock.clockDomainId,
		rawPlatformSpeedMps = payload.speedMetersPerSecond,
		bootClockDomainId = clock.clockDomainId,
	)
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
	.digest(this)
	.joinToString("") { byte -> "%02x".format(byte) }

private val TEST_ACQUISITION_METADATA = LocationWalAcquisitionMetadata(
	acquisitionMode = LocationAcquisitionMode.FUSED,
	requestPriority = LocationRequestPriority.HIGH_ACCURACY,
)
