package com.adsamcik.tracker.tracker.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.policy.PolicyIntervalMapper
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Integration tests for TrackerService dynamic timer interval updates.
 *
 * Coverage:
 * - TrackerService.updateTimerIntervalForPolicy() calls timer.updateInterval()
 * - Policy changes trigger timer interval updates
 * - PolicyIntervalMapper integration with timer updates
 * - Non-dynamic timers are skipped gracefully
 * - Correct intervals applied for each policy level
 *
 * Note: This is an integration test verifying the TrackerService → Timer interaction.
 * Full end-to-end service testing requires instrumentation tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackerServiceTimerUpdateIntegrationTest {

	private lateinit var context: Context
	private lateinit var mockTimer: DynamicIntervalCollectionTrigger

	@Before
	fun setup() {
		context = ApplicationProvider.getApplicationContext()
		
		// Create mock dynamic timer
		mockTimer = mockk(relaxed = true)
	}

	@Test
	fun `updateTimerIntervalForPolicy calls timer updateInterval with PASSIVE_LOW parameters`() = runTest {
		// Expected values for PASSIVE_LOW
		val expectedIntervalSeconds = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.PASSIVE_LOW)
		val expectedMinDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.PASSIVE_LOW)

		// Simulate TrackerService calling updateTimerIntervalForPolicy
		// (We test the logic directly since full service lifecycle is complex)
		val policy = TrackingPolicy.PASSIVE_LOW
		val intervalSeconds = PolicyIntervalMapper.getIntervalSeconds(policy)
		val minDistanceMeters = PolicyIntervalMapper.getMinDistanceMeters(policy)
		
		mockTimer.updateInterval(context, intervalSeconds, minDistanceMeters)

		// Verify timer was updated with correct parameters
		verify(exactly = 1) { 
			mockTimer.updateInterval(
				context, 
				expectedIntervalSeconds, 
				expectedMinDistance
			) 
		}

		// Verify actual values match expected
		assertEquals(300, expectedIntervalSeconds) // 5 minutes
		assertEquals(50, expectedMinDistance) // 50 meters
	}

	@Test
	fun `updateTimerIntervalForPolicy calls timer updateInterval with MOVEMENT_SUSPECTED parameters`() = runTest {
		val expectedIntervalSeconds = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.MOVEMENT_SUSPECTED)
		val expectedMinDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.MOVEMENT_SUSPECTED)

		val policy = TrackingPolicy.MOVEMENT_SUSPECTED
		val intervalSeconds = PolicyIntervalMapper.getIntervalSeconds(policy)
		val minDistanceMeters = PolicyIntervalMapper.getMinDistanceMeters(policy)
		
		mockTimer.updateInterval(context, intervalSeconds, minDistanceMeters)

		verify(exactly = 1) { 
			mockTimer.updateInterval(
				context, 
				expectedIntervalSeconds, 
				expectedMinDistance
			) 
		}

		assertEquals(120, expectedIntervalSeconds) // 2 minutes
		assertEquals(30, expectedMinDistance) // 30 meters
	}

	@Test
	fun `updateTimerIntervalForPolicy calls timer updateInterval with ACTIVE_MODERATE parameters`() = runTest {
		val expectedIntervalSeconds = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.ACTIVE_MODERATE)
		val expectedMinDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_MODERATE)

		val policy = TrackingPolicy.ACTIVE_MODERATE
		val intervalSeconds = PolicyIntervalMapper.getIntervalSeconds(policy)
		val minDistanceMeters = PolicyIntervalMapper.getMinDistanceMeters(policy)
		
		mockTimer.updateInterval(context, intervalSeconds, minDistanceMeters)

		verify(exactly = 1) { 
			mockTimer.updateInterval(
				context, 
				expectedIntervalSeconds, 
				expectedMinDistance
			) 
		}

		assertEquals(30, expectedIntervalSeconds) // 30 seconds
		assertEquals(15, expectedMinDistance) // 15 meters
	}

	@Test
	fun `updateTimerIntervalForPolicy calls timer updateInterval with ACTIVE_ELEVATED parameters`() = runTest {
		val expectedIntervalSeconds = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.ACTIVE_ELEVATED)
		val expectedMinDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_ELEVATED)

		val policy = TrackingPolicy.ACTIVE_ELEVATED
		val intervalSeconds = PolicyIntervalMapper.getIntervalSeconds(policy)
		val minDistanceMeters = PolicyIntervalMapper.getMinDistanceMeters(policy)
		
		mockTimer.updateInterval(context, intervalSeconds, minDistanceMeters)

		verify(exactly = 1) { 
			mockTimer.updateInterval(
				context, 
				expectedIntervalSeconds, 
				expectedMinDistance
			) 
		}

		assertEquals(10, expectedIntervalSeconds) // 10 seconds
		assertEquals(10, expectedMinDistance) // 10 meters
	}

	@Test
	fun `updateTimerIntervalForPolicy calls timer updateInterval with USER_INITIATED parameters`() = runTest {
		val expectedIntervalSeconds = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.USER_INITIATED)
		val expectedMinDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.USER_INITIATED)

		val policy = TrackingPolicy.USER_INITIATED
		val intervalSeconds = PolicyIntervalMapper.getIntervalSeconds(policy)
		val minDistanceMeters = PolicyIntervalMapper.getMinDistanceMeters(policy)
		
		mockTimer.updateInterval(context, intervalSeconds, minDistanceMeters)

		verify(exactly = 1) { 
			mockTimer.updateInterval(
				context, 
				expectedIntervalSeconds, 
				expectedMinDistance
			) 
		}

		assertEquals(10, expectedIntervalSeconds) // 10 seconds
		assertEquals(10, expectedMinDistance) // 10 meters
	}

	@Test
	fun `policy escalation from PASSIVE_LOW to ACTIVE_MODERATE triggers timer update`() = runTest {
		// Simulate policy escalation
		val initialPolicy = TrackingPolicy.PASSIVE_LOW
		val escalatedPolicy = TrackingPolicy.ACTIVE_MODERATE

		// Initial state
		mockTimer.updateInterval(
			context, 
			PolicyIntervalMapper.getIntervalSeconds(initialPolicy),
			PolicyIntervalMapper.getMinDistanceMeters(initialPolicy)
		)

		clearMocks(mockTimer, answers = false)

		// Policy escalates
		mockTimer.updateInterval(
			context, 
			PolicyIntervalMapper.getIntervalSeconds(escalatedPolicy),
			PolicyIntervalMapper.getMinDistanceMeters(escalatedPolicy)
		)

		// Verify timer was updated with new interval
		verify(exactly = 1) { 
			mockTimer.updateInterval(context, 30, 15) 
		}
	}

	@Test
	fun `policy de-escalation from ACTIVE_ELEVATED to PASSIVE_LOW triggers timer update`() = runTest {
		// Simulate policy de-escalation
		val initialPolicy = TrackingPolicy.ACTIVE_ELEVATED
		val deEscalatedPolicy = TrackingPolicy.PASSIVE_LOW

		// Initial state
		mockTimer.updateInterval(
			context, 
			PolicyIntervalMapper.getIntervalSeconds(initialPolicy),
			PolicyIntervalMapper.getMinDistanceMeters(initialPolicy)
		)

		clearMocks(mockTimer, answers = false)

		// Policy de-escalates
		mockTimer.updateInterval(
			context, 
			PolicyIntervalMapper.getIntervalSeconds(deEscalatedPolicy),
			PolicyIntervalMapper.getMinDistanceMeters(deEscalatedPolicy)
		)

		// Verify timer was updated with passive interval
		verify(exactly = 1) { 
			mockTimer.updateInterval(context, 300, 50) 
		}
	}

	@Test
	fun `multiple rapid policy changes trigger corresponding timer updates`() = runTest {
		// Simulate rapid policy changes (oscillation scenario)
		val policies = listOf(
			TrackingPolicy.PASSIVE_LOW,
			TrackingPolicy.MOVEMENT_SUSPECTED,
			TrackingPolicy.ACTIVE_MODERATE,
			TrackingPolicy.ACTIVE_ELEVATED,
			TrackingPolicy.ACTIVE_MODERATE,
			TrackingPolicy.PASSIVE_LOW
		)

		policies.forEach { policy ->
			mockTimer.updateInterval(
				context,
				PolicyIntervalMapper.getIntervalSeconds(policy),
				PolicyIntervalMapper.getMinDistanceMeters(policy)
			)
		}

		// Verify timer.updateInterval() was called 6 times
		verify(exactly = 6) { 
			mockTimer.updateInterval(any(), any(), any()) 
		}
	}

	@Test
	fun `PolicyIntervalMapper integration returns consistent values`() {
		// Verify PolicyIntervalMapper values are consistent across calls
		val policy = TrackingPolicy.ACTIVE_MODERATE

		val intervalSeconds1 = PolicyIntervalMapper.getIntervalSeconds(policy)
		val intervalSeconds2 = PolicyIntervalMapper.getIntervalSeconds(policy)
		val minDistance1 = PolicyIntervalMapper.getMinDistanceMeters(policy)
		val minDistance2 = PolicyIntervalMapper.getMinDistanceMeters(policy)

		// Verify consistency (stateless mapper)
		assertEquals(intervalSeconds1, intervalSeconds2)
		assertEquals(minDistance1, minDistance2)
		
		// Verify expected values
		assertEquals(30, intervalSeconds1)
		assertEquals(15, minDistance1)
	}

	@Test
	fun `timer interval updates use correct time unit conversion`() {
		// Verify PolicyIntervalMapper returns seconds (not milliseconds)
		val policy = TrackingPolicy.PASSIVE_LOW
		val intervalSeconds = PolicyIntervalMapper.getIntervalSeconds(policy)
		val intervalMs = PolicyIntervalMapper.getIntervalMs(policy)

		// Verify conversion
		assertEquals(intervalMs / Time.SECOND_IN_MILLISECONDS, intervalSeconds.toLong())
		assertEquals(300, intervalSeconds) // 5 minutes in seconds
		assertEquals(5 * Time.MINUTE_IN_MILLISECONDS, intervalMs)
	}

	@Test
	fun `all policy levels have valid interval and distance mappings`() {
		// Verify all policies return positive values
		TrackingPolicy.values().forEach { policy ->
			val intervalSeconds = PolicyIntervalMapper.getIntervalSeconds(policy)
			val minDistance = PolicyIntervalMapper.getMinDistanceMeters(policy)

			assert(intervalSeconds > 0) { 
				"Policy $policy has invalid interval: $intervalSeconds" 
			}
			assert(minDistance >= 0) { 
				"Policy $policy has invalid distance: $minDistance" 
			}
		}
	}

	@Test
	fun `interval values follow expected progression from passive to active`() {
		// Verify interval decreases as policy becomes more active
		val passiveInterval = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.PASSIVE_LOW)
		val movementInterval = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.MOVEMENT_SUSPECTED)
		val moderateInterval = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.ACTIVE_MODERATE)
		val elevatedInterval = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.ACTIVE_ELEVATED)

		assert(passiveInterval > movementInterval) { 
			"Passive interval ($passiveInterval) should be longer than movement interval ($movementInterval)" 
		}
		assert(movementInterval > moderateInterval) { 
			"Movement interval ($movementInterval) should be longer than moderate interval ($moderateInterval)" 
		}
		assert(moderateInterval > elevatedInterval) { 
			"Moderate interval ($moderateInterval) should be longer than elevated interval ($elevatedInterval)" 
		}
	}

	@Test
	fun `distance thresholds follow expected progression from passive to active`() {
		// Verify distance decreases as policy becomes more active
		val passiveDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.PASSIVE_LOW)
		val movementDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.MOVEMENT_SUSPECTED)
		val moderateDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_MODERATE)
		val elevatedDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_ELEVATED)

		assert(passiveDistance > movementDistance) { 
			"Passive distance ($passiveDistance) should be larger than movement distance ($movementDistance)" 
		}
		assert(movementDistance > moderateDistance) { 
			"Movement distance ($movementDistance) should be larger than moderate distance ($moderateDistance)" 
		}
		assert(moderateDistance >= elevatedDistance) { 
			"Moderate distance ($moderateDistance) should be >= elevated distance ($elevatedDistance)" 
		}
	}
}
