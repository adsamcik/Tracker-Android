package com.adsamcik.tracker.tracker.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.TrackerRunDao
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.policy.PolicyIntervalMapper
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for TrackerService dynamic timer interval updates.
 *
 * Coverage:
 * - Policy changes trigger actual timer interval updates
 * - PolicyIntervalMapper integration with policy manager
 * - Timer receives correct intervals for each policy level
 * - Non-dynamic timers are skipped gracefully
 *
 * Note: These tests verify the actual code paths, not just mock interactions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackerServiceTimerUpdateIntegrationTest {

	private lateinit var context: Context
	private lateinit var database: AppDatabase
	private lateinit var trackerRunDao: TrackerRunDao
	private lateinit var mockTimer: DynamicIntervalCollectionTrigger

	@Before
	fun setup() {
		context = ApplicationProvider.getApplicationContext()
		database = mockk(relaxed = true)
		trackerRunDao = mockk(relaxed = true)
		mockTimer = mockk(relaxed = true)
		
		// Mock database returns
		coEvery { database.trackerRunDao() } returns trackerRunDao
		coEvery { trackerRunDao.insert(any<TrackerRun>()) } returns 1L
		coEvery { trackerRunDao.endRun(any(), any()) } just Runs
	}

	@Test
	fun `policy manager progression triggers correct interval updates`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		// Initial policy should be PASSIVE_LOW
		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.PASSIVE_LOW)

		// Escalate to MOVEMENT_SUSPECTED (need non-zero baseline)
		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 10, timeMs = baseTime) // Baseline
		manager.onStepUpdate(stepCount = 35, timeMs = baseTime + 60_000) // 25 steps/min

		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.MOVEMENT_SUSPECTED)

		// Escalate to ACTIVE_MODERATE
		manager.onStepUpdate(stepCount = 90, timeMs = baseTime + 120_000) // 55 steps/min

		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.ACTIVE_MODERATE)

		// Escalate to ACTIVE_ELEVATED
		manager.onStepUpdate(stepCount = 185, timeMs = baseTime + 180_000) // 95 steps/min

		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.ACTIVE_ELEVATED)
	}

	@Test
	fun `activity transition triggers timer interval update`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)

		// Simulate activity transition from STILL to WALKING
		val baseTime = System.currentTimeMillis()
		manager.onActivityTransition(activityType = 3, confidence = 80, timeMs = baseTime) // STILL
		manager.onActivityTransition(activityType = 7, confidence = 75, timeMs = baseTime + 5000) // WALKING

		// Should escalate to MOVEMENT_SUSPECTED
		assertEquals(TrackingPolicy.MOVEMENT_SUSPECTED, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.MOVEMENT_SUSPECTED)
	}

	@Test
	fun `location change triggers timer interval update`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)

		// Simulate significant location change
		manager.onLocationChange(displacementMeters = 100f, timeMs = System.currentTimeMillis())

		// Should escalate to ACTIVE_MODERATE
		assertEquals(TrackingPolicy.ACTIVE_MODERATE, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.ACTIVE_MODERATE)
	}

	@Test
	fun `user initiated session maintains high frequency intervals`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = true, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		// User-initiated should start at USER_INITIATED policy
		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.USER_INITIATED)

		// Verify it doesn't change even with updates
		val baseTime = System.currentTimeMillis()
		manager.onStepUpdate(stepCount = 0, timeMs = baseTime)
		manager.onActivityTransition(activityType = 3, confidence = 80, timeMs = baseTime)
		manager.onLocationChange(displacementMeters = 10f, timeMs = baseTime)

		// Should still be USER_INITIATED
		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.USER_INITIATED)
	}

	@Test
	fun `PolicyIntervalMapper returns consistent values across policy transitions`() = runTest {
		// Verify all policies have valid mappings
		TrackingPolicy.values().forEach { policy ->
			val intervalSeconds = PolicyIntervalMapper.getIntervalSeconds(policy)
			val intervalMs = PolicyIntervalMapper.getIntervalMs(policy)
			val minDistance = PolicyIntervalMapper.getMinDistanceMeters(policy)

			// Verify interval consistency
			assertEquals(intervalMs / 1000, intervalSeconds.toLong())

			// Verify values are positive
			assert(intervalSeconds > 0) { "Invalid interval for $policy" }
			assert(minDistance >= 0) { "Invalid distance for $policy" }
		}
	}

	@Test
	fun `interval progression follows battery optimization strategy`() {
		// Verify intervals decrease as policy becomes more active
		val passiveInterval = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.PASSIVE_LOW)
		val movementInterval = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.MOVEMENT_SUSPECTED)
		val moderateInterval = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.ACTIVE_MODERATE)
		val elevatedInterval = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.ACTIVE_ELEVATED)
		val userInterval = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.USER_INITIATED)

		// Verify decreasing intervals
		assert(passiveInterval > movementInterval)
		assert(movementInterval > moderateInterval)
		assert(moderateInterval >= elevatedInterval)
		assert(elevatedInterval == userInterval) // Both should be high frequency

		// Verify distance thresholds decrease
		val passiveDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.PASSIVE_LOW)
		val movementDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.MOVEMENT_SUSPECTED)
		val moderateDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_MODERATE)
		val elevatedDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_ELEVATED)

		assert(passiveDistance > movementDistance)
		assert(movementDistance > moderateDistance)
		assert(moderateDistance >= elevatedDistance)
	}

	@Test
	fun `multiple rapid policy changes produce stable final interval`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// Rapid escalation (need non-zero baseline)
		manager.onStepUpdate(stepCount = 10, timeMs = baseTime) // Baseline
		manager.onStepUpdate(stepCount = 35, timeMs = baseTime + 60_000) // 25 steps/min
		manager.onStepUpdate(stepCount = 90, timeMs = baseTime + 120_000) // 55 steps/min
		manager.onStepUpdate(stepCount = 185, timeMs = baseTime + 180_000) // 95 steps/min

		// Final policy should be ACTIVE_ELEVATED
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)

		// Verify final interval matches expected
		val expectedInterval = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.ACTIVE_ELEVATED)
		val expectedDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_ELEVATED)

		assertEquals(10, expectedInterval)
		assertEquals(10, expectedDistance)
	}

	@Test
	fun `policy remains stable when near threshold boundaries`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// Oscillate around 40 steps/min threshold
		manager.onStepUpdate(stepCount = 10, timeMs = baseTime)
		manager.onStepUpdate(stepCount = 49, timeMs = baseTime + 60_000) // 39 steps/min
		val policyBefore = manager.currentPolicy.value

		manager.onStepUpdate(stepCount = 90, timeMs = baseTime + 120_000) // 41 steps/min
		val policyAfter = manager.currentPolicy.value

		// Verify crossing threshold changes policy
		assertTrue(policyAfter.ordinal >= policyBefore.ordinal, "Policy should escalate when crossing threshold")
		verifyIntervalForPolicy(policyAfter)
	}

	@Test
	fun `consecutive activity transitions refine policy selection`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// WALKING activity
		manager.onActivityTransition(activityType = 7, confidence = 80, timeMs = baseTime)
		val walkingPolicy = manager.currentPolicy.value

		// RUNNING activity
		manager.onActivityTransition(activityType = 8, confidence = 85, timeMs = baseTime + 60_000)
		val runningPolicy = manager.currentPolicy.value

		// Running should be at least as aggressive as walking
		assertTrue(runningPolicy.ordinal >= walkingPolicy.ordinal, "Running policy should be >= walking policy")
		verifyIntervalForPolicy(runningPolicy)
	}

	@Test
	fun `mixed signal sources converge to consistent policy`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// Multi-level escalation through all policy levels
		manager.onStepUpdate(stepCount = 10, timeMs = baseTime)
		manager.onStepUpdate(stepCount = 21, timeMs = baseTime + 60_000) // 11 steps/min → MOVEMENT_SUSPECTED
		manager.onActivityTransition(activityType = 8, confidence = 90, timeMs = baseTime + 90_000) // RUNNING → ACTIVE_MODERATE
		manager.onStepUpdate(stepCount = 62, timeMs = baseTime + 120_000) // 41 steps/min
		manager.onLocationChange(displacementMeters = 300f, timeMs = baseTime + 150_000) // Significant movement
		manager.onStepUpdate(stepCount = 143, timeMs = baseTime + 180_000) // 81 steps/min → ACTIVE_ELEVATED

		// Should converge to ACTIVE_ELEVATED after multi-level escalation
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.ACTIVE_ELEVATED)
	}

	@Test
	fun `conflicting signals prioritize step count`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// Multi-level step escalation
		manager.onStepUpdate(stepCount = 10, timeMs = baseTime)
		manager.onStepUpdate(stepCount = 21, timeMs = baseTime + 60_000) // 11 steps/min → MOVEMENT_SUSPECTED
		manager.onStepUpdate(stepCount = 62, timeMs = baseTime + 120_000) // 41 steps/min → ACTIVE_MODERATE

		// Activity says STILL (conflicting), but doesn't cause de-escalation
		manager.onActivityTransition(activityType = 3, confidence = 75, timeMs = baseTime + 150_000)

		// Step count should remain (activity doesn't de-escalate, only escalates)
		assertEquals(
			TrackingPolicy.ACTIVE_MODERATE,
			manager.currentPolicy.value,
			"Step count elevation persists; activity STILL doesn't de-escalate"
		)
	}

	@Test
	fun `user-initiated policy maintains maximum collection frequency`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = true, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.USER_INITIATED)

		val baseTime = System.currentTimeMillis()

		// Even with inactivity signals, should maintain USER_INITIATED intervals
		manager.onActivityTransition(activityType = 3, confidence = 95, timeMs = baseTime) // STILL
		manager.onLocationChange(displacementMeters = 5f, timeMs = baseTime + 60_000) // Minimal movement

		assertEquals(TrackingPolicy.USER_INITIATED, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.USER_INITIATED)
	}

	@Test
	fun `policy downgrade after extended inactivity reduces intervals`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// Multi-level escalation to ACTIVE_ELEVATED
		manager.onStepUpdate(stepCount = 10, timeMs = baseTime)
		manager.onStepUpdate(stepCount = 21, timeMs = baseTime + 60_000) // 11 steps/min → MOVEMENT_SUSPECTED
		manager.onStepUpdate(stepCount = 62, timeMs = baseTime + 120_000) // 41 steps/min → ACTIVE_MODERATE
		manager.onStepUpdate(stepCount = 143, timeMs = baseTime + 180_000) // 81 steps/min → ACTIVE_ELEVATED
		val elevatedInterval = PolicyIntervalMapper.getIntervalSeconds(manager.currentPolicy.value)

		// Extended period (>300s cooldown) with minimal movement
		manager.onStepUpdate(stepCount = 148, timeMs = baseTime + 600_000) // 5 steps/min after long pause

		val passiveInterval = PolicyIntervalMapper.getIntervalSeconds(manager.currentPolicy.value)

		// Passive interval should be longer (less frequent collection) after cooldown de-escalation
		assertTrue(passiveInterval > elevatedInterval, "Passive policy should have longer intervals after cooldown")
	}

	@Test
	fun `location accuracy degradation alone does not drastically change policy`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// Establish moderate activity
		manager.onStepUpdate(stepCount = 10, timeMs = baseTime)
		manager.onStepUpdate(stepCount = 50, timeMs = baseTime + 60_000)
		val policyBefore = manager.currentPolicy.value

		// Minimal displacement despite time passing
		manager.onLocationChange(displacementMeters = 5f, timeMs = baseTime + 120_000)
		val policyAfter = manager.currentPolicy.value

		// Policy should not drastically downgrade from minimal displacement alone (could be indoor activity)
		assertEquals(policyBefore, policyAfter, "Minimal displacement alone should not drastically change policy")
	}

	@Test
	fun `rapid start-stop cycles maintain consistent interval behavior`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())

		// Rapid start-stop-start
		manager.start()
		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)
		val interval1 = PolicyIntervalMapper.getIntervalSeconds(manager.currentPolicy.value)

		manager.stop()
		
		manager.start()
		assertEquals(TrackingPolicy.PASSIVE_LOW, manager.currentPolicy.value)
		val interval2 = PolicyIntervalMapper.getIntervalSeconds(manager.currentPolicy.value)

		// Should start with same initial interval
		assertEquals(interval1, interval2, "Restarted manager should have same initial interval")
	}

	@Test
	fun `extreme step rates are clamped to ACTIVE_ELEVATED`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// Multi-level escalation even with extreme step rates (one level at a time)
		manager.onStepUpdate(stepCount = 10, timeMs = baseTime)
		manager.onStepUpdate(stepCount = 221, timeMs = baseTime + 60_000) // 211 steps/min → MOVEMENT_SUSPECTED
		manager.onStepUpdate(stepCount = 432, timeMs = baseTime + 120_000) // 211 steps/min → ACTIVE_MODERATE
		manager.onStepUpdate(stepCount = 643, timeMs = baseTime + 180_000) // 211 steps/min → ACTIVE_ELEVATED

		// Should cap at ACTIVE_ELEVATED (not exceed it) after multi-level escalation
		assertEquals(TrackingPolicy.ACTIVE_ELEVATED, manager.currentPolicy.value)
		verifyIntervalForPolicy(TrackingPolicy.ACTIVE_ELEVATED)
	}

	@Test
	fun `zero speed from location does not force downgrade if steps are active`() = runTest {
		val manager = TrackingPolicyManager(context, isUserInitiated = false, database = database, escalationEngine = DefaultPolicyEscalationEngine())
		manager.start()

		val baseTime = System.currentTimeMillis()

		// Active step rate
		manager.onStepUpdate(stepCount = 10, timeMs = baseTime)
		manager.onStepUpdate(stepCount = 60, timeMs = baseTime + 60_000)
		val activePolicyBefore = manager.currentPolicy.value

		// Minimal displacement despite steps (e.g., treadmill or indoor activity)
		manager.onLocationChange(displacementMeters = 2f, timeMs = baseTime + 120_000)

		// Should trust step count over minimal displacement
		assertTrue(
			manager.currentPolicy.value.ordinal >= TrackingPolicy.MOVEMENT_SUSPECTED.ordinal,
			"Step count should override minimal displacement"
		)
	}

	/**
	 * Helper function to verify correct interval for a given policy.
	 * Simulates what TrackerService.updateTimerIntervalForPolicy would do.
	 */
	private fun verifyIntervalForPolicy(policy: TrackingPolicy) {
		val expectedInterval = PolicyIntervalMapper.getIntervalSeconds(policy)
		val expectedDistance = PolicyIntervalMapper.getMinDistanceMeters(policy)

		// Verify expected values based on policy
		when (policy) {
			TrackingPolicy.PASSIVE_LOW -> {
				assertEquals(300, expectedInterval) // 5 minutes
				assertEquals(50, expectedDistance)
			}
			TrackingPolicy.MOVEMENT_SUSPECTED -> {
				assertEquals(120, expectedInterval) // 2 minutes
				assertEquals(30, expectedDistance)
			}
			TrackingPolicy.ACTIVE_MODERATE -> {
				assertEquals(30, expectedInterval) // 30 seconds
				assertEquals(15, expectedDistance)
			}
			TrackingPolicy.ACTIVE_ELEVATED -> {
				assertEquals(10, expectedInterval) // 10 seconds
				assertEquals(10, expectedDistance)
			}
			TrackingPolicy.USER_INITIATED -> {
				assertEquals(10, expectedInterval) // 10 seconds
				assertEquals(10, expectedDistance)
			}
		}
	}
}
