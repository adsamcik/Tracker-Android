package com.adsamcik.tracker.activity.recognizer

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for activity recognition classes:
 * [ActivityRecognitionResult], [OnFootActivityRecognizer], and [VehicleActivityRecognizer].
 *
 * These tests validate the activity classification logic that determines
 * which [NativeSessionActivity] best represents a collection of location data points.
 */
@DisplayName("Activity Recognition")
class ActivityRecognizerTest {

    private val session: TrackerSession = mockk(relaxed = true)

    /**
     * Creates a [DatabaseLocation] with the specified [DetectedActivity] and confidence.
     * All other location fields use neutral defaults since the recognizers only
     * inspect [ActivityInfo].
     */
    private fun locationWith(
        activity: DetectedActivity,
        confidence: Int = 80,
        time: Long = 1700000000000L
    ): DatabaseLocation {
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
        val activityInfo = ActivityInfo(activity, confidence)
        return DatabaseLocation(location, activityInfo)
    }

    /**
     * Creates a list of [DatabaseLocation] instances, all with the same activity and confidence.
     */
    private fun locationsOf(
        count: Int,
        activity: DetectedActivity,
        confidence: Int = 80
    ): List<DatabaseLocation> = (0 until count).map { i ->
        locationWith(activity, confidence, time = 1700000000000L + i * 1000L)
    }

