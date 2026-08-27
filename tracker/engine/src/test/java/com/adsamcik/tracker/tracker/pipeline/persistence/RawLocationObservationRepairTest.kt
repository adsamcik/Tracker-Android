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
	fun `repair reconstructs missing canonical observation from authoritative location WAL`() = runTest {
		val payload = LocationFixPayload(
			latitudeDegrees = 50.087,
			longitudeDegrees = 14.421,
			horizontalAccuracyMeters = 4f,
			altitudeMeters = 242.5,
			verticalAccuracyMeters = 3f,
			speedMetersPerSecond = 1.25f,
			bearingDegrees = 90f,
			provider = "gps",
		)
		val encoded = codec.encode(payload, 1)
		database.sourceEventWalDao().insertIgnoringDuplicate(
			SourceEventWalEntity(
				eventId = "location-event",
				providerDedupKey = "callback-1:0",
				logicalTrackingId = "tracking",
				serviceRunId = "run",
				sourceKind = SourceKind.LOCATION.stableCode,
				sourceInstanceId = "location-runtime",
				registrationGeneration = 1,
				sourceSequence = 7,
				configRevision = 1,
				planAttribution = 1,
				clockDomainId = "android-boot-count:19",
				observedElapsedNanos = 1_000_000_000L,
				receivedElapsedNanos = 1_250_000_000L,
				wallTimeMs = 10_000L,
				wallTimeUncertaintyMs = 0,
				capturedCollectedDataEpoch = 0,
				acquiredAtMs = 10_250L,
				qualityFlags = 0,
				qualityConfidence = null,
				payloadVersion = 1,
				payload = encoded.bytes,
				payloadChecksum = encoded.checksum,
				createdAtMs = 10_250L,
			),
		)
		val repair = RawLocationObservationRepair(database, codec)

		repair.repairMissingCanonicalObservations() shouldBe 1
		repair.repairMissingCanonicalObservations() shouldBe 0

		val observation = database.locationObservationDao()
			.getBetween(0L, Long.MAX_VALUE)
			.single()
		observation.sourceEventId shouldBe "location-event"
		observation.sourceSignalId shouldBe "location-observation:location-event"
		observation.fixTimeMs shouldBe 10_000L
		observation.receivedAtMs shouldBe 10_250L
		observation.deliveryAgeMs shouldBe 250L
		observation.latE7 shouldBe 500_870_000
		observation.lonE7 shouldBe 144_210_000
		observation.callbackId shouldBe "callback-1:0"
		observation.sourceRevision shouldBe 1L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
	}
}
