package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationProjectionDao
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionObservationEntity
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionPointEntity
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationDomainProjectionTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `route and distance are invariant to replay admission order`() {
		val ordered = listOf(
			observation("a", 1, 50.0, 14.0),
			observation("b", 3, 50.0009, 14.0),
			observation("c", 5, 50.0018, 14.0),
		)
		val shuffled = listOf(ordered[2], ordered[0], ordered[1])

		val first = deriveLocationTrack(ordered).associateBy(LocationDerivedPoint::eventId)
		val second = deriveLocationTrack(shuffled).associateBy(LocationDerivedPoint::eventId)

		assertEquals(first, second)
		assertTrue(abs(first.getValue("c").cumulativeDistanceMeters - 200.0) < 3.0)
	}

	@Test
	fun `teleport cannot add route distance and two nearby fixes reacquire without bridging gap`() {
		val route = deriveLocationTrack(
			listOf(
				observation("nyc", 1, 40.7128, -74.0060),
				observation("tokyo-1", 2, 35.6762, 139.6503),
				observation("tokyo-2", 3, 35.6763, 139.6503),
			),
		).associateBy(LocationDerivedPoint::eventId)

		assertFalse(route.getValue("tokyo-1").accepted)
		assertEquals(LocationRejection.TELEPORT_UNCONFIRMED, route.getValue("tokyo-1").rejection)
		assertTrue(route.getValue("tokyo-2").accepted)
		assertEquals(0.0, route.getValue("tokyo-2").segmentDistanceMeters)
		assertEquals(0.0, route.getValue("tokyo-2").cumulativeDistanceMeters)
	}

	@Test
	fun `poor accuracy is excluded from route speed and policy evidence`() {
		val poor = observation("poor", 1, 50.0, 14.0).copy(
			payload = observation("poor", 1, 50.0, 14.0).payload.copy(horizontalAccuracyMeters = 50.1f),
		)

		val result = deriveLocationTrack(listOf(poor)).single()

		assertFalse(result.accepted)
		assertEquals(LocationRejection.LOW_ACCURACY, result.rejection)
	}

	@Test
	fun `projection stores normalized rows and revises a late-arrival suffix without join blobs`() = runTest {
		val effects = mutableListOf<ProjectionOutboxEffect>()
		val context = object : ProjectionContext {
			override suspend fun recordOutbox(effect: ProjectionOutboxEffect) { effects += effect }
			override suspend fun loadJoinState(key: String): ByteArray? = null
			override suspend fun saveJoinState(
				key: String,
				payload: ByteArray,
				minimumRequiredOrdinal: Long?,
				logicalTrackingId: String?,
				payloadVersion: Int,
			) = error("Normalized location projection must not persist join-state BLOBs")
			override suspend fun removeJoinState(key: String) = Unit
		}
		val projection = LocationDomainProjection(database)

		projection.apply(event("later", admissionOrdinal = 1, observedSecond = 2), context)
		projection.apply(event("earlier", admissionOrdinal = 2, observedSecond = 1), context)

		val observations = database.locationProjectionDao().observations(TRACKING_ID)
		val points = database.locationProjectionDao().points(TRACKING_ID).associateBy { it.eventId }
		assertEquals(listOf("earlier", "later"), observations.map { it.eventId })
		assertEquals(1, points.getValue("earlier").revision)
		assertEquals(2, points.getValue("later").revision)
		assertTrue(points.getValue("later").cumulativeDistanceMeters > 90.0)
		assertTrue(database.sourceProjectionStateDao().joinStates(LocationDomainProjection.ID, 1).isEmpty())
		assertEquals(12, effects.size)
	}

	@Test
	fun `ordered append advances from latest rows without loading full session history`() = runTest {
		val projectionDao = mockk<LocationProjectionDao>()
		val mockedDatabase = mockk<AppDatabase>()
		val priorObservation = observationEntity("earlier", 1)
		val priorPoint = pointEntity("earlier", 1)
		every { mockedDatabase.locationProjectionDao() } returns projectionDao
		coEvery { projectionDao.latestObservation(TRACKING_ID) } returns priorObservation
		coEvery { projectionDao.point("later") } returns null
		coEvery { projectionDao.point("earlier") } returns priorPoint
		coEvery { projectionDao.latestAcceptedObservation(TRACKING_ID) } returns priorObservation
		coEvery { projectionDao.latestUnconfirmedTeleportObservation(TRACKING_ID) } returns null
		coEvery { projectionDao.upsertObservation(any()) } returns Unit
		coEvery { projectionDao.upsertPoints(any()) } returns Unit
		val effects = mutableListOf<ProjectionOutboxEffect>()
		val context = recordingContext(effects)

		LocationDomainProjection(mockedDatabase).apply(
			event("later", admissionOrdinal = 2, observedSecond = 2),
			context,
		)

		coVerify(exactly = 0) { projectionDao.observations(any()) }
		coVerify(exactly = 0) { projectionDao.points(any()) }
		coVerify(exactly = 1) {
			projectionDao.upsertPoints(match { points ->
				points.single().eventId == "later" && points.single().cumulativeDistanceMeters > 90.0
			})
		}
		assertEquals(4, effects.size)
	}

	@Test
	fun `incremental accumulator remains identical to deterministic full replay`() {
		val observations = listOf(
			observation("a", 1, 50.0, 14.0).copy(
				payload = observation("a", 1, 50.0, 14.0).payload.copy(speedMetersPerSecond = 3f),
			),
			observation("b", 2, 50.0009, 14.0),
			observation("poor", 3, 50.0018, 14.0).copy(
				payload = observation("poor", 3, 50.0018, 14.0).payload.copy(horizontalAccuracyMeters = 60f),
			),
			observation("c", 4, 50.0027, 14.0),
		)
		var state = LocationAccumulatorState()
		val incremental = observations.map { current ->
			advanceLocationTrack(state, current).also { state = it.state }.point
		}

		assertEquals(deriveLocationTrack(observations), incremental)
	}

	private fun recordingContext(effects: MutableList<ProjectionOutboxEffect>) = object : ProjectionContext {
		override suspend fun recordOutbox(effect: ProjectionOutboxEffect) { effects += effect }
		override suspend fun loadJoinState(key: String): ByteArray? = null
		override suspend fun saveJoinState(
			key: String,
			payload: ByteArray,
			minimumRequiredOrdinal: Long?,
			logicalTrackingId: String?,
			payloadVersion: Int,
		) = error("Normalized location projection must not persist join-state BLOBs")
		override suspend fun removeJoinState(key: String) = Unit
	}

	private fun observationEntity(id: String, second: Long) = LocationProjectionObservationEntity(
		eventId = id,
		logicalTrackingId = TRACKING_ID,
		admissionOrdinal = second,
		elapsedRealtimeNanos = second * 1_000_000_000L,
		wallTimeMs = second * 1_000L,
		latitudeDegrees = 50.0 + second * 0.0009,
		longitudeDegrees = 14.0,
		horizontalAccuracyMeters = 5f,
		altitudeMeters = 250.0,
		verticalAccuracyMeters = 4f,
		speedMetersPerSecond = null,
	)

	private fun pointEntity(id: String, second: Long) = LocationProjectionPointEntity(
		eventId = id,
		logicalTrackingId = TRACKING_ID,
		revision = 1,
		accepted = true,
		rejection = null,
		latitudeDegrees = 50.0 + second * 0.0009,
		longitudeDegrees = 14.0,
		segmentDistanceMeters = 0.0,
		cumulativeDistanceMeters = 0.0,
		estimatedSpeedMetersPerSecond = null,
		rawWgs84AltitudeMeters = 250.0,
		verticalAccuracyMeters = 4f,
		elapsedRealtimeNanos = second * 1_000_000_000L,
	)

	private fun observation(id: String, second: Long, latitude: Double, longitude: Double) = LocationObservation(
		eventId = id,
		admissionOrdinal = second,
		elapsedRealtimeNanos = second * 1_000_000_000L,
		wallTimeMs = second * 1_000L,
		payload = LocationFixPayload(latitude, longitude, 5f, 250.0, 4f, null, null, "gps"),
	)

	private fun event(id: String, admissionOrdinal: Long, observedSecond: Long) = AdmittedSourceEvent(
		eventId = SourceEventId(id),
		admissionOrdinal = admissionOrdinal,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = id,
			logicalTrackingId = LogicalTrackingId(TRACKING_ID),
			serviceRunId = null,
			source = SourceKind.LOCATION,
			sourceInstanceId = SourceInstanceId("location-test"),
			registrationGeneration = 1,
			sourceSequence = admissionOrdinal,
			configRevision = null,
			planAttribution = PlanAttribution.RECEIVE_TIME_ONLY,
			clockDomainId = "test-clock",
			observedElapsedRealtimeNanos = observedSecond * 1_000_000_000L,
			receivedElapsedRealtimeNanos = 3_000_000_000L,
			wallTimeMs = observedSecond * 1_000L,
			wallTimeUncertaintyMs = 0,
			capturedCollectedDataEpoch = 1,
			acquiredAtMs = 3_000,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = LocationFixPayload(
				latitudeDegrees = 50.0 + observedSecond * 0.0009,
				longitudeDegrees = 14.0,
				horizontalAccuracyMeters = 5f,
				altitudeMeters = 250.0,
				verticalAccuracyMeters = 4f,
				speedMetersPerSecond = null,
				bearingDegrees = null,
				provider = "gps",
			),
		),
	)

	private companion object {
		const val TRACKING_ID = "tracking-test"
	}
}
