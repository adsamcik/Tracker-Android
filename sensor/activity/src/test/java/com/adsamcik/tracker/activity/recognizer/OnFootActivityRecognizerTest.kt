package com.adsamcik.tracker.activity.recognizer

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Focused tests for [OnFootActivityRecognizer] covering walk/run classification,
 * the [OnFootActivityRecognizer.WALK_DENOMINATOR] boundary, confidence calculations,
 * bucket classification, and edge cases.
 */
class OnFootActivityRecognizerTest {

    private val recognizer = OnFootActivityRecognizer()
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

    @Test
    fun `WALK_DENOMINATOR is 3`() {
        OnFootActivityRecognizer.WALK_DENOMINATOR shouldBe 3
    }

    @Nested
    inner class `walking detection` {

        @Test
        fun `pure WALKING locations return WALKING`() {
            val locations = locationsOf(10, DetectedActivity.WALKING, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 80
        }

        @Test
        fun `ON_FOOT maps to walk bucket and returns WALKING`() {
            val locations = locationsOf(10, DetectedActivity.ON_FOOT, confidence = 65)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 65
        }

        @Test
        fun `mixed WALKING and ON_FOOT average confidence`() {
            // Both go to walk bucket: 5×80 + 5×60 = 700, avg = 70
            val locations = locationsOf(5, DetectedActivity.WALKING, confidence = 80) +
                    locationsOf(5, DetectedActivity.ON_FOOT, confidence = 60)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 70
        }

        @Test
        fun `walking confidence averages with varying values`() {
            val locations = listOf(
                locationWith(DetectedActivity.WALKING, confidence = 50),
                locationWith(DetectedActivity.WALKING, confidence = 70),
                locationWith(DetectedActivity.WALKING, confidence = 90)
            )
            // walk.confidenceSum = 210, count = 3, confidence = 70
            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 70
        }
    }

    @Nested
    inner class `running detection` {

        @Test
        fun `pure RUNNING returns RUNNING with full confidence`() {
            // run.confidenceSum=900, walk.confidenceSum=0, 900 > 0/3=0 -> RUNNING
            // confidence = (10.0/10.0) * 90 = 90
            val locations = locationsOf(10, DetectedActivity.RUNNING, confidence = 90)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 90
        }

        @Test
        fun `running confidence scales by proportion of collection`() {
            // 3 RUNNING(90) + 7 WALKING(60)
            // run.confidenceSum=270, walk.confidenceSum=420, 420/3=140, 270>140 -> RUNNING
            // run.confidence = 270/3 = 90, scale = 3.0/10.0 = 0.3
            // confidence = (0.3 * 90).roundToInt() = 27
            val locations = locationsOf(3, DetectedActivity.RUNNING, confidence = 90) +
                    locationsOf(7, DetectedActivity.WALKING, confidence = 60)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 27
        }

        @Test
        fun `running confidence with varying values across locations`() {
            // 4 RUNNING: 60,70,80,90 -> sum=300, count=4, avg=75
            // walk.confidenceSum=0, 0/3=0, 300>0 -> RUNNING
            // confidence = (4.0/4.0) * 75 = 75
            val locations = listOf(
                locationWith(DetectedActivity.RUNNING, confidence = 60),
                locationWith(DetectedActivity.RUNNING, confidence = 70),
                locationWith(DetectedActivity.RUNNING, confidence = 80),
                locationWith(DetectedActivity.RUNNING, confidence = 90)
            )

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 75
        }

        @Test
        fun `running half of collection gives halved confidence`() {
            // 5 RUNNING(80) + 5 WALKING(30)
            // run.confidenceSum=400, walk.confidenceSum=150, 150/3=50, 400>50 -> RUNNING
            // run.confidence=80, scale=5.0/10.0=0.5
            // confidence = (0.5 * 80).roundToInt() = 40
            val locations = locationsOf(5, DetectedActivity.RUNNING, confidence = 80) +
                    locationsOf(5, DetectedActivity.WALKING, confidence = 30)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 40
        }
    }

    @Nested
    inner class `walk denominator boundary` {

        @Test
        fun `run confidence sum exactly at walk divided by 3 returns WALKING`() {
            // 6 WALKING(60): walk.confidenceSum=360, 360/3=120
            // 4 RUNNING(30): run.confidenceSum=120, 120 > 120 -> false -> WALKING
            val locations = locationsOf(6, DetectedActivity.WALKING, confidence = 60) +
                    locationsOf(4, DetectedActivity.RUNNING, confidence = 30)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 60
        }

        @Test
        fun `run confidence sum just above walk divided by 3 returns RUNNING`() {
            // 6 WALKING(60): walk.confidenceSum=360, 360/3=120
            // 4 RUNNING(31): run.confidenceSum=124, 124 > 120 -> true -> RUNNING
            // run.confidence=124/4=31, scale=4.0/10.0=0.4
            // confidence = (0.4 * 31).roundToInt() = 12
            val locations = locationsOf(6, DetectedActivity.WALKING, confidence = 60) +
                    locationsOf(4, DetectedActivity.RUNNING, confidence = 31)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 12
        }

        @Test
        fun `integer division truncation affects boundary`() {
            // 2 WALKING(50): walk.confidenceSum=100, 100/3=33 (truncated from 33.33)
            // 1 RUNNING(34): run.confidenceSum=34, 34 > 33 -> true -> RUNNING
            // run.confidence=34, scale=1.0/3.0=0.333
            // confidence = (0.333 * 34).roundToInt() = 11
            val locations = locationsOf(2, DetectedActivity.WALKING, confidence = 50) +
                    locationsOf(1, DetectedActivity.RUNNING, confidence = 34)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 11
        }

        @Test
        fun `walk confidence sum of zero means any running triggers RUNNING`() {
            // 0 WALKING, 3 RUNNING(10), 7 STILL
            // walk.confidenceSum=0, 0/3=0
            // run.confidenceSum=30, 30 > 0 -> true -> RUNNING
            // run.confidence=10, scale=3.0/10.0=0.3
            // confidence = (0.3 * 10).roundToInt() = 3
            val locations = locationsOf(3, DetectedActivity.RUNNING, confidence = 10) +
                    locationsOf(7, DetectedActivity.STILL, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 3
        }
    }

    @Nested
    inner class `other activity dominance` {

        @Test
        fun `IN_VEHICLE in other bucket dominates on foot`() {
            // walk=2, run=0, onFoot=0, other=8 (IN_VEHICLE)
            // other(8) > 0+2+0=2 -> true -> null
            val locations = locationsOf(2, DetectedActivity.WALKING, confidence = 80) +
                    locationsOf(8, DetectedActivity.IN_VEHICLE, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `ON_BICYCLE in other bucket dominates on foot`() {
            val locations = locationsOf(2, DetectedActivity.WALKING, confidence = 80) +
                    locationsOf(8, DetectedActivity.ON_BICYCLE, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
        }

        @Test
        fun `other exactly equals on foot total does not dominate`() {
            // walk=3, run=1, onFoot=0, other=4 (IN_VEHICLE)
            // other(4) > 0+3+1=4 -> false (not strictly greater)
            val locations = locationsOf(3, DetectedActivity.WALKING, confidence = 70) +
                    locationsOf(1, DetectedActivity.RUNNING, confidence = 70) +
                    locationsOf(4, DetectedActivity.IN_VEHICLE, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldNotBeNull()
        }

        @Test
        fun `STILL does not count as other`() {
            // walk=3, still=7, other=0 -> other(0) > 3 -> false -> WALKING
            val locations = locationsOf(3, DetectedActivity.WALKING, confidence = 75) +
                    locationsOf(7, DetectedActivity.STILL, confidence = 60)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 75
        }

        @Test
        fun `UNKNOWN and TILTING do not count as other`() {
            // walk=3, unknown=7, other=0 -> other(0) > 3 -> false -> WALKING
            val locations = locationsOf(3, DetectedActivity.WALKING, confidence = 80) +
                    locationsOf(4, DetectedActivity.UNKNOWN, confidence = 50) +
                    locationsOf(3, DetectedActivity.TILTING, confidence = 50)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 80
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
        fun `single walking location`() {
            val locations = listOf(locationWith(DetectedActivity.WALKING, confidence = 99))

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 99
        }

        @Test
        fun `single running location`() {
            // run.confidenceSum=88 > 0/3=0 -> RUNNING
            // confidence = (1.0/1.0) * 88 = 88
            val locations = listOf(locationWith(DetectedActivity.RUNNING, confidence = 88))

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.RUNNING
            result.confidence shouldBe 88
        }

        @Test
        fun `all zero confidence walking returns null`() {
            val locations = locationsOf(5, DetectedActivity.WALKING, confidence = 0)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `only STILL locations returns null`() {
            val locations = locationsOf(5, DetectedActivity.STILL, confidence = 90)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `only UNKNOWN locations returns null`() {
            val locations = locationsOf(5, DetectedActivity.UNKNOWN, confidence = 80)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity.shouldBeNull()
            result.confidence shouldBe 0
        }

        @Test
        fun `large collection with walking majority`() {
            // walk.confidenceSum=6750, 6750/3=2250, run.confidenceSum=300, 300>2250 -> false -> WALKING
            val locations = locationsOf(90, DetectedActivity.WALKING, confidence = 75) +
                    locationsOf(5, DetectedActivity.RUNNING, confidence = 60) +
                    locationsOf(5, DetectedActivity.STILL, confidence = 40)

            val result = recognizer.resolve(session, locations)

            result.recognizedActivity shouldBe NativeSessionActivity.WALKING
            result.confidence shouldBe 75
        }
    }
}
