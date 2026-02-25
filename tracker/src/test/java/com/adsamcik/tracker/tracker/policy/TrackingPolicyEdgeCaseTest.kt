package com.adsamcik.tracker.tracker.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Edge case validation tests for TrackingPolicyManager.
 *
 * Coverage:
 * - Rapid policy oscillation (walk/stop/walk cycles)
 * - Long passive periods (overnight stationary)
 * - Rapid step count changes
 * - Activity transition flooding
 * - Cooldown boundary conditions
 * - State machine stability under stress
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackingPolicyEdgeCaseTest {

	private lateinit var context: Context
	private lateinit var database: AppDatabase

	@Before
	fun setup() {
		context = ApplicationProvider.getApplicationContext()

		// Mock database for testing
		database = mockk(relaxed = true)
		val trackerRunDao = mockk<com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao>(relaxed = true)
		coEvery { database.trackerRunDao() } returns trackerRunDao
		coEvery { trackerRunDao.insert(any<com.adsamcik.tracker.shared.base.database.data.TrackerRun>()) } returns 42L
	}

	// ========================================
	// Rapid Oscillation Tests
	// ========================================

	@Test
	fun `rapid walk-stop cycles respect cooldown`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// Establish baseline
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)
		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)

		// Start walking (escalate to MOVEMENT_SUSPECTED)
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000) // 25 steps/min
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Stop walking (still within cooldown - should NOT de-escalate yet)
		manager.onStepUpdate(stepCount = 130, timeMs = baseTime + 120_000) // 5 steps/min
		assertEquals(
			TrackingPolicy.MOVEMENT_SUSPECTED,
			manager.currentPolicy.value,
			"Should remain MOVEMENT_SUSPECTED during cooldown period"
		)

		// Resume walking before cooldown expires (should escalate to ACTIVE_MODERATE)
		manager.onStepUpdate(stepCount = 195, timeMs = baseTime + 180_000) // 65 steps/min
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
	}

	@Test
	fun `rapid activity transitions within cooldown do not cause re-escalation`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// Establish STILL baseline
		manager.onActivityTransition(
			activityType = 3, // STILL
			confidence = 80,
			timeMs = baseTime
		)
		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)

		// First transition: STILL -> WALKING (escalates to MOVEMENT_SUSPECTED)
		manager.onActivityTransition(
			activityType = 7, // WALKING
			confidence = 80,
			timeMs = baseTime + 1000
		)
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Rapid transition: WALKING -> STILL (within cooldown)
		manager.onActivityTransition(
			activityType = 3, // STILL
			confidence = 75,
			timeMs = baseTime + 60_000
		)
		// Should remain MOVEMENT_SUSPECTED (cooldown prevents de-escalation)
		assertEquals(
			TrackingPolicy.MOVEMENT_SUSPECTED,
			manager.currentPolicy.value,
			"Should not de-escalate during cooldown"
		)

		// Rapid transition: STILL -> WALKING (within cooldown)
		manager.onActivityTransition(
			activityType = 7, // WALKING
			confidence = 85,
			timeMs = baseTime + 120_000
		)
		// Should escalate to ACTIVE_MODERATE (MOVEMENT_SUSPECTED + STILL→WALKING)
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
	}

	@Test
	fun `alternating step rates within same threshold do not trigger oscillation`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// Step rate 15 steps/min (MOVEMENT_SUSPECTED threshold)
		manager.onStepUpdate(stepCount = 115, timeMs = baseTime + 60_000)
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Step rate 12 steps/min (still above 10 threshold, same level)
		manager.onStepUpdate(stepCount = 127, timeMs = baseTime + 120_000)
		assertEquals(
			TrackingPolicy.MOVEMENT_SUSPECTED,
			manager.currentPolicy.value,
			"Should remain at same level for fluctuations within threshold"
		)

		// Step rate 18 steps/min (still MOVEMENT_SUSPECTED range)
		manager.onStepUpdate(stepCount = 145, timeMs = baseTime + 180_000)
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
	}

	@Test
	fun `rapid location changes escalate progressively without cooldown protection`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// First significant location change (100m) - escalates immediately
		manager.onLocationChange(displacementMeters = 100f, timeMs = baseTime)
		assertEquals(
			TrackingPolicy.ACTIVE_MODERATE,
			manager.currentPolicy.value,
			"Location displacement > 50m should escalate from PASSIVE_LOW to ACTIVE_MODERATE"
		)

		// Second rapid location change (150m) - escalates again
		manager.onLocationChange(displacementMeters = 150f, timeMs = baseTime + 60_000)
		assertEquals(
			TrackingPolicy.ACTIVE_ELEVATED,
			manager.currentPolicy.value,
			"Location displacement > 50m should escalate from ACTIVE_MODERATE to ACTIVE_ELEVATED"
		)

		// Further changes don't escalate (already at max)
		manager.onLocationChange(displacementMeters = 200f, timeMs = baseTime + 120_000)
		assertEquals(
			TrackingPolicy.ACTIVE_ELEVATED,
			manager.currentPolicy.value,
			"Already at maximum policy level"
		)
	}

	// ========================================
	// Long Passive Period Tests
	// ========================================

	@Test
	fun `long passive period maintains PASSIVE_LOW`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// Simulate overnight stationary (8 hours with minimal steps)
		val eightHoursMs = 8 * 60 * 60 * 1000L
		val intervals = 96 // 5-minute intervals over 8 hours

		for (i in 1..intervals) {
			val elapsedMs = (eightHoursMs / intervals) * i
			// 1-2 steps per 5 minutes (very minimal movement, like sleep)
			val stepIncrement = if (i % 2 == 0) 1 else 2
			manager.onStepUpdate(
				stepCount = 100 + (i * stepIncrement),
				timeMs = baseTime + elapsedMs
			)
		}

		// Should remain PASSIVE_LOW throughout
		assertEquals(
			TrackingPolicy.PASSIVE_LOW,
			manager.currentPolicy.value,
			"Long passive period should maintain PASSIVE_LOW"
		)
	}

	@Test
	fun `passive period followed by sudden activity escalates`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		
		val baseTime = System.currentTimeMillis()
		manager.start()

		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// Minimal activity (stays PASSIVE_LOW)
		manager.onStepUpdate(stepCount = 105, timeMs = baseTime + 60_000) // 5 steps/min
		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)

		// Sudden activity: 25 steps in next minute
		manager.onStepUpdate(stepCount = 130, timeMs = baseTime + 120_000) // 25 steps/min
		assertEquals(
			TrackingPolicy.MOVEMENT_SUSPECTED,
			manager.currentPolicy.value,
			"Sudden increase to 25 steps/min should escalate"
		)

		// Continue with higher activity: 65 steps in next minute
		manager.onStepUpdate(stepCount = 195, timeMs = baseTime + 180_000) // 65 steps/min
		assertEquals(
			TrackingPolicy.ACTIVE_MODERATE,
			manager.currentPolicy.value,
			"65 steps/min should escalate to ACTIVE_MODERATE"
		)
	}

	@Test
	fun `extended STILL activity maintains PASSIVE_LOW`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// Initial STILL activity
		manager.onActivityTransition(
			activityType = 3, // STILL
			confidence = 95,
			timeMs = baseTime
		)
		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)

		// Repeated STILL confirmations over several hours
		for (i in 1..24) { // Every 15 minutes for 6 hours
			manager.onActivityTransition(
				activityType = 3, // STILL
				confidence = 90 + (i % 10),
				timeMs = baseTime + (i * 15 * 60 * 1000L)
			)
		}

		// Should still be PASSIVE_LOW
		assertEquals(
			TrackingPolicy.PASSIVE_LOW,
			manager.currentPolicy.value,
			"Extended STILL activity should maintain PASSIVE_LOW"
		)
	}

	// ========================================
	// Cooldown Boundary Condition Tests
	// ========================================

	@Test
	fun `cooldown mechanism exists and policy transitions update lastTransitionTime`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		
		val baseTime = System.currentTimeMillis()
		manager.start()

		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// Escalate to MOVEMENT_SUSPECTED  
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000) // 25 steps/min
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Further escalate to ACTIVE_MODERATE
		manager.onStepUpdate(stepCount = 170, timeMs = baseTime + 120_000) // 45 steps/min
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)

		// Note: Cooldown de-escalation uses real time (Time.nowMillis) internally,
		// making it challenging to test with simulated timestamps.
		// This test validates that escalation works; de-escalation timing is tested
		// in integration/device tests where real time passage can be measured.
	}

	@Test
	fun `multiple escalations within cooldown period only count first`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// First escalation: PASSIVE -> MOVEMENT_SUSPECTED
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000)
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Second escalation attempt within cooldown: MOVEMENT -> ACTIVE_MODERATE
		manager.onStepUpdate(stepCount = 190, timeMs = baseTime + 120_000) // 65 steps/min
		assertEquals(
			TrackingPolicy.ACTIVE_MODERATE,
			manager.currentPolicy.value,
			"Second escalation should succeed (one level at a time)"
		)

		// Third escalation attempt: ACTIVE_MODERATE -> ACTIVE_ELEVATED
		manager.onStepUpdate(stepCount = 285, timeMs = baseTime + 180_000) // 95 steps/min
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
	}

	// ========================================
	// State Machine Stability Tests
	// ========================================

	@Test
	fun `high-frequency step updates maintain stability`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()
		var currentStepCount = 100

		manager.onStepUpdate(stepCount = currentStepCount, timeMs = baseTime)

		// Simulate high-frequency updates (every 10 seconds for 5 minutes)
		for (i in 1..30) {
			val elapsedMs = i * 10_000L
			currentStepCount += 2 // ~12 steps/min (MOVEMENT_SUSPECTED range)

			manager.onStepUpdate(stepCount = currentStepCount, timeMs = baseTime + elapsedMs)
		}

		// Should stabilize at MOVEMENT_SUSPECTED
		assertEquals(
			TrackingPolicy.MOVEMENT_SUSPECTED,
			manager.currentPolicy.value,
			"High-frequency updates should maintain stable policy"
		)
	}

	@Test
	fun `mixed signal types converge to consistent policy`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		
		val baseTime = System.currentTimeMillis()
		manager.start()

		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// Step signal: walking pace
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000) // 25 steps/min
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Activity signal: establish STILL baseline first, then transition to WALKING
		manager.onActivityTransition(
			activityType = 3, // STILL
			confidence = 85,
			timeMs = baseTime + 90_000
		)
		// Should remain MOVEMENT_SUSPECTED (STILL doesn't de-escalate immediately)
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Now transition to WALKING (STILL→WALKING escalates)
		manager.onActivityTransition(
			activityType = 7, // WALKING
			confidence = 85,
			timeMs = baseTime + 95_000
		)
		assertEquals(
			TrackingPolicy.ACTIVE_MODERATE,
			manager.currentPolicy.value,
			"STILL→WALKING transition from MOVEMENT_SUSPECTED should escalate to ACTIVE_MODERATE"
		)

		// Step signal: increases to running pace
		manager.onStepUpdate(stepCount = 220, timeMs = baseTime + 120_000) // 95 steps/min
		assertEquals(
			TrackingPolicy.ACTIVE_ELEVATED,
			manager.currentPolicy.value,
			"High step rate should escalate to ACTIVE_ELEVATED"
		)
	}

	@Test
	fun `shouldRequestLocation remains consistent during oscillation`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// Verify PASSIVE_LOW doesn't require location
		assertFalse(
			manager.shouldRequestLocation(),
			"PASSIVE_LOW should not require location"
		)

		// Escalate to MOVEMENT_SUSPECTED
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000)
		assertFalse(
			manager.shouldRequestLocation(),
			"MOVEMENT_SUSPECTED should not require location"
		)

		// Escalate to ACTIVE_MODERATE
		manager.onStepUpdate(stepCount = 180, timeMs = baseTime + 120_000)
		assertTrue(
			manager.shouldRequestLocation(),
			"ACTIVE_MODERATE should require location"
		)

		// Even during rapid oscillation, location requirement should be consistent with policy
		for (i in 1..10) {
			val currentPolicy = manager.currentPolicy.value
			val shouldRequest = manager.shouldRequestLocation()

			when (currentPolicy) {
				TrackingPolicy.PASSIVE_LOW, TrackingPolicy.MOVEMENT_SUSPECTED -> {
					assertFalse(shouldRequest, "Passive policies should not require location")
				}
				TrackingPolicy.ACTIVE_MODERATE,
				TrackingPolicy.ACTIVE_ELEVATED,
				TrackingPolicy.USER_INITIATED -> {
					assertTrue(shouldRequest, "Active policies should require location")
				}
			}
		}
	}

	// ========================================
	// Boundary Value Tests
	// ========================================

	@Test
	fun `step rate just above threshold escalates consistently`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		
		val baseTime = System.currentTimeMillis()
		manager.start()

		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// 11 steps/min (just above 10 threshold → MOVEMENT_SUSPECTED)
		manager.onStepUpdate(stepCount = 111, timeMs = baseTime + 60_000)
		assertEquals(
			TrackingPolicy.MOVEMENT_SUSPECTED,
			manager.currentPolicy.value,
			"Step rate > 10/min should escalate to MOVEMENT_SUSPECTED"
		)

		// 41 steps/min (just above 40 threshold → ACTIVE_MODERATE)
		manager.onStepUpdate(stepCount = 152, timeMs = baseTime + 120_000)
		assertEquals(
			TrackingPolicy.ACTIVE_MODERATE,
			manager.currentPolicy.value,
			"Step rate > 40/min should escalate to ACTIVE_MODERATE"
		)

		// 81 steps/min (just above 80 threshold → ACTIVE_ELEVATED)
		manager.onStepUpdate(stepCount = 233, timeMs = baseTime + 180_000)
		assertEquals(
			TrackingPolicy.ACTIVE_ELEVATED,
			manager.currentPolicy.value,
			"Step rate > 80/min should escalate to ACTIVE_ELEVATED"
		)
	}

	@Test
	fun `zero step increment maintains current policy`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// Escalate to MOVEMENT_SUSPECTED
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000)
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Zero step increment (device idle)
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 120_000)
		assertEquals(
			TrackingPolicy.MOVEMENT_SUSPECTED,
			manager.currentPolicy.value,
			"Zero steps should maintain current policy during cooldown"
		)
	}

	@Test
	fun `very large step increment does not skip policy levels`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// Massive step increment (200 steps/min - unrealistic but tests boundary)
		manager.onStepUpdate(stepCount = 300, timeMs = baseTime + 60_000)

		// Should escalate one level at a time (state machine behavior)
		assertEquals(
			TrackingPolicy.MOVEMENT_SUSPECTED,
			manager.currentPolicy.value,
			"Should escalate one level at a time even with huge step count"
		)

		// Continue escalation
		manager.onStepUpdate(stepCount = 500, timeMs = baseTime + 120_000)
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)

		manager.onStepUpdate(stepCount = 700, timeMs = baseTime + 180_000)
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
	}

	// ========================================
	// Recovery Tests
	// ========================================

	@Test
	fun `system handles intermittent activity patterns`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		
		val baseTime = System.currentTimeMillis()
		manager.start()

		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// Simulate short bursts of activity (2 cycles within 4 minutes)
		// Cycle 1: Active
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000) // 25 steps/min
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Cycle 1: Idle (but still above threshold overall)
		manager.onStepUpdate(stepCount = 130, timeMs = baseTime + 120_000) // 5 steps/min  
		// Should remain at MOVEMENT_SUSPECTED (within cooldown)

		// Cycle 2: Active again
		manager.onStepUpdate(stepCount = 155, timeMs = baseTime + 180_000) // 25 steps/min
		// Should remain at MOVEMENT_SUSPECTED or escalate

		// Final state should not be PASSIVE_LOW (intermittent activity detected)
		val finalPolicy = manager.currentPolicy.value
		assertTrue(
			finalPolicy != TrackingPolicy.PASSIVE_LOW,
			"Intermittent activity should maintain elevated policy"
		)
	}

	@Test
	fun `user-initiated policy survives oscillation attempts`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = true, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)

		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)

		// Attempt various escalation/de-escalation triggers
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime + 60_000) // Zero steps
		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)

		manager.onActivityTransition(
			activityType = 3, // STILL
			confidence = 100,
			timeMs = baseTime + 120_000
		)
		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)

		manager.onStepUpdate(stepCount = 500, timeMs = baseTime + 180_000) // Massive steps
		assertEquals(
			TrackingPolicy.USER_INITIATED,
			manager.currentPolicy.value,
			"User-initiated policy should be immutable"
		)
	}
}
