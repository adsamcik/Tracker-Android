package com.adsamcik.tracker.tracker.policy

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for PolicyIntervalMapper.
 *
 * Coverage:
 * - Interval mapping for all policy levels
 * - Interval progression (longer for passive, shorter for active)
 * - Distance threshold mapping
 * - Conversion between milliseconds and seconds
 */
@DisplayName("PolicyIntervalMapper")
class PolicyIntervalMapperTest {

    @Nested
    @DisplayName("Interval Mapping (Milliseconds)")
    inner class IntervalMappingMs {

        @Test
        fun `PASSIVE_LOW returns 5 minute interval`() {
            val intervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.PASSIVE_LOW)
            intervalMs shouldBe 5 * Time.MINUTE_IN_MILLISECONDS
        }

        @Test
        fun `MOVEMENT_SUSPECTED returns 2 minute interval`() {
            val intervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.MOVEMENT_SUSPECTED)
            intervalMs shouldBe 2 * Time.MINUTE_IN_MILLISECONDS
        }

        @Test
        fun `ACTIVE_MODERATE uses selected 2 second interval`() {
            val intervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_MODERATE)
            intervalMs shouldBe 2 * Time.SECOND_IN_MILLISECONDS
        }

        @Test
        fun `ACTIVE_ELEVATED uses selected 2 second interval`() {
            val intervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_ELEVATED)
            intervalMs shouldBe 2 * Time.SECOND_IN_MILLISECONDS
        }

        @Test
        fun `USER_INITIATED uses selected 2 second interval`() {
            val intervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.USER_INITIATED)
            intervalMs shouldBe 2 * Time.SECOND_IN_MILLISECONDS
        }
    }

    @Nested
    @DisplayName("Interval Mapping (Seconds)")
    inner class IntervalMappingSec {

        @Test
        fun `PASSIVE_LOW returns 300 second interval`() {
            val intervalSec = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.PASSIVE_LOW)
            intervalSec shouldBe 300
        }

        @Test
        fun `MOVEMENT_SUSPECTED returns 120 second interval`() {
            val intervalSec = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.MOVEMENT_SUSPECTED)
            intervalSec shouldBe 120
        }

        @Test
        fun `ACTIVE_MODERATE uses selected 2 second interval`() {
            val intervalSec = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.ACTIVE_MODERATE)
            intervalSec shouldBe 2
        }

        @Test
        fun `ACTIVE_ELEVATED uses selected 2 second interval`() {
            val intervalSec = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.ACTIVE_ELEVATED)
            intervalSec shouldBe 2
        }

        @Test
        fun `USER_INITIATED uses selected 2 second interval`() {
            val intervalSec = PolicyIntervalMapper.getIntervalSeconds(TrackingPolicy.USER_INITIATED)
            intervalSec shouldBe 2
        }
    }

    @Nested
    @DisplayName("Interval Progression")
    inner class IntervalProgression {

        @Test
        fun `interval decreases from PASSIVE to MOVEMENT_SUSPECTED`() {
            val passiveInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.PASSIVE_LOW)
            val movementInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.MOVEMENT_SUSPECTED)

            movementInterval shouldBeLessThan passiveInterval
        }

        @Test
        fun `interval decreases from MOVEMENT_SUSPECTED to ACTIVE_MODERATE`() {
            val movementInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.MOVEMENT_SUSPECTED)
            val activeModerateInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_MODERATE)

            activeModerateInterval shouldBeLessThan movementInterval
        }

        @Test
        fun `active GPS policies preserve the same selected interval`() {
            val activeModerateInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_MODERATE)
            val activeElevatedInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_ELEVATED)

            activeElevatedInterval shouldBe activeModerateInterval
        }

        @Test
        fun `USER_INITIATED has same interval as ACTIVE_ELEVATED`() {
            val userInitiatedInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.USER_INITIATED)
            val activeElevatedInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_ELEVATED)

            userInitiatedInterval shouldBe activeElevatedInterval
        }
    }

    @Nested
    @DisplayName("Distance Thresholds")
    inner class DistanceThresholds {

        @Test
        fun `PASSIVE_LOW returns 50m distance threshold`() {
            PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.PASSIVE_LOW) shouldBe 50
        }

        @Test
        fun `MOVEMENT_SUSPECTED returns 30m distance threshold`() {
            PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.MOVEMENT_SUSPECTED) shouldBe 30
        }

        @Test
        fun `ACTIVE_MODERATE uses selected 10m distance threshold`() {
            PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_MODERATE) shouldBe 10
        }

        @Test
        fun `ACTIVE_ELEVATED returns 10m distance threshold`() {
            PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_ELEVATED) shouldBe 10
        }

        @Test
        fun `USER_INITIATED returns 10m distance threshold`() {
            PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.USER_INITIATED) shouldBe 10
        }
    }

    @Nested
    @DisplayName("Distance Progression")
    inner class DistanceProgression {

        @Test
        fun `distance threshold decreases from PASSIVE to MOVEMENT_SUSPECTED`() {
            val passiveDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.PASSIVE_LOW)
            val movementDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.MOVEMENT_SUSPECTED)

            movementDistance shouldBeLessThan passiveDistance
        }

        @Test
        fun `distance threshold decreases from MOVEMENT_SUSPECTED to ACTIVE_MODERATE`() {
            val movementDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.MOVEMENT_SUSPECTED)
            val activeModerateDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_MODERATE)

            activeModerateDistance shouldBeLessThan movementDistance
        }

        @Test
        fun `active GPS policies preserve the same selected distance threshold`() {
            val activeModerateDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_MODERATE)
            val activeElevatedDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_ELEVATED)

            activeElevatedDistance shouldBe activeModerateDistance
        }

        @Test
        fun `USER_INITIATED has same distance threshold as ACTIVE_ELEVATED`() {
            val userInitiatedDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.USER_INITIATED)
            val activeElevatedDistance = PolicyIntervalMapper.getMinDistanceMeters(TrackingPolicy.ACTIVE_ELEVATED)

            userInitiatedDistance shouldBe activeElevatedDistance
        }
    }

    @Nested
    @DisplayName("Battery Optimization Strategy")
    inner class BatteryOptimizationStrategy {

        @Test
        fun `passive interval is at least 10x longer than active`() {
            val passiveIntervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.PASSIVE_LOW)
            val activeElevatedIntervalMs = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_ELEVATED)

            passiveIntervalMs shouldBeGreaterThanOrEqualTo (activeElevatedIntervalMs * 10)
        }

        @Test
        fun `movement interval is between passive and active`() {
            val passiveInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.PASSIVE_LOW)
            val movementInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.MOVEMENT_SUSPECTED)
            val activeInterval = PolicyIntervalMapper.getIntervalMs(TrackingPolicy.ACTIVE_ELEVATED)

            movementInterval shouldBeLessThan passiveInterval
            activeInterval shouldBeLessThan movementInterval
        }
    }

    @Nested
    @DisplayName("Conversion Accuracy")
    inner class ConversionAccuracy {

        @Test
        fun `millisecond to second conversion is accurate for all policies`() {
            TrackingPolicy.entries.forEach { policy ->
                val intervalMs = PolicyIntervalMapper.getIntervalMs(policy)
                val intervalSec = PolicyIntervalMapper.getIntervalSeconds(policy)
                val expectedSec = (intervalMs / Time.SECOND_IN_MILLISECONDS).toInt()

                intervalSec shouldBe expectedSec
            }
        }
    }
    @Test
    fun `active GPS policies honor custom cadence and distance`() {
        val params = TrackingParamsState(minTimeSeconds = 7, minDistanceMeters = 42)
        val activePolicies = listOf(
            TrackingPolicy.ACTIVE_MODERATE,
            TrackingPolicy.ACTIVE_ELEVATED,
            TrackingPolicy.USER_INITIATED,
        )

        activePolicies.forEach { policy ->
            PolicyIntervalMapper.getIntervalSeconds(policy, params) shouldBe 7
            PolicyIntervalMapper.getMinDistanceMeters(policy, params) shouldBe 42
        }
    }
}
