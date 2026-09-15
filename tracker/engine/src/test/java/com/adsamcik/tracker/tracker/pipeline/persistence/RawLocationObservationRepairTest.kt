package com.adsamcik.tracker.tracker.pipeline.persistence

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
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
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `repair leaves v1 WAL mock provenance typed unverifiable`() = runTest {
		insertWal("location-event", payloadVersion = 1, isMock = null)
		val repair = RawLocationObservationRepair(database, codec)

		repair.repairMissingCanonicalObservations() shouldBe 0

		database.locationObservationDao().countAll() shouldBe 0L
		(database.sourceEvidenceStateDao().get()?.revision ?: 0L) shouldBe 0L
	}

	@Test
	fun `repair preserves both values of durable v2 mock provenance`() = runTest {
		insertWal("location-v2-real", payloadVersion = 2, isMock = false, unitIndex = 0)
		insertWal("location-v2-mock", payloadVersion = 2, isMock = true, unitIndex = 1)
		val repair = RawLocationObservationRepair(database, codec)

		repair.repairMissingCanonicalObservations() shouldBe 2

		val observations = database.locationObservationDao().getBetween(0L, Long.MAX_VALUE)
			.associateBy { it.sourceEventId }
		observations.getValue("location-v2-real").isMock shouldBe false
		observations.getValue("location-v2-mock").isMock shouldBe true
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

	private suspend fun insertWal(
		eventId: String,
		payloadVersion: Int,
		isMock: Boolean?,
		unitIndex: Int = 0,
		protectedBinding: Boolean = false,
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
				authorizationFingerprint = "authorization-fingerprint"
					.takeIf { protectedBinding },
				sourceSequence = 7L + unitIndex,
				configRevision = 1,
				planAttribution = 1,
				clockDomainId = "android-boot-count:19",
				observedElapsedNanos = 1_000_000_000L + unitIndex,
				receivedElapsedNanos = 1_250_000_000L + unitIndex,
				wallTimeMs = 10_000L + unitIndex,
				wallTimeUncertaintyMs = 0,
				capturedCollectedDataEpoch = 0,
				sourcePolicyRevision = 1L.takeIf { protectedBinding },
				captureConsentEpoch = 1L.takeIf { protectedBinding },
				sessionManifestRevision = 1L.takeIf { protectedBinding },
				lifecycleLeaseGeneration = 1L.takeIf { protectedBinding },
				acquiredAtMs = 10_250L + unitIndex,
				qualityFlags = 0,
				qualityConfidence = null,
				payloadVersion = payloadVersion,
				payload = encoded.bytes,
				payloadChecksum = encoded.checksum,
				createdAtMs = 10_250L + unitIndex,
			),
		)
	}
}
