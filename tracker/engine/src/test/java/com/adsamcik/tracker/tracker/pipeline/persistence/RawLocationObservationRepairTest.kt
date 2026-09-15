package com.adsamcik.tracker.tracker.pipeline.persistence

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.recordFullDeletion
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RawLocationObservationRepairTest {
	private lateinit var database: AppDatabase
	private val codec = DefaultSourcePayloadCodec()

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		runTest {
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
					owner =
						SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
					ownerGeneration =
						SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
					updatedAtMs = 1L,
				),
			)
		}
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `repair preserves released v1 WAL with explicit unknown mock provenance`() = runTest {
		insertWal("location-event", payloadVersion = 1, isMock = null)
		val repair = RawLocationObservationRepair(database, codec)

		repair.repairMissingCanonicalObservations() shouldBe 1
		repair.repairMissingCanonicalObservations() shouldBe 0

		val observation = requireNotNull(
			database.locationObservationDao().getBySourceEventId("location-event"),
		)
		observation.isMock shouldBe false
		observation.ingressDisposition shouldBe "MIGRATED_MOCK_PROVENANCE_UNKNOWN"
		observation.sourceRevision shouldBe 1L
		database.locationObservationDecisionDao().insert(
			listOf(
				LocationObservationDecision(
					observationSourceEventId = "location-event",
					decision = LocationObservationDecision.REJECTED,
					reason = "MIGRATED_MOCK_PROVENANCE_UNKNOWN",
					acceptedSampleSourceSignalId = null,
					sourceSignalId = "pending-legacy-decision",
					clockDomainId = observation.clockDomainId,
					decidedAtMs = observation.receivedAtMs,
				),
			),
		)
		database.locationObservationDecisionDao()
			.getBySourceEventId("location-event")
			?.reason shouldBe "MIGRATED_MOCK_PROVENANCE_UNKNOWN"
	}

	@Test
	fun `repair rejects v2 control and receive-time rows from canonical raw history`() = runTest {
		insertWal(
			"location-v2-control",
			payloadVersion = 2,
			isMock = false,
			purposeMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
		)
		insertWal(
			"location-v2-receive",
			payloadVersion = 2,
			isMock = false,
			planAttribution = 2,
		)
		val repair = RawLocationObservationRepair(database, codec)

		repair.repairMissingCanonicalObservations() shouldBe 0
		database.locationObservationDao().countAll() shouldBe 0L
	}

	@Test
	fun `repair does not claim a fully bound protected location product`() = runTest {
		insertWal(
			eventId = "protected-location",
			payloadVersion = 2,
			isMock = false,
			protectedBinding = true,
		)
		val repair = RawLocationObservationRepair(database, codec)

		repair.repairMissingCanonicalObservations() shouldBe 0
		database.locationObservationDao().countAll() shouldBe 0L
	}

	@Test
	fun `repair retains legacy WAL when permanent Location owner is lost`() = runTest {
		insertWal("legacy-owner-loss", payloadVersion = 1, isMock = null)
		check(database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
			destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
			expectedOwner =
				SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
			expectedOwnerGeneration =
				SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
			newOwner = "OTHER_LOCATION_OWNER",
			newOwnerGeneration = 2L,
			updatedAtMs = 2L,
		) == 1)

		runCatching {
			RawLocationObservationRepair(database, codec)
				.repairMissingCanonicalObservations()
		}.isFailure shouldBe true

		database.locationObservationDao().countAll() shouldBe 0L
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `repair settles deleted high water without resurrecting legacy observation`() = runTest {
		insertWal("legacy-deleted", payloadVersion = 1, isMock = null)
		val ordinal = requireNotNull(
			database.sourceEventWalDao().getByEventId("legacy-deleted"),
		).admissionOrdinal
		database.sourceEvidenceStateDao().recordFullDeletion(
			epoch = 1L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = ordinal,
			updatedAtMs = 2L,
		)

		RawLocationObservationRepair(database, codec)
			.repairMissingCanonicalObservations() shouldBe 0

		database.locationObservationDao().countAll() shouldBe 0L
	}

	@Test
	fun `repair rejects wrong epoch and pre-retention legacy rows`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		check(database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 1L,
			retainedFromMs = 20_000L,
			updatedAtMs = 2L,
		) == 1)
		insertWal(
			eventId = "legacy-wrong-epoch",
			payloadVersion = 1,
			isMock = null,
			capturedEpoch = 0L,
		)
		insertWal(
			eventId = "legacy-before-retention",
			payloadVersion = 1,
			isMock = null,
			unitIndex = 1,
			capturedEpoch = 1L,
			acquiredAtMs = 10_000L,
		)

		RawLocationObservationRepair(database, codec)
			.repairMissingCanonicalObservations() shouldBe 0

		database.locationObservationDao().countAll() shouldBe 0L
	}

	private suspend fun insertWal(
		eventId: String,
		payloadVersion: Int,
		isMock: Boolean?,
		unitIndex: Int = 0,
		protectedBinding: Boolean = false,
		purposeMask: Long = 0L,
		planAttribution: Int = 1,
		capturedEpoch: Long = 0L,
		acquiredAtMs: Long = 10_250L + unitIndex,
	) {
		val payload = LocationFixPayload(
			latitudeDegrees = 50.087,
			longitudeDegrees = 14.421,
			horizontalAccuracyMeters = 4f,
			altitudeMeters = 242.5,
			verticalAccuracyMeters = 3f,
			speedMetersPerSecond = 1.25f,
			bearingDegrees = 90f,
			provider = "gps",
			isMock = isMock,
		)
		val encoded = codec.encode(payload, payloadVersion)
		database.sourceEventWalDao().insertIgnoringDuplicate(
			SourceEventWalEntity(
				eventId = eventId,
				providerDedupKey = if (protectedBinding) null else "callback-1:$unitIndex",
				deliveryIdentity = "delivery-1".takeIf { protectedBinding },
				deliveryUnitIndex = unitIndex.takeIf { protectedBinding },
				deliveryUnitCount = 1.takeIf { protectedBinding },
				logicalTrackingId = "tracking",
				serviceRunId = "run",
				sourceKind = SourceKind.LOCATION.stableCode,
				sourceInstanceId = "location-runtime",
				registrationGeneration = 1,
				physicalConfigurationFingerprint = "location-fingerprint"
					.takeIf { protectedBinding },
				authorizationRevision = 1L.takeIf { protectedBinding },
				authorizationPurposeEligibilityMask = purposeMask,
				authorizationFingerprint = "authorization-fingerprint"
					.takeIf { protectedBinding },
				sourceSequence = 7L + unitIndex,
				configRevision = 1,
				planAttribution = planAttribution,
				clockDomainId = "android-boot-count:19",
				observedElapsedNanos = 1_000_000_000L + unitIndex,
				receivedElapsedNanos = 1_250_000_000L + unitIndex,
				wallTimeMs = 10_000L + unitIndex,
				wallTimeUncertaintyMs = 0,
				capturedCollectedDataEpoch = capturedEpoch,
				sourcePolicyRevision = 1L.takeIf { protectedBinding },
				captureConsentEpoch = 1L.takeIf { protectedBinding },
				sessionManifestRevision = 1L.takeIf { protectedBinding },
				lifecycleLeaseGeneration = 1L.takeIf { protectedBinding },
				acquiredAtMs = acquiredAtMs,
				qualityFlags = 0,
				qualityConfidence = null,
				payloadVersion = payloadVersion,
				payload = encoded.bytes,
				payloadChecksum = encoded.checksum,
				createdAtMs = acquiredAtMs,
			),
		)
	}
}
