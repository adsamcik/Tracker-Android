package com.adsamcik.tracker.tracker.policy

import com.adsamcik.tracker.shared.base.Time
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for PolicyIntervalMapper.
 *
 * Coverage:
 * - Interval mapping for all policy levels
 * - Interval progression (longer for passive, shorter for active)
 * - Distance threshold mapping
 * - Conversion between milliseconds and seconds
 */
class PolicyIntervalMapperTest {

	@Test
	fun `PASSIVE_LOW returns 5 minute interval`() {
		val intervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.PASSIVE_LOW)
		assertEquals(5 * Time.MINUTE_IN_MILLISECONDS, intervalMs)
	}

	@Test
	fun `PASSIVE_LOW returns 300 second interval`() {
		val intervalSec = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.PASSIVE_LOW)
		assertEquals(300, intervalSec)
	}

	@Test
	fun `MOVEMENT_SUSPECTED returns 2 minute interval`() {
		val intervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.MOVEMENT_SUSPECTED)
		assertEquals(2 * Time.MINUTE_IN_MILLISECONDS, intervalMs)
	}

	@Test
	fun `MOVEMENT_SUSPECTED returns 120 second interval`() {
		val intervalSec = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.MOVEMENT_SUSPECTED)
		assertEquals(120, intervalSec)
	}

	@Test
	fun `ACTIVE_MODERATE returns 30 second interval`() {
		val intervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_MODERATE)
		assertEquals(30 * Time.SECOND_IN_MILLISECONDS, intervalMs)
	}

	@Test
	fun `ACTIVE_MODERATE returns 30 second interval (seconds)`() {
		val intervalSec = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.ACTIVE_MODERATE)
		assertEquals(30, intervalSec)
	}

	@Test
	fun `ACTIVE_ELEVATED returns 10 second interval`() {
		val intervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_ELEVATED)
		assertEquals(10 * Time.SECOND_IN_MILLISECONDS, intervalMs)
	}

	@Test
	fun `ACTIVE_ELEVATED returns 10 second interval (seconds)`() {
		val intervalSec = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.ACTIVE_ELEVATED)
		assertEquals(10, intervalSec)
	}

	@Test
	fun `USER_INITIATED returns 10 second interval`() {
		val intervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.USER_INITIATED)
		assertEquals(10 * Time.SECOND_IN_MILLISECONDS, intervalMs)
	}

	@Test
	fun `USER_INITIATED returns 10 second interval (seconds)`() {
		val intervalSec = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.USER_INITIATED)
		assertEquals(10, intervalSec)
	}

	// Interval Progression Tests

	@Test
	fun `interval decreases from PASSIVE to MOVEMENT_SUSPECTED`() {
		val passiveInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.PASSIVE_LOW)
		val movementInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.MOVEMENT_SUSPECTED)
		
		assert(movementInterval < passiveInterval) {
			"MOVEMENT_SUSPECTED interval ($movementInterval) should be less than PASSIVE_LOW ($passiveInterval)"
		}
	}

	@Test
	fun `interval decreases from MOVEMENT_SUSPECTED to ACTIVE_MODERATE`() {
		val movementInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.MOVEMENT_SUSPECTED)
		val activeModerateInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_MODERATE)
		
		assert(activeModerateInterval < movementInterval) {
			"ACTIVE_MODERATE interval ($activeModerateInterval) should be less than MOVEMENT_SUSPECTED ($movementInterval)"
		}
	}

	@Test
	fun `interval decreases from ACTIVE_MODERATE to ACTIVE_ELEVATED`() {
		val activeModerateInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_MODERATE)
		val activeElevatedInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_ELEVATED)
		
		assert(activeElevatedInterval < activeModerateInterval) {
			"ACTIVE_ELEVATED interval ($activeElevatedInterval) should be less than ACTIVE_MODERATE ($activeModerateInterval)"
		}
	}

	@Test
	fun `USER_INITIATED has same interval as ACTIVE_ELEVATED`() {
		val userInitiatedInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.USER_INITIATED)
		val activeElevatedInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_ELEVATED)
		
		assertEquals(activeElevatedInterval, userInitiatedInterval,
			"USER_INITIATED and ACTIVE_ELEVATED should have same interval for data quality")
	}

	// Distance Threshold Tests

	@Test
	fun `PASSIVE_LOW returns 50m distance threshold`() {
		val distance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.PASSIVE_LOW)
		assertEquals(50, distance)
	}

	@Test
	fun `MOVEMENT_SUSPECTED returns 30m distance threshold`() {
		val distance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.MOVEMENT_SUSPECTED)
		assertEquals(30, distance)
	}

	@Test
	fun `ACTIVE_MODERATE returns 15m distance threshold`() {
		val distance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_MODERATE)
		assertEquals(15, distance)
	}

	@Test
	fun `ACTIVE_ELEVATED returns 10m distance threshold`() {
		val distance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_ELEVATED)
		assertEquals(10, distance)
	}

	@Test
	fun `USER_INITIATED returns 10m distance threshold`() {
		val distance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.USER_INITIATED)
		assertEquals(10, distance)
	}

	// Distance Progression Tests

	@Test
	fun `distance threshold decreases from PASSIVE to MOVEMENT_SUSPECTED`() {
		val passiveDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.PASSIVE_LOW)
		val movementDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.MOVEMENT_SUSPECTED)
		
		assert(movementDistance < passiveDistance) {
			"MOVEMENT_SUSPECTED distance ($movementDistance) should be less than PASSIVE_LOW ($passiveDistance)"
		}
	}

	@Test
	fun `distance threshold decreases from MOVEMENT_SUSPECTED to ACTIVE_MODERATE`() {
		val movementDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.MOVEMENT_SUSPECTED)
		val activeModerateDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_MODERATE)
		
		assert(activeModerateDistance < movementDistance) {
			"ACTIVE_MODERATE distance ($activeModerateDistance) should be less than MOVEMENT_SUSPECTED ($movementDistance)"
		}
	}

	@Test
	fun `distance threshold decreases from ACTIVE_MODERATE to ACTIVE_ELEVATED`() {
		val activeModerateDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_MODERATE)
		val activeElevatedDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_ELEVATED)
		
		assert(activeElevatedDistance < activeModerateDistance) {
			"ACTIVE_ELEVATED distance ($activeElevatedDistance) should be less than ACTIVE_MODERATE ($activeModerateDistance)"
		}
	}

	@Test
	fun `USER_INITIATED has same distance threshold as ACTIVE_ELEVATED`() {
		val userInitiatedDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.USER_INITIATED)
		val activeElevatedDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_ELEVATED)
		
		assertEquals(activeElevatedDistance, userInitiatedDistance,
			"USER_INITIATED and ACTIVE_ELEVATED should have same distance threshold for precision")
	}

	// Battery Optimization Strategy Tests

	@Test
	fun `passive interval is at least 10x longer than active`() {
		val passiveIntervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.PASSIVE_LOW)
		val activeElevatedIntervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_ELEVATED)
		
		assert(passiveIntervalMs >= activeElevatedIntervalMs * 10) {
			"PASSIVE_LOW interval should be at least 10x ACTIVE_ELEVATED for battery optimization"
		}
	}

	@Test
	fun `movement interval is between passive and active`() {
		val passiveInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.PASSIVE_LOW)
		val movementInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.MOVEMENT_SUSPECTED)
		val activeInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_ELEVATED)
		
		assert(movementInterval > activeInterval && movementInterval < passiveInterval) {
			"MOVEMENT_SUSPECTED interval should be between ACTIVE_ELEVATED and PASSIVE_LOW"
		}
	}

	// Conversion Accuracy Tests

	@Test
	fun `millisecond to second conversion is accurate for all policies`() {
		TrackingPolicy.entries.forEach { policy ->
			val intervalMs = PolicyIntervalMapper.getIntervalMs(policy)
			val intervalSec = PolicyIntervalMapper.getIntervalSeconds(policy)
			val expectedSec = (intervalMs / Time.SECOND_IN_MILLISECONDS).toInt()
			
			assertEquals(expectedSec, intervalSec, 
				"Conversion accuracy failed for $policy: expected $expectedSec, got $intervalSec")
		}
	}
}
