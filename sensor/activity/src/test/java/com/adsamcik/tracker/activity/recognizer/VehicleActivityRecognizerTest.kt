package com.adsamcik.tracker.activity.recognizer

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Focused tests for [VehicleActivityRecognizer] covering bicycle vs vehicle
 * priority, confidence averaging, bucket classification, and boundary conditions.
 */
class VehicleActivityRecognizerTest {

    private val recognizer = VehicleActivityRecognizer()
    private val session: TrackerSession = mockk(relaxed = true)

    private fun locationWith(
        activity: DetectedActivity,
        confidence: Int = 80,
        time: Long = 1_700_000_000_000L
    ): ActivityLocation {
        val location = Location(
            time = time,
            latitude = 50.0,
            longitude = 14.0,
            altitude = null,
            horizontalAccuracy = 10f,
            verticalAccuracy = null,
            speed = null,
            speedAccuracy = null
        )
        return ActivityLocation(location, ActivityInfo(activity, confidence))
    }

    private fun locationsOf(
        count: Int,
        activity: DetectedActivity,
        confidence: Int = 80
    ): List<ActivityLocation> = List(count) { i ->
        locationWith(activity, confidence, time = 1_700_000_000_000L + i * 1000L)
    }

    @Test
    fun `precisionConfidence is 75`() {
        recognizer.precisionConfidence shouldBe 75
    }

