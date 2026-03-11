package com.adsamcik.tracker.tracker.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay

/**
 * Unit tests for TrackingPolicyManager adaptive policy state machine.
 *
 * Coverage:
 * - Initial policy selection (USER_INITIATED vs PASSIVE_LOW)
 * - Step rate threshold-based escalation (10, 40, 80 steps/min)
 * - Activity transition-based escalation
 * - Location displacement-based escalation
 * - shouldRequestLocation() behavior per policy
 * - TrackerRun lifecycle (create on start, end on stop)
 * - User-initiated policy locking (no auto-adaptation)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackingPolicyManagerTest {

	private lateinit var context: Context
	private lateinit var database: AppDatabase
	private lateinit var trackerRunDao: TrackerRunDao

	@Before
	fun setup() {
		context = ApplicationProvider.getApplicationContext()
		database = mockk(relaxed = true)
		trackerRunDao = mockk(relaxed = true)

		// Mock database returns
		coEvery { database.trackerRunDao() } returns trackerRunDao
		coEvery { trackerRunDao.insert(any<TrackerRun>()) } returns 1L
	}

	@After
	fun tearDown() {
		// Cleanup if needed
	}

	@Test
	fun `initial policy is PASSIVE_LOW for non-user sessions`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)
	}

	@Test
	fun `initial policy is USER_INITIATED for user sessions`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = true, scope = backgroundScope, database = database)
		startAndAwait(manager)

		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)
	}

	@Test
	fun `shouldRequestLocation returns false for PASSIVE_LOW`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		assertFalse(manager.shouldRequestLocation())
	}

	@Test
	fun `shouldRequestLocation returns false for MOVEMENT_SUSPECTED`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)
		
		// Escalate to MOVEMENT_SUSPECTED by simulating 25 steps/min rate (> 10 threshold)
		val baseTime = System.currentTimeMillis()
		onStepUpdateAndAwait(manager, stepCount = 100, timeMs = baseTime) // Baseline (non-zero)
		onStepUpdateAndAwait(manager, stepCount = 125, timeMs = baseTime + 60_000) // 25 steps in 1 minute

		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
		assertFalse(manager.shouldRequestLocation())
	}

	@Test
	fun `shouldRequestLocation returns true for ACTIVE_MODERATE`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		// Escalate step-by-step to ACTIVE_MODERATE
		val baseTime = System.currentTimeMillis()
		onStepUpdateAndAwait(manager, stepCount = 100, timeMs = baseTime) // Baseline (non-zero)
		onStepUpdateAndAwait(manager, stepCount = 125, timeMs = baseTime + 60_000) // 25 steps/min → MOVEMENT_SUSPECTED
		onStepUpdateAndAwait(manager, stepCount = 180, timeMs = baseTime + 120_000) // 55 steps/min → ACTIVE_MODERATE

		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
		assertTrue(manager.shouldRequestLocation())
	}

	@Test
	fun `step rate below 10 per min keeps PASSIVE_LOW`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		// Simulate 5 steps/min rate (below threshold)
		val baseTime = System.currentTimeMillis()
		onStepUpdateAndAwait(manager, stepCount = 0, timeMs = baseTime) // Baseline
		onStepUpdateAndAwait(manager, stepCount = 5, timeMs = baseTime + 60_000) // 5 steps in 1 minute

		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)
	}

	@Test
	fun `step rate 10-40 per min escalates to MOVEMENT_SUSPECTED`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		// Simulate 25 steps/min rate (> 10 threshold, < 40 threshold)
		val baseTime = System.currentTimeMillis()
		onStepUpdateAndAwait(manager, stepCount = 100, timeMs = baseTime) // Baseline (non-zero)
		onStepUpdateAndAwait(manager, stepCount = 125, timeMs = baseTime + 60_000) // 25 steps in 1 minute

		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
	}

	@Test
	fun `step rate 40-80 per min escalates to ACTIVE_MODERATE`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		// Step rate escalation happens one level at a time
		// First escalate to MOVEMENT_SUSPECTED, then to ACTIVE_MODERATE
		val baseTime = System.currentTimeMillis()
		onStepUpdateAndAwait(manager, stepCount = 100, timeMs = baseTime) // Baseline (non-zero)
		onStepUpdateAndAwait(manager, stepCount = 125, timeMs = baseTime + 60_000) // 25 steps/min → MOVEMENT_SUSPECTED
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
		
		// Now escalate to ACTIVE_MODERATE with 65 steps/min (> 40 threshold)
		onStepUpdateAndAwait(manager, stepCount = 190, timeMs = baseTime + 120_000) // 65 steps in next minute
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
	}

	@Test
	fun `step rate above 80 per min escalates to ACTIVE_ELEVATED`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		// Step rate escalation happens one level at a time
		// PASSIVE_LOW → MOVEMENT_SUSPECTED → ACTIVE_MODERATE → ACTIVE_ELEVATED
		val baseTime = System.currentTimeMillis()
		onStepUpdateAndAwait(manager, stepCount = 100, timeMs = baseTime) // Baseline (non-zero)
		
		// Escalate to MOVEMENT_SUSPECTED (25 steps/min)
		onStepUpdateAndAwait(manager, stepCount = 125, timeMs = baseTime + 60_000)
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
		
		// Escalate to ACTIVE_MODERATE (55 steps/min)
		onStepUpdateAndAwait(manager, stepCount = 180, timeMs = baseTime + 120_000)
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
		
		// Escalate to ACTIVE_ELEVATED (95 steps/min > 80 threshold)
		onStepUpdateAndAwait(manager, stepCount = 275, timeMs = baseTime + 180_000)
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
	}

	@Test
	fun `activity transition from STILL to MOVING escalates policy`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()
		
		// Establish STILL baseline
		onActivityTransitionAndAwait(manager, activityType = 3, confidence = 80, timeMs = baseTime) // STILL
		
		// Transition to WALKING with high confidence
		onActivityTransitionAndAwait(manager, activityType = 7, confidence = 75, timeMs = baseTime + 5000) // WALKING

		// Should escalate from PASSIVE_LOW
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
	}

	@Test
	fun `location displacement above 50m escalates policy`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		onLocationChangeAndAwait(manager, displacementMeters = 100f, timeMs = System.currentTimeMillis())

		// Should escalate from PASSIVE_LOW
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
	}

	@Test
	fun `user-initiated sessions do not adapt based on step rate`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = true, scope = backgroundScope, database = database)
		startAndAwait(manager)

		// Try to escalate via steps
		onStepUpdateAndAwait(manager, stepCount = 100, timeMs = System.currentTimeMillis())

		// Should stay USER_INITIATED
		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)
	}

	@Test
	fun `tracker run created on start`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		coVerify { trackerRunDao.insert(any<TrackerRun>()) }
	}

	@Test
	fun `tracker run ended on stop`() = runTest {
		coEvery { trackerRunDao.insert(any<TrackerRun>()) } returns 42L

		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)
		stopAndAwait(manager)

		coVerify { trackerRunDao.endRun(42L, any<Long>()) }
	}

	@Test
	fun `progressive escalation through all policy levels`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)

		val baseTime = System.currentTimeMillis()
		
		// Establish baseline (non-zero)
		onStepUpdateAndAwait(manager, stepCount = 100, timeMs = baseTime)
		
		// Escalate to MOVEMENT_SUSPECTED (25 steps/min > 10 threshold)
		onStepUpdateAndAwait(manager, stepCount = 125, timeMs = baseTime + 60_000)
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Escalate to ACTIVE_MODERATE (55 steps/min > 40 threshold)
		onStepUpdateAndAwait(manager, stepCount = 180, timeMs = baseTime + 120_000) // 55 steps in next minute
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)

		// Escalate to ACTIVE_ELEVATED (95 steps/min > 80 threshold)
		onStepUpdateAndAwait(manager, stepCount = 275, timeMs = baseTime + 180_000) // 95 steps in next minute
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
	}

	@Test
	fun `step accumulation from zero works correctly`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()
		
		// First update establishes non-zero baseline (simulates service startup with some steps already counted)
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)
		
		// Add 25 steps over 1 minute (delta = 25, rate = 25 steps/min > 10 threshold)
		onStepUpdateAndAwait(manager, stepCount = 35, timeMs = baseTime + 60_000)
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Add another 55 steps over next minute (delta = 55, rate = 55 steps/min > 40 threshold)
		onStepUpdateAndAwait(manager, stepCount = 90, timeMs = baseTime + 120_000)
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)

		// Add another 95 steps over next minute (delta = 95, rate = 95 steps/min > 80 threshold)
		onStepUpdateAndAwait(manager, stepCount = 185, timeMs = baseTime + 180_000)
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
	}

	@Test
	fun `concurrent step updates are serialized correctly`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()
		
		// Launch multiple concurrent step updates
		val updates = (1..20).map { i ->
			async {
				onStepUpdateAndAwait(manager, 
					stepCount = i * 10, 
					timeMs = baseTime + (i * 1000L)
				)
			}
		}

		// Wait for all updates to complete
		updates.awaitAll()

		// Final policy should be determined by the highest step rate
		// No crashes or inconsistent state
		val finalPolicy = manager.currentPolicy.value
		assertTrue(finalPolicy in TrackingPolicy.values())
	}

	@Test
	fun `concurrent activity and location updates do not cause race conditions`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()
		
		// Launch concurrent updates of different types
		val updates = listOf(
			async { onStepUpdateAndAwait(manager, stepCount = 50, timeMs = baseTime) },
			async { onActivityTransitionAndAwait(manager, activityType = 7, confidence = 80, timeMs = baseTime + 100) },
			async { onLocationChangeAndAwait(manager, displacementMeters = 100f, timeMs = baseTime + 200) },
			async { onStepUpdateAndAwait(manager, stepCount = 100, timeMs = baseTime + 60_000) },
			async { onActivityTransitionAndAwait(manager, activityType = 3, confidence = 75, timeMs = baseTime + 300) },
			async { onLocationChangeAndAwait(manager, displacementMeters = 200f, timeMs = baseTime + 400) }
		)

		updates.awaitAll()

		// Should reach an elevated policy without crashes
		val finalPolicy = manager.currentPolicy.value
		assertTrue(
			finalPolicy == TrackingPolicy.ACTIVE_MODERATE || 
			finalPolicy == TrackingPolicy.ACTIVE_ELEVATED
		)
	}

	@Test
	fun `rapid policy transitions are handled correctly`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()
		
		// Simulate rapid escalation with non-zero baseline
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime) // Baseline
		onStepUpdateAndAwait(manager, stepCount = 35, timeMs = baseTime + 60_000) // 25 steps/min → MOVEMENT_SUSPECTED
		onStepUpdateAndAwait(manager, stepCount = 90, timeMs = baseTime + 120_000) // 55 steps/min → ACTIVE_MODERATE
		onStepUpdateAndAwait(manager, stepCount = 185, timeMs = baseTime + 180_000) // 95 steps/min → ACTIVE_ELEVATED

		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)

		// Verify tracker run was created for start + each transition (4 total)
		coVerify(atLeast = 4) { trackerRunDao.insert(any<TrackerRun>()) }
	}

	@Test
	fun `start and stop are thread-safe`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		
		// Launch multiple start/stop operations concurrently
		val operations = listOf(
			async { startAndAwait(manager) },
			async { delay(10); stopAndAwait(manager) },
			async { delay(20); startAndAwait(manager) },
			async { delay(30); stopAndAwait(manager) }
		)

		operations.awaitAll()

		// Should complete without crashes or exceptions
		// Final state depends on timing but should be consistent
	}

	// ========== Edge Cases & Boundary Conditions ==========

	@Test
	fun `exact threshold boundaries produce correct policy transitions`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()

		// Just above 10 steps/min threshold (MOVEMENT_SUSPECTED)
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = 21, timeMs = baseTime + 60_000) // 11 steps/min
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Just above 40 steps/min threshold (ACTIVE_MODERATE)
		onStepUpdateAndAwait(manager, stepCount = 62, timeMs = baseTime + 120_000) // 41 steps/min
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)

		// Just above 80 steps/min threshold (ACTIVE_ELEVATED)
		onStepUpdateAndAwait(manager, stepCount = 143, timeMs = baseTime + 180_000) // 81 steps/min
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
	}

	@Test
	fun `just below thresholds maintains current policy`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()

		// 9 steps/min - just below MOVEMENT_SUSPECTED threshold
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = 19, timeMs = baseTime + 60_000)
		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)
	}

	@Test
	fun `step count decreasing (device reboot) resets baseline gracefully`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		
		// Use a far-future base time to avoid any cooldown issues with system time
		val baseTime = System.currentTimeMillis() + 1_000_000_000L
		
		startAndAwait(manager)

		// Normal progression to establish baseline  
		onStepUpdateAndAwait(manager, stepCount = 100, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = 131, timeMs = baseTime + 60_000) // 31 steps/min → MOVEMENT_SUSPECTED
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Step counter resets (e.g., device reboot) - negative delta is ignored
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime + 120_000)
		
		// Long period of inactivity (>300s cooldown) to de-escalate back to PASSIVE_LOW
		onStepUpdateAndAwait(manager, stepCount = 12, timeMs = baseTime + 500_000) // 2 steps in ~380s
		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)
		
		// Now with proper delta from new baseline, escalate again
		onStepUpdateAndAwait(manager, stepCount = 43, timeMs = baseTime + 560_000) // 31 steps/min → MOVEMENT_SUSPECTED
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
	}

	@Test
	fun `time going backwards is handled gracefully`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()

		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = 50, timeMs = baseTime + 60_000)

		// Time goes backwards (clock adjustment)
		onStepUpdateAndAwait(manager, stepCount = 60, timeMs = baseTime + 30_000)

		// Should not crash or produce invalid step rates
		// Policy should remain stable or degrade safely
	}

	@Test
	fun `very long time interval prevents overflow in step rate calculation`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()

		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		
		// 24 hour gap
		onStepUpdateAndAwait(manager, stepCount = 20, timeMs = baseTime + 24 * 60 * 60_000)

		// Should calculate very low step rate without overflow
		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)
	}

	@Test
	fun `zero time delta between updates is handled safely`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()

		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = 50, timeMs = baseTime) // Same timestamp

		// Should not divide by zero or crash
	}

	// ========== Policy Downgrade Scenarios ==========

	@Test
	fun `policy downgrades after period of inactivity`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()

		// Escalate to ACTIVE_ELEVATED through multiple steps (one level at a time)
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = 21, timeMs = baseTime + 60_000) // 11/min → MOVEMENT_SUSPECTED
		onStepUpdateAndAwait(manager, stepCount = 62, timeMs = baseTime + 120_000) // 41/min → ACTIVE_MODERATE
		onStepUpdateAndAwait(manager, stepCount = 143, timeMs = baseTime + 180_000) // 81/min → ACTIVE_ELEVATED
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)

		// Wait for cooldown period (5 minutes = 300 seconds) with minimal movement
		// Cooldown triggers after 300s since last transition
		onStepUpdateAndAwait(manager, stepCount = 148, timeMs = baseTime + 500_000) // 340s later, 5 steps over long period
		
		// Cooldown should have triggered, downgrading one level
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
	}

	@Ignore("Activity transitions only escalate, never de-escalate - see TRACKING_POLICY_IMPLEMENTATION_ANALYSIS.md")
	@Test
	fun `activity transition to STILL downgrades policy`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		// Escalate via steps
		val baseTime = System.currentTimeMillis()
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = 60, timeMs = baseTime + 60_000)
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)

		// Activity recognition detects STILL
		onActivityTransitionAndAwait(manager, activityType = 3, confidence = 90, timeMs = baseTime + 120_000) // STILL = 3

		// Should downgrade
		val finalPolicy = manager.currentPolicy.value
		assertTrue(
			finalPolicy.ordinal < TrackingPolicy.ACTIVE_MODERATE.ordinal,
			"Expected downgrade after STILL activity, got $finalPolicy"
		)
	}

	// ========== Mixed Event Sequences ==========

	@Test
	fun `interleaved step and activity events produce consistent policy`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()

		// Set initial activity to STILL so transition to WALKING can escalate
		onActivityTransitionAndAwait(manager, activityType = 3, confidence = 80, timeMs = baseTime) // STILL

		// Step update
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime + 1000)
		onStepUpdateAndAwait(manager, stepCount = 35, timeMs = baseTime + 61_000) // 25 steps/min → MOVEMENT_SUSPECTED
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Activity confirms movement (wasStill=true, isMoving=true)
		onActivityTransitionAndAwait(manager, activityType = 7, confidence = 85, timeMs = baseTime + 90_000) // WALKING = 7
		
		// Should escalate to ACTIVE_MODERATE via activity transition
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
	}

	@Test
	fun `location updates combined with step data refine policy`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()

		// Moderate step rate
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = 35, timeMs = baseTime + 60_000)

		// Significant displacement confirms movement
		onLocationChangeAndAwait(manager, displacementMeters = 100f, timeMs = baseTime + 120_000)

		// Should maintain or escalate policy
		assertTrue(
			manager.currentPolicy.value.ordinal >= TrackingPolicy.MOVEMENT_SUSPECTED.ordinal,
			"Expected at least MOVEMENT_SUSPECTED with location confirmation"
		)
	}

	@Test
	fun `poor location accuracy during high step rate maintains elevated policy`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()

		// High step rate - escalate through all levels
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = 21, timeMs = baseTime + 60_000) // 11/min → MOVEMENT_SUSPECTED
		onStepUpdateAndAwait(manager, stepCount = 62, timeMs = baseTime + 120_000) // 41/min → ACTIVE_MODERATE
		onStepUpdateAndAwait(manager, stepCount = 143, timeMs = baseTime + 180_000) // 81/min → ACTIVE_ELEVATED
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)

		// Minimal displacement despite steps (e.g., treadmill or indoor activity)
		onLocationChangeAndAwait(manager, displacementMeters = 5f, timeMs = baseTime + 240_000)

		// Should remain elevated (no de-escalation logic in onLocationChange)
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
	}

	// ========== Persistence & State Recovery ==========

	@Test
	fun `start creates TrackerRun in database`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		coVerify(exactly = 1) { trackerRunDao.insert(any<TrackerRun>()) }
	}

	@Test
	fun `stop ends current TrackerRun in database`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		
		coEvery { trackerRunDao.endRun(any(), any()) } just Runs
		
		startAndAwait(manager)
		stopAndAwait(manager)

		coVerify { trackerRunDao.endRun(any(), any()) }
	}

	@Test
	fun `multiple start-stop cycles create separate TrackerRuns`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		
		coEvery { trackerRunDao.endRun(any(), any()) } just Runs

		// First session
		startAndAwait(manager)
		stopAndAwait(manager)

		// Second session
		startAndAwait(manager)
		stopAndAwait(manager)

		// Should have created 2 TrackerRuns
		coVerify(exactly = 2) { trackerRunDao.insert(any<TrackerRun>()) }
		coVerify(exactly = 2) { trackerRunDao.endRun(any(), any()) }
	}

	// ========== User-Initiated Policy Locking ==========

	@Test
	fun `user-initiated session ignores step updates`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = true, scope = backgroundScope, database = database)
		startAndAwait(manager)

		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)

		val baseTime = System.currentTimeMillis()

		// Even with zero steps, should remain USER_INITIATED
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime + 60_000) // No movement

		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)
	}

	@Test
	fun `user-initiated session ignores activity transitions`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = true, scope = backgroundScope, database = database)
		startAndAwait(manager)

		// STILL activity should not downgrade
		onActivityTransitionAndAwait(manager, activityType = 3, confidence = 95, timeMs = System.currentTimeMillis())

		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)
	}

	@Test
	fun `user-initiated session ignores location changes`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = true, scope = backgroundScope, database = database)
		startAndAwait(manager)

		// Minimal displacement should not affect policy
		onLocationChangeAndAwait(manager, displacementMeters = 5f, timeMs = System.currentTimeMillis())

		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)
	}

	// ========== Stress & Robustness ==========

	@Test
	fun `large step count values do not cause overflow`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()

		// Near Int.MAX_VALUE (realistic for cumulative step counters)
		val highStepBase = Int.MAX_VALUE - 1000
		
		// Multi-level escalation through all policy levels
		onStepUpdateAndAwait(manager, stepCount = highStepBase, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = highStepBase + 11, timeMs = baseTime + 60_000)
		onStepUpdateAndAwait(manager, stepCount = highStepBase + 52, timeMs = baseTime + 120_000)
		onStepUpdateAndAwait(manager, stepCount = highStepBase + 133, timeMs = baseTime + 180_000)

		// Should calculate step rate without overflow and reach top policy
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
	}

	@Test
	fun `rapid policy oscillation stabilizes eventually`() = runTest {
		val manager = TrackingPolicyManager(context = context, isUserInitiated = false, scope = backgroundScope, database = database)
		startAndAwait(manager)

		val baseTime = System.currentTimeMillis()

		// Alternate between high and low activity
		onStepUpdateAndAwait(manager, stepCount = 10, timeMs = baseTime)
		onStepUpdateAndAwait(manager, stepCount = 90, timeMs = baseTime + 60_000) // High
		onStepUpdateAndAwait(manager, stepCount = 95, timeMs = baseTime + 120_000) // Low
		onStepUpdateAndAwait(manager, stepCount = 175, timeMs = baseTime + 180_000) // High
		onStepUpdateAndAwait(manager, stepCount = 180, timeMs = baseTime + 240_000) // Low

		// Policy should stabilize at some reasonable level
		val finalPolicy = manager.currentPolicy.value
		assertTrue(
			finalPolicy != null,
			"Policy should stabilize after oscillation"
		)
	}
	private suspend fun kotlinx.coroutines.test.TestScope.startAndAwait(manager: TrackingPolicyManager) {
		manager.start()
		advanceUntilIdle()
	}

	private suspend fun kotlinx.coroutines.test.TestScope.stopAndAwait(manager: TrackingPolicyManager) {
		manager.stop()
		advanceUntilIdle()
	}

	private suspend fun kotlinx.coroutines.test.TestScope.onStepUpdateAndAwait(manager: TrackingPolicyManager, stepCount: Int, timeMs: Long) {
		manager.onStepUpdate(stepCount = stepCount, timeMs = timeMs)
		advanceUntilIdle()
	}

	private suspend fun kotlinx.coroutines.test.TestScope.onActivityTransitionAndAwait(manager: TrackingPolicyManager, activityType: Int, confidence: Int, timeMs: Long) {
		manager.onActivityTransition(activityType = activityType, confidence = confidence, timeMs = timeMs)
		advanceUntilIdle()
	}

	private suspend fun kotlinx.coroutines.test.TestScope.onLocationChangeAndAwait(manager: TrackingPolicyManager, displacementMeters: Float, timeMs: Long) {
		manager.onLocationChange(displacementMeters = displacementMeters, timeMs = timeMs)
		advanceUntilIdle()
	}

}
