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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()

		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)
	}

	@Test
	fun `initial policy is USER_INITIATED for user sessions`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = true, database)
		manager.start()

		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)
	}

	@Test
	fun `shouldRequestLocation returns false for PASSIVE_LOW`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()

		assertFalse(manager.shouldRequestLocation())
	}

	@Test
	fun `shouldRequestLocation returns false for MOVEMENT_SUSPECTED`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()
		
		// Escalate to MOVEMENT_SUSPECTED by simulating 25 steps/min rate (> 10 threshold)
		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime) // Baseline (non-zero)
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000) // 25 steps in 1 minute

		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
		assertFalse(manager.shouldRequestLocation())
	}

	@Test
	fun `shouldRequestLocation returns true for ACTIVE_MODERATE`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()

		// Escalate step-by-step to ACTIVE_MODERATE
		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime) // Baseline (non-zero)
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000) // 25 steps/min → MOVEMENT_SUSPECTED
		manager.onStepUpdate(stepCount = 180, timeMs = baseTime + 120_000) // 55 steps/min → ACTIVE_MODERATE

		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
		assertTrue(manager.shouldRequestLocation())
	}

	@Test
	fun `step rate below 10 per min keeps PASSIVE_LOW`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()

		// Simulate 5 steps/min rate (below threshold)
		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 0, timeMs = baseTime) // Baseline
		manager.onStepUpdate(stepCount = 5, timeMs = baseTime + 60_000) // 5 steps in 1 minute

		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)
	}

	@Test
	fun `step rate 10-40 per min escalates to MOVEMENT_SUSPECTED`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()

		// Simulate 25 steps/min rate (> 10 threshold, < 40 threshold)
		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime) // Baseline (non-zero)
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000) // 25 steps in 1 minute

		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
	}

	@Test
	fun `step rate 40-80 per min escalates to ACTIVE_MODERATE`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()

		// Step rate escalation happens one level at a time
		// First escalate to MOVEMENT_SUSPECTED, then to ACTIVE_MODERATE
		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime) // Baseline (non-zero)
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000) // 25 steps/min → MOVEMENT_SUSPECTED
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
		
		// Now escalate to ACTIVE_MODERATE with 65 steps/min (> 40 threshold)
		manager.onStepUpdate(stepCount = 190, timeMs = baseTime + 120_000) // 65 steps in next minute
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
	}

	@Test
	fun `step rate above 80 per min escalates to ACTIVE_ELEVATED`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()

		// Step rate escalation happens one level at a time
		// PASSIVE_LOW → MOVEMENT_SUSPECTED → ACTIVE_MODERATE → ACTIVE_ELEVATED
		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime) // Baseline (non-zero)
		
		// Escalate to MOVEMENT_SUSPECTED (25 steps/min)
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000)
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
		
		// Escalate to ACTIVE_MODERATE (55 steps/min)
		manager.onStepUpdate(stepCount = 180, timeMs = baseTime + 120_000)
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
		
		// Escalate to ACTIVE_ELEVATED (95 steps/min > 80 threshold)
		manager.onStepUpdate(stepCount = 275, timeMs = baseTime + 180_000)
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
	}

	@Test
	fun `activity transition from STILL to MOVING escalates policy`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()

		val baseTime = System.currentTimeMillis()
		
		// Establish STILL baseline
		manager.onActivityTransition(activityType = 3, confidence = 80, timeMs = baseTime) // STILL
		
		// Transition to WALKING with high confidence
		manager.onActivityTransition(activityType = 7, confidence = 75, timeMs = baseTime + 5000) // WALKING

		// Should escalate from PASSIVE_LOW
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
	}

	@Test
	fun `location displacement above 50m escalates policy`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()

		manager.onLocationChange(displacementMeters = 100f, timeMs = System.currentTimeMillis())

		// Should escalate from PASSIVE_LOW
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
	}

	@Test
	fun `user-initiated sessions do not adapt based on step rate`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = true, database)
		manager.start()

		// Try to escalate via steps
		manager.onStepUpdate(stepCount = 100, timeMs = System.currentTimeMillis())

		// Should stay USER_INITIATED
		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)
	}

	@Test
	fun `tracker run created on start`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()

		coVerify { trackerRunDao.insert(any<TrackerRun>()) }
	}

	@Test
	fun `tracker run ended on stop`() = runTest {
		coEvery { trackerRunDao.insert(any<TrackerRun>()) } returns 42L

		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()
		manager.stop()

		coVerify { trackerRunDao.endRun(42L, any<Long>()) }
	}

	@Test
	fun `progressive escalation through all policy levels`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database)
		manager.start()

		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)

		val baseTime = System.currentTimeMillis()
		
		// Establish baseline (non-zero)
		manager.onStepUpdate(stepCount = 100, timeMs = baseTime)
		
		// Escalate to MOVEMENT_SUSPECTED (25 steps/min > 10 threshold)
		manager.onStepUpdate(stepCount = 125, timeMs = baseTime + 60_000)
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)

		// Escalate to ACTIVE_MODERATE (55 steps/min > 40 threshold)
		manager.onStepUpdate(stepCount = 180, timeMs = baseTime + 120_000) // 55 steps in next minute
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)

		// Escalate to ACTIVE_ELEVATED (95 steps/min > 80 threshold)
		manager.onStepUpdate(stepCount = 275, timeMs = baseTime + 180_000) // 95 steps in next minute
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
	}
}