    @Nested
    inner class `bicycle detection` {

        @Test
        fun `bicycle check takes priority when both thresholds are met`() {
            // 4 BICYCLE, 3 IN_VEHICLE, 3 UNKNOWN (total=10)
            // Bicycle check: unknown(3)+bicycle(4)=7 > (unknown(3)+onFoot(0)+vehicle(3))/2=3 -> true
            val locations = locationsOf(4, DetectedActivity.ON_BICYCLE, confidence = 85) +
                    locationsOf(3, DetectedActivity.IN_VEHICLE, confidence = 90) +
                    locationsOf(3, DetectedActivity.UNKNOWN, confidence = 50)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.BICYCLE
            result.confidence shouldBe 85
        }

        @Test
        fun `bicycle confidence averages across locations with varying confidences`() {
            // 3 BICYCLE: 60,80,100 -> avg = 80
            val locations = listOf(
                locationWith(DetectedActivity.ON_BICYCLE, confidence = 60),
                locationWith(DetectedActivity.ON_BICYCLE, confidence = 80),
                locationWith(DetectedActivity.ON_BICYCLE, confidence = 100)
            )

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.BICYCLE
            result.confidence shouldBe 80
        }

        @Test
        fun `single bicycle location detected`() {
            val locations = listOf(locationWith(DetectedActivity.ON_BICYCLE, confidence = 70))
            // Bicycle: 0+1=1 > (0+0+0)/2=0 -> true
            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.BICYCLE
            result.confidence shouldBe 70
        }

        @Test
        fun `bicycle not detected when on foot dominates denominator`() {
            // 1 BICYCLE, 9 ON_FOOT -> bicycle check: 0+1=1 > (0+9+0)/2=4 -> false
            val locations = locationsOf(1, DetectedActivity.ON_BICYCLE, confidence = 90) +
                    locationsOf(9, DetectedActivity.ON_FOOT, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
        }

        @Test
        fun `bicycle check uses integer division in denominator`() {
            // 3 ON_FOOT, 2 BICYCLE -> (0+3+0)/2 = 1 (int div), 0+2=2 > 1 -> true
            val locations = locationsOf(3, DetectedActivity.ON_FOOT, confidence = 60) +
                    locationsOf(2, DetectedActivity.ON_BICYCLE, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.BICYCLE
            result.confidence shouldBe 80
        }

        @Test
        fun `unknown alone does not trigger bicycle`() {
            val locations = locationsOf(3, DetectedActivity.UNKNOWN, confidence = 50) +
                    locationsOf(1, DetectedActivity.IN_VEHICLE, confidence = 80) +
                    locationsOf(6, DetectedActivity.STILL, confidence = 70)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }
    }

    @Nested
    inner class `vehicle detection` {

        @Test
        fun `vehicle confidence averages correctly with varying confidences`() {
            val locations = listOf(
                locationWith(DetectedActivity.IN_VEHICLE, confidence = 60),
                locationWith(DetectedActivity.IN_VEHICLE, confidence = 70),
                locationWith(DetectedActivity.IN_VEHICLE, confidence = 80),
                locationWith(DetectedActivity.IN_VEHICLE, confidence = 90),
                locationWith(DetectedActivity.IN_VEHICLE, confidence = 100)
            )

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.LAND_VEHICLE
            result.confidence shouldBe 80
        }

        @Test
        fun `vehicle just above 20 percent threshold with still padding`() {
            // 3 IN_VEHICLE, 7 STILL (total=10)
            // Bicycle: 0+0=0 > (0+0+3)/2=1 -> false
            // Vehicle: 0+3=3 > 10*0.2=2.0 -> true
            val locations = locationsOf(3, DetectedActivity.IN_VEHICLE, confidence = 75) +
                    locationsOf(7, DetectedActivity.STILL, confidence = 60)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.LAND_VEHICLE
            result.confidence shouldBe 75
        }

        @Test
        fun `vehicle at exactly 20 percent returns null`() {
            // 2 IN_VEHICLE, 8 ON_FOOT (total=10) -> 0+2=2 > 10*0.2=2.0 -> false
            val locations = locationsOf(2, DetectedActivity.IN_VEHICLE, confidence = 90) +
                    locationsOf(8, DetectedActivity.ON_FOOT, confidence = 60)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
        }

        @Test
        fun `unknown plus vehicle at exactly 20 percent returns null`() {
            // 1 IN_VEHICLE, 1 UNKNOWN, 8 STILL (total=10)
            // Bicycle: 1+0=1 > (1+0+1)/2=1 -> false
            // Vehicle: 1+1=2 > 10*0.2=2.0 -> false
            val locations = locationsOf(1, DetectedActivity.IN_VEHICLE, confidence = 80) +
                    locationsOf(1, DetectedActivity.UNKNOWN, confidence = 50) +
                    locationsOf(8, DetectedActivity.STILL, confidence = 70)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
        }

        @Test
        fun `single IN_VEHICLE location returns LAND_VEHICLE`() {
            // 1 IN_VEHICLE (total=1) -> 0+1=1 > 1*0.2=0.2 -> true
            val locations = listOf(locationWith(DetectedActivity.IN_VEHICLE, confidence = 95))

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.LAND_VEHICLE
            result.confidence shouldBe 95
        }
    }

    @Nested
    inner class `activity bucket classification` {

        @Test
        fun `WALKING RUNNING and ON_FOOT all map to onFoot bucket`() {
            // All on-foot types go to onFoot, none to vehicle or bicycle
            val locations = locationsOf(4, DetectedActivity.WALKING, confidence = 80) +
                    locationsOf(3, DetectedActivity.RUNNING, confidence = 80) +
                    locationsOf(3, DetectedActivity.ON_FOOT, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
        }

        @Test
        fun `STILL does not affect bicycle or vehicle thresholds`() {
            val locations = locationsOf(10, DetectedActivity.STILL, confidence = 90)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
        }

        @Test
        fun `TILTING maps to unknown bucket`() {
            val locations = locationsOf(10, DetectedActivity.TILTING, confidence = 60)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }
    }

    @Nested
    inner class `edge cases` {

        @Test
        fun `empty collection returns null`() {
            val result = recognizer.resolve(session, emptyList())

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `all zero confidence vehicle returns null`() {
            val locations = locationsOf(10, DetectedActivity.IN_VEHICLE, confidence = 0)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `large collection with clear vehicle majority`() {
            val locations = locationsOf(80, DetectedActivity.IN_VEHICLE, confidence = 85) +
                    locationsOf(10, DetectedActivity.ON_FOOT, confidence = 70) +
                    locationsOf(10, DetectedActivity.STILL, confidence = 50)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.LAND_VEHICLE
            result.confidence shouldBe 85
        }

        @Test
        fun `mixed vehicle and bicycle below both thresholds`() {
            // 1 IN_VEHICLE, 1 ON_BICYCLE, 8 ON_FOOT (total=10)
            // Bicycle: 0+1=1 > (0+8+1)/2=4 -> false
            // Vehicle: 0+1=1 > 10*0.2=2 -> false
            val locations = locationsOf(1, DetectedActivity.IN_VEHICLE, confidence = 80) +
                    locationsOf(1, DetectedActivity.ON_BICYCLE, confidence = 80) +
                    locationsOf(8, DetectedActivity.ON_FOOT, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
        }
    }
}
