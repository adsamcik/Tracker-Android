package com.adsamcik.tracker.tracker.component.consumer.pre

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for PolicyAwareLocationPreTrackerComponent.
 *
 * Coverage:
 * - Location-optional behavior for PASSIVE policies
 * - Location-required behavior for ACTIVE policies
 * - Location quality validation when required
 * - Seamless tracking continuation without GPS
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PolicyAwareLocationPreTrackerComponentTest {

	private lateinit var context: Context
	private lateinit var policyFlow: MutableStateFlow<TrackingPolicy>

	@Before
	fun setup() {
		context = ApplicationProvider.getApplicationContext()
		policyFlow = MutableStateFlow(TrackingPolicy.PASSIVE_LOW)
	}

	@Test
	fun `PASSIVE_LOW allows tracking without location`() = runTest {
		policyFlow.value = TrackingPolicy.PASSIVE_LOW
		val component = PolicyAwareLocationPreTrackerComponent(policyFlow)
		component.onEnable(context)

		val cycle = TrackingCycle(
			timestampMs = System.currentTimeMillis(),
			elapsedRealtimeNanos = 0L,
		)
		// No location added

		val result = component.onNewData(cycle)

		assertTrue(result, "PASSIVE_LOW should allow tracking without location")
	}

	@Test
	fun `MOVEMENT_SUSPECTED allows tracking without location`() = runTest {
		policyFlow.value = TrackingPolicy.MOVEMENT_SUSPECTED
		val component = PolicyAwareLocationPreTrackerComponent(policyFlow)
		component.onEnable(context)

		val cycle = TrackingCycle(
			timestampMs = System.currentTimeMillis(),
			elapsedRealtimeNanos = 0L,
		)
		// No location added

		val result = component.onNewData(cycle)

		assertTrue(result, "MOVEMENT_SUSPECTED should allow tracking without location")
	}

	@Test
	fun `ACTIVE_MODERATE requires location`() = runTest {
		policyFlow.value = TrackingPolicy.ACTIVE_MODERATE
		val component = PolicyAwareLocationPreTrackerComponent(policyFlow)
		component.onEnable(context)

		val cycle = TrackingCycle(
			timestampMs = System.currentTimeMillis(),
			elapsedRealtimeNanos = 0L,
		)
		// No location added

		val result = component.onNewData(cycle)

		assertFalse(result, "ACTIVE_MODERATE should reject tracking without location")
	}

	@Test
	fun `ACTIVE_MODERATE accepts valid location`() = runTest {
		policyFlow.value = TrackingPolicy.ACTIVE_MODERATE
		val component = PolicyAwareLocationPreTrackerComponent(policyFlow)
		component.onEnable(context)

		val mockLocation = mockk<android.location.Location>(relaxed = true)
		every { mockLocation.hasAccuracy() } returns true
		every { mockLocation.accuracy } returns 20f

		val locationData = com.adsamcik.tracker.shared.base.data.LocationData.Builder()
			.apply { setLocation(mockLocation) }
			.build()
		val cycle = TrackingCycle(
			timestampMs = System.currentTimeMillis(),
			elapsedRealtimeNanos = 0L,
			location = locationData,
		)

		val result = component.onNewData(cycle)

		assertTrue(result, "ACTIVE_MODERATE should accept valid location")
	}

	@Test
	fun `ACTIVE_MODERATE rejects location without accuracy`() = runTest {
		policyFlow.value = TrackingPolicy.ACTIVE_MODERATE
		val component = PolicyAwareLocationPreTrackerComponent(policyFlow)
		component.onEnable(context)

		val mockLocation = mockk<android.location.Location>(relaxed = true)
		every { mockLocation.hasAccuracy() } returns false

		val locationData = com.adsamcik.tracker.shared.base.data.LocationData.Builder()
			.apply { setLocation(mockLocation) }
			.build()
		val cycle = TrackingCycle(
			timestampMs = System.currentTimeMillis(),
			elapsedRealtimeNanos = 0L,
			location = locationData,
		)

		val result = component.onNewData(cycle)

		assertFalse(result, "ACTIVE_MODERATE should reject location without accuracy")
	}

	@Test
	fun `USER_INITIATED requires location`() = runTest {
		policyFlow.value = TrackingPolicy.USER_INITIATED
		val component = PolicyAwareLocationPreTrackerComponent(policyFlow)
		component.onEnable(context)

		val cycle = TrackingCycle(
			timestampMs = System.currentTimeMillis(),
			elapsedRealtimeNanos = 0L,
		)
		// No location added

		val result = component.onNewData(cycle)

		assertFalse(result, "USER_INITIATED should require location")
	}

	@Test
	fun `policy transition from PASSIVE to ACTIVE changes requirement`() = runTest {
		policyFlow.value = TrackingPolicy.PASSIVE_LOW
		val component = PolicyAwareLocationPreTrackerComponent(policyFlow)
		component.onEnable(context)

		val cycleNoLocation = TrackingCycle(
			timestampMs = System.currentTimeMillis(),
			elapsedRealtimeNanos = 0L,
		)

		// Initially should allow without location
		var result = component.onNewData(cycleNoLocation)
		assertTrue(result, "PASSIVE_LOW should allow without location")

		// Transition to ACTIVE_MODERATE
		policyFlow.value = TrackingPolicy.ACTIVE_MODERATE

		// Now should require location
		result = component.onNewData(cycleNoLocation)
		assertFalse(result, "ACTIVE_MODERATE should require location")
	}

	@Test
    fun `PASSIVE_LOW accepts tracking with location present`() = runTest {
		policyFlow.value = TrackingPolicy.PASSIVE_LOW
		val component = PolicyAwareLocationPreTrackerComponent(policyFlow)
		component.onEnable(context)

		val mockLocation = mockk<android.location.Location>(relaxed = true)
		every { mockLocation.hasAccuracy() } returns true

		val locationData = com.adsamcik.tracker.shared.base.data.LocationData.Builder()
			.apply { setLocation(mockLocation) }
			.build()
		val cycle = TrackingCycle(
			timestampMs = System.currentTimeMillis(),
			elapsedRealtimeNanos = 0L,
			location = locationData,
		)

		val result = component.onNewData(cycle)

        assertTrue(result, "PASSIVE_LOW should accept tracking with location")
    }

    @Test
    fun `USER_INITIATED uses repository accuracy threshold`() = runTest {
        policyFlow.value = TrackingPolicy.USER_INITIATED
        val params = MutableStateFlow(TrackingParamsState(requiredAccuracyMeters = 25))
        val repository: TrackingParamsRepository = mockk {
            every { data } returns params
        }
        val component = PolicyAwareLocationPreTrackerComponent(
            policyFlow = policyFlow,
            trackingParamsRepository = repository,
        )
        component.onEnable(context)

        val mockLocation = mockk<android.location.Location>(relaxed = true)
        every { mockLocation.hasAccuracy() } returns true
        every { mockLocation.accuracy } returns 30f

        val locationData = com.adsamcik.tracker.shared.base.data.LocationData.Builder()
            .apply { setLocation(mockLocation) }
            .build()
        val cycle = TrackingCycle(
            timestampMs = System.currentTimeMillis(),
            elapsedRealtimeNanos = 0L,
            location = locationData,
        )

        val result = component.onNewData(cycle)

        assertFalse(result, "USER_INITIATED should use repository required accuracy")
        component.onDisable(context)
    }
}