    // -----------------------------------------------------------------------
    // ActivityRecognitionResult
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ActivityRecognitionResult")
    inner class ActivityRecognitionResultTests {

        @Test
        fun `stores recognizedActivity and confidence correctly`() {
            val result = ActivityRecognitionResult(NativeSessionActivity.WALKING, 85)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 85
        }

        @Test
        fun `stores null recognizedActivity correctly`() {
            val result = ActivityRecognitionResult(null, 0)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `requireRecognizedActivity returns value when not null`() {
            val result = ActivityRecognitionResult(NativeSessionActivity.RUNNING, 90)

            result.requireRecognizedActivity shouldBe NativeSessionActivity.RUNNING
        }

        @Test
        fun `requireRecognizedActivity throws NullPointerException when null`() {
            val result = ActivityRecognitionResult(null, 0)

            assertThrows<NullPointerException> {
                result.requireRecognizedActivity
            }
        }
    }

    // -----------------------------------------------------------------------
    // OnFootActivityRecognizer
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("OnFootActivityRecognizer")
    inner class OnFootTests {

        private val recognizer = OnFootActivityRecognizer()

        @Test
        fun `precisionConfidence is 75`() {
            recognizer.precisionConfidence shouldBe 75
        }

        @Test
        fun `all walking locations returns WALKING`() {
            val locations = locationsOf(10, DetectedActivity.WALKING, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 80
        }

        @Test
        fun `all ON_FOOT locations returns WALKING`() {
            val locations = locationsOf(10, DetectedActivity.ON_FOOT, confidence = 70)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 70
        }

        @Test
        fun `all running locations returns RUNNING`() {
            // All locations are RUNNING with confidence 90.
            // walk.confidenceSum = 0, so walk.confidenceSum/3 = 0.
            // run.confidenceSum = 900 > 0, so RUNNING branch is taken.
            // confidence = (10/10) * 90 = 90
            val locations = locationsOf(10, DetectedActivity.RUNNING, confidence = 90)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 90
        }

        @Test
        fun `running confidence sum exceeds walk sum divided by 3 returns RUNNING`() {
            // 5 WALKING with confidence 60 -> walk.confidenceSum = 300, walk.confidenceSum/3 = 100
            // 5 RUNNING with confidence 30 -> run.confidenceSum = 150 > 100, so RUNNING
            // run.confidence = 150/5 = 30
            // confidence = (5.0/10.0) * 30 = 15
            val locations = locationsOf(5, DetectedActivity.WALKING, confidence = 60) +
                    locationsOf(5, DetectedActivity.RUNNING, confidence = 30)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 15
        }

        @Test
        fun `running confidence sum at boundary does not trigger RUNNING`() {
            // 6 WALKING with confidence 60 -> walk.confidenceSum = 360, walk.confidenceSum/3 = 120
            // 4 RUNNING with confidence 30 -> run.confidenceSum = 120, NOT > 120
            // Falls through to WALKING with walk.confidence = 360/6 = 60
            val locations = locationsOf(6, DetectedActivity.WALKING, confidence = 60) +
                    locationsOf(4, DetectedActivity.RUNNING, confidence = 30)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 60
        }

        @Test
        fun `other activities dominate returns null result`() {
            // The "other" bucket catches IN_VEHICLE, ON_BICYCLE.
            // onFoot is always 0 in the implementation (WALKING/ON_FOOT go to walk bucket).
            // other.count (6) > onFoot.count (0) + walk.count (2) + run.count (2) = 4
            val locations = locationsOf(2, DetectedActivity.WALKING, confidence = 80) +
                    locationsOf(2, DetectedActivity.RUNNING, confidence = 80) +
                    locationsOf(6, DetectedActivity.IN_VEHICLE, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `other activities do not dominate when equal`() {
            // other.count (4) > walk.count (2) + run.count (2) = 4 -> false (not strictly greater)
            // Falls through to walk/run logic. run.confidenceSum = 160, walk.confidenceSum/3 = 53
            // 160 > 53 -> RUNNING. confidence = (2.0/8.0) * 80 = 20
            val locations = locationsOf(2, DetectedActivity.WALKING, confidence = 80) +
                    locationsOf(2, DetectedActivity.RUNNING, confidence = 80) +
                    locationsOf(4, DetectedActivity.IN_VEHICLE, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 20
        }

        @Test
        fun `STILL locations counted as still not other`() {
            // STILL goes to `still` bucket, not `other` bucket.
            // 5 WALKING + 5 STILL -> other.count = 0, not > walk.count (5)
            // Falls through to WALKING with walk.confidence = 400/5 = 80
            val locations = locationsOf(5, DetectedActivity.WALKING, confidence = 80) +
                    locationsOf(5, DetectedActivity.STILL, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 80
        }

        @Test
        fun `UNKNOWN and TILTING go to unknown bucket not other`() {
            // UNKNOWN and TILTING go to `unknown` bucket, not `other`.
            // 5 WALKING + 3 UNKNOWN + 2 TILTING -> other.count = 0, not > walk.count (5)
            // Falls through to WALKING
            val locations = locationsOf(5, DetectedActivity.WALKING, confidence = 70) +
                    locationsOf(3, DetectedActivity.UNKNOWN, confidence = 50) +
                    locationsOf(2, DetectedActivity.TILTING, confidence = 50)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 70
        }

        @Test
        fun `empty collection returns null`() {
            val result = recognizer.resolve(session, emptyList())

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `single walking location returns WALKING`() {
            val locations = listOf(locationWith(DetectedActivity.WALKING, confidence = 95))

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 95
        }

        @Test
        fun `single running location returns RUNNING`() {
            // walk.confidenceSum = 0, walk.confidenceSum/3 = 0
            // run.confidenceSum = 85 > 0 -> RUNNING
            // confidence = (1.0/1.0) * 85 = 85
            val locations = listOf(locationWith(DetectedActivity.RUNNING, confidence = 85))

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 85
        }
    }

    // -----------------------------------------------------------------------
    // VehicleActivityRecognizer
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("VehicleActivityRecognizer")
    inner class VehicleTests {

        private val recognizer = VehicleActivityRecognizer()

        @Test
        fun `precisionConfidence is 75`() {
            recognizer.precisionConfidence shouldBe 75
        }

        @Test
        fun `all IN_VEHICLE locations returns LAND_VEHICLE`() {
            // 10 IN_VEHICLE: vehicle.count=10, unknown.count=0, bicycle.count=0, onFoot.count=0
            // Bicycle check: 0+0 > (0+0+10)/2=5 -> false
            // Vehicle check: 0+10 > 10*0.2=2 -> true -> LAND_VEHICLE
            val locations = locationsOf(10, DetectedActivity.IN_VEHICLE, confidence = 90)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.LAND_VEHICLE
            result.confidence shouldBe 90
        }

        @Test
        fun `all ON_BICYCLE locations returns BICYCLE`() {
            // 10 ON_BICYCLE: bicycle.count=10, unknown.count=0, onFoot.count=0, vehicle.count=0
            // Bicycle check: 0+10 > (0+0+0)/2=0 -> true -> BICYCLE
            val locations = locationsOf(10, DetectedActivity.ON_BICYCLE, confidence = 85)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.BICYCLE
            result.confidence shouldBe 85
        }

        @Test
        fun `bicycle decision with unknown contributing`() {
            // 3 BICYCLE conf=80, 3 UNKNOWN conf=50, 4 ON_FOOT conf=70
            // bicycle.count=3, unknown.count=3, onFoot.count=4, vehicle.count=0
            // Bicycle check: 3+3=6 > (3+4+0)/2=3 -> true -> BICYCLE
            // bicycle.confidence = 240/3 = 80
            val locations = locationsOf(3, DetectedActivity.ON_BICYCLE, confidence = 80) +
                    locationsOf(3, DetectedActivity.UNKNOWN, confidence = 50) +
                    locationsOf(4, DetectedActivity.ON_FOOT, confidence = 70)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.BICYCLE
            result.confidence shouldBe 80
        }

        @Test
        fun `vehicle decision above 20 percent threshold`() {
            // 3 IN_VEHICLE conf=90, 7 ON_FOOT conf=60
            // vehicle.count=3, unknown.count=0, onFoot.count=7
            // Bicycle check: 0+0 > (0+7+3)/2=5 -> false
            // Vehicle check: 0+3 > 10*0.2=2 -> true -> LAND_VEHICLE
            // vehicle.confidence = 270/3 = 90
            val locations = locationsOf(3, DetectedActivity.IN_VEHICLE, confidence = 90) +
                    locationsOf(7, DetectedActivity.ON_FOOT, confidence = 60)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.LAND_VEHICLE
            result.confidence shouldBe 90
        }

        @Test
        fun `vehicle at exactly 20 percent threshold returns null`() {
            // 2 IN_VEHICLE, 8 ON_FOOT (total=10)
            // vehicle.count=2, unknown.count=0
            // Bicycle check: 0+0 > (0+8+2)/2=5 -> false
            // Vehicle check: 0+2 > 10*0.2=2.0 -> false (2 is NOT > 2.0)
            val locations = locationsOf(2, DetectedActivity.IN_VEHICLE, confidence = 90) +
                    locationsOf(8, DetectedActivity.ON_FOOT, confidence = 60)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `below both thresholds returns null`() {
            // 1 IN_VEHICLE, 1 ON_BICYCLE, 8 ON_FOOT (total=10)
            // vehicle.count=1, bicycle.count=1, unknown.count=0, onFoot.count=8
            // Bicycle check: 0+1=1 > (0+8+1)/2=4 -> false
            // Vehicle check: 0+1=1 > 10*0.2=2 -> false
            val locations = locationsOf(1, DetectedActivity.IN_VEHICLE, confidence = 80) +
                    locationsOf(1, DetectedActivity.ON_BICYCLE, confidence = 80) +
                    locationsOf(8, DetectedActivity.ON_FOOT, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `empty collection returns null`() {
            // Bicycle check: 0+0 > (0+0+0)/2=0 -> false (0 not > 0)
            // Vehicle check: 0+0 > 0*0.2=0 -> false (0 not > 0)
            val result = recognizer.resolve(session, emptyList())

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `unknown does not contribute to vehicle threshold`() {
            val locations = locationsOf(1, DetectedActivity.IN_VEHICLE, confidence = 80) +
                    locationsOf(2, DetectedActivity.UNKNOWN, confidence = 50) +
                    locationsOf(7, DetectedActivity.STILL, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `TILTING goes to unknown bucket`() {
            // 3 TILTING conf=60, 7 IN_VEHICLE conf=90 (total=10)
            // vehicle.count=7, unknown.count=3
            // Bicycle check: 3+0=3 > (3+0+7)/2=5 -> false
            // Vehicle check: 3+7=10 > 10*0.2=2 -> true -> LAND_VEHICLE
            val locations = locationsOf(3, DetectedActivity.TILTING, confidence = 60) +
                    locationsOf(7, DetectedActivity.IN_VEHICLE, confidence = 90)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.LAND_VEHICLE
            result.confidence shouldBe 90
        }

        @Test
        fun `bicycle check uses integer division for denominator`() {
            // Verify that the / 2 is integer division:
            // 3 ON_FOOT, 2 ON_BICYCLE, 0 UNKNOWN (total=5)
            // Bicycle check: 0+2=2 > (0+3+0)/2 = 1 (integer division) -> true -> BICYCLE
            val locations = locationsOf(3, DetectedActivity.ON_FOOT, confidence = 60) +
                    locationsOf(2, DetectedActivity.ON_BICYCLE, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.BICYCLE
            result.confidence shouldBe 80
        }

        @Test
        fun `mixed activities with WALKING and RUNNING go to onFoot`() {
            // WALKING and RUNNING both map to onFoot bucket in VehicleActivityRecognizer
            // 3 WALKING + 3 RUNNING + 4 IN_VEHICLE (total=10)
            // onFoot.count=6, vehicle.count=4, unknown.count=0
            // Bicycle check: 0+0=0 > (0+6+4)/2=5 -> false
            // Vehicle check: 0+4=4 > 10*0.2=2 -> true -> LAND_VEHICLE
            val locations = locationsOf(3, DetectedActivity.WALKING, confidence = 70) +
                    locationsOf(3, DetectedActivity.RUNNING, confidence = 70) +
                    locationsOf(4, DetectedActivity.IN_VEHICLE, confidence = 85)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.LAND_VEHICLE
            result.confidence shouldBe 85
        }
    }
}
