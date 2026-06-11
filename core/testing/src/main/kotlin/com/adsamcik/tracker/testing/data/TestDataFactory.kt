package com.adsamcik.tracker.testing.data

import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession

/**
 * Factory for creating test data instances with sensible defaults.
 * Use these factories to create consistent, predictable test data across all modules.
 */
object TestDataFactory {

	// NYC Central Park coordinates as default
	private const val DEFAULT_LATITUDE = 40.7829
	private const val DEFAULT_LONGITUDE = -73.9654
	private const val DEFAULT_ALTITUDE = 10.0
	private const val DEFAULT_HORIZONTAL_ACCURACY = 5f
	private const val DEFAULT_SPEED = 1.4f // Average walking speed m/s

	/**
	 * Creates a [TrackerSession] with sensible defaults for testing.
	 *
	 * @param id Session ID (auto-generated if 0)
	 * @param start Start time in epoch millis
	 * @param end End time in epoch millis
	 * @param isUserInitiated Whether the session was started manually by user
	 * @param collections Number of data collections in this session
	 * @param distanceInM Total distance traveled in meters
	 * @param distanceOnFootInM Distance traveled on foot in meters
	 * @param distanceInVehicleInM Distance traveled in vehicle in meters
	 * @param steps Number of steps recorded
	 * @param sessionActivityId Optional activity ID association
	 */
	fun createTrackerSession(
		id: Long = 1L,
		start: Long = System.currentTimeMillis() - 3600_000L, // 1 hour ago
		end: Long = System.currentTimeMillis(),
		isUserInitiated: Boolean = true,
		collections: Int = 100,
		distanceInM: Float = 5000f,
		distanceOnFootInM: Float = 4000f,
		distanceInVehicleInM: Float = 1000f,
		steps: Int = 6500,
		sessionActivityId: Long? = null
	): TrackerSession = TrackerSession(
		id = id,
		start = start,
		end = end,
		isUserInitiated = isUserInitiated,
		collections = collections,
		distanceInM = distanceInM,
		distanceOnFootInM = distanceOnFootInM,
		distanceInVehicleInM = distanceInVehicleInM,
		steps = steps,
		sessionActivityId = sessionActivityId
	)

	/**
	 * Creates a list of [TrackerSession] instances for paging/list tests.
	 *
	 * @param count Number of sessions to generate
	 * @param baseTimeMillis Starting time for the first session
	 * @param sessionDurationMillis Duration of each session
	 * @param gapBetweenSessionsMillis Gap between sessions
	 */
	fun createTrackerSessionList(
		count: Int,
		baseTimeMillis: Long = System.currentTimeMillis() - (count * 2 * 3600_000L),
		sessionDurationMillis: Long = 3600_000L,
		gapBetweenSessionsMillis: Long = 1800_000L
	): List<TrackerSession> = (0 until count).map { index ->
		val start = baseTimeMillis + (index * (sessionDurationMillis + gapBetweenSessionsMillis))
		createTrackerSession(
			id = index + 1L,
			start = start,
			end = start + sessionDurationMillis,
			collections = 50 + (index * 10),
			distanceInM = 1000f + (index * 500f),
			steps = 1000 + (index * 500)
		)
	}

	/**
	 * Creates a [Location] with sensible defaults for testing.
	 *
	 * @param time Timestamp in epoch millis
	 * @param latitude Latitude in degrees
	 * @param longitude Longitude in degrees
	 * @param altitude Altitude in meters (nullable)
	 * @param horizontalAccuracy Horizontal accuracy in meters (nullable)
	 * @param verticalAccuracy Vertical accuracy in meters (nullable)
	 * @param speed Speed in m/s (nullable)
	 * @param speedAccuracy Speed accuracy in m/s (nullable)
	 */
	fun createLocation(
		time: Long = System.currentTimeMillis(),
		latitude: Double = DEFAULT_LATITUDE,
		longitude: Double = DEFAULT_LONGITUDE,
		altitude: Double? = DEFAULT_ALTITUDE,
		horizontalAccuracy: Float? = DEFAULT_HORIZONTAL_ACCURACY,
		verticalAccuracy: Float? = null,
		speed: Float? = DEFAULT_SPEED,
		speedAccuracy: Float? = null
	): Location = Location(
		time = time,
		latitude = latitude,
		longitude = longitude,
		altitude = altitude,
		horizontalAccuracy = horizontalAccuracy,
		verticalAccuracy = verticalAccuracy,
		speed = speed,
		speedAccuracy = speedAccuracy
	)

	/**
	 * Creates a list of [Location] instances simulating a path/route.
	 *
	 * @param count Number of locations to generate
	 * @param startTime Starting timestamp
	 * @param intervalMillis Time between each location sample
	 * @param startLatitude Starting latitude
	 * @param startLongitude Starting longitude
	 * @param latitudeStep Latitude change per sample (for simulating movement)
	 * @param longitudeStep Longitude change per sample
	 */
	fun createLocationPath(
		count: Int,
		startTime: Long = System.currentTimeMillis() - (count * 1000L),
		intervalMillis: Long = 1000L,
		startLatitude: Double = DEFAULT_LATITUDE,
		startLongitude: Double = DEFAULT_LONGITUDE,
		latitudeStep: Double = 0.0001,
		longitudeStep: Double = 0.0001
	): List<Location> = (0 until count).map { index ->
		createLocation(
			time = startTime + (index * intervalMillis),
			latitude = startLatitude + (index * latitudeStep),
			longitude = startLongitude + (index * longitudeStep)
		)
	}

	/**
	 * Creates an empty/minimal session for testing edge cases.
	 */
	fun createEmptySession(id: Long = 1L): TrackerSession = createTrackerSession(
		id = id,
		collections = 0,
		distanceInM = 0f,
		distanceOnFootInM = 0f,
		distanceInVehicleInM = 0f,
		steps = 0
	)

	/**
	 * Creates a very long session for testing large data handling.
	 */
	fun createLongSession(
		id: Long = 1L,
		durationHours: Int = 8
	): TrackerSession {
		val durationMillis = durationHours * 3600_000L
		val now = System.currentTimeMillis()
		return createTrackerSession(
			id = id,
			start = now - durationMillis,
			end = now,
			collections = durationHours * 3600, // 1 per second
			distanceInM = durationHours * 5000f, // ~5km/hour walking
			steps = durationHours * 6000 // ~6000 steps/hour
		)
	}
}
