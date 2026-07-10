package com.adsamcik.tracker.activity

import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Activity Request Data")
class ActivityRequestDataTest {

    // -----------------------------------------------------------------------
    // ActivityTransitionType
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ActivityTransitionType")
    inner class TransitionTypeTests {

        @Test
        fun `ENTER maps to ACTIVITY_TRANSITION_ENTER`() {
            ActivityTransitionType.ENTER.value shouldBe 0
        }

        @Test
        fun `EXIT maps to ACTIVITY_TRANSITION_EXIT`() {
            ActivityTransitionType.EXIT.value shouldBe 1
        }

        @Test
        fun `ENTER and EXIT have distinct values`() {
            ActivityTransitionType.ENTER.value shouldNotBe ActivityTransitionType.EXIT.value
        }

        @Test
        fun `enum contains exactly two entries`() {
            ActivityTransitionType.entries.size shouldBe 2
        }
    }

    // -----------------------------------------------------------------------
    // ActivityTransitionData
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ActivityTransitionData")
    inner class TransitionDataTests {

        @Test
        fun `stores activity and type correctly`() {
            val data = ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER)

            data.activity shouldBe DetectedActivityType.WALKING
            data.type shouldBe ActivityTransitionType.ENTER
        }

        @Test
        fun `equals works for identical data`() {
            val a = ActivityTransitionData(DetectedActivityType.IN_VEHICLE, ActivityTransitionType.EXIT)
            val b = ActivityTransitionData(DetectedActivityType.IN_VEHICLE, ActivityTransitionType.EXIT)

            a shouldBe b
        }

        @Test
        fun `differs when activity differs`() {
            val a = ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER)
            val b = ActivityTransitionData(DetectedActivityType.RUNNING, ActivityTransitionType.ENTER)

            a shouldNotBe b
        }

        @Test
        fun `differs when transition type differs`() {
            val a = ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER)
            val b = ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.EXIT)

            a shouldNotBe b
        }

        @Test
        fun `copy preserves fields`() {
            val original = ActivityTransitionData(DetectedActivityType.ON_BICYCLE, ActivityTransitionType.EXIT)
            val copy = original.copy()

            copy shouldBe original
        }

        @Test
        fun `copy can override activity`() {
            val original = ActivityTransitionData(DetectedActivityType.ON_BICYCLE, ActivityTransitionType.EXIT)
            val modified = original.copy(activity = DetectedActivityType.STILL)

            modified.activity shouldBe DetectedActivityType.STILL
            modified.type shouldBe ActivityTransitionType.EXIT
        }
    }

    // -----------------------------------------------------------------------
    // ActivityChangeRequestData
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ActivityChangeRequestData")
    inner class ChangeRequestDataTests {

        @Test
        fun `stores detection interval correctly`() {
            val data = ActivityChangeRequestData(detectionIntervalS = 30)

            data.detectionIntervalS shouldBe 30
        }

        @Test
        fun `stores zero interval`() {
            val data = ActivityChangeRequestData(detectionIntervalS = 0)

            data.detectionIntervalS shouldBe 0
        }
    }

    // -----------------------------------------------------------------------
    // ActivityTransitionRequestData
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ActivityTransitionRequestData")
    inner class TransitionRequestDataTests {

        @Test
        fun `stores transition list correctly`() {
            val transitions = listOf(
                ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER),
                ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.EXIT)
            )
            val data = ActivityTransitionRequestData(transitionList = transitions)

            data.transitionList shouldBe transitions
        }

        @Test
        fun `empty transition list is allowed`() {
            val data = ActivityTransitionRequestData(transitionList = emptyList())

            data.transitionList.size shouldBe 0
        }
    }

    // -----------------------------------------------------------------------
    // ActivityRequestData
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ActivityRequestData")
    inner class RequestDataTests {

        @Test
        fun `default changeData is null`() {
            val data = ActivityRequestData(key = String::class)

            data.changeData.shouldBeNull()
        }

        @Test
        fun `default transitionData is null`() {
            val data = ActivityRequestData(key = String::class)

            data.transitionData.shouldBeNull()
        }

        @Test
        fun `stores key correctly`() {
            val data = ActivityRequestData(key = Int::class)

            data.key shouldBe Int::class
        }

        @Test
        fun `stores changeData when provided`() {
            val changeData = ActivityChangeRequestData(detectionIntervalS = 15)
            val data = ActivityRequestData(
                key = String::class,
                changeData = changeData
            )

            data.changeData.shouldNotBeNull()
            data.changeData?.detectionIntervalS shouldBe 15
        }

        @Test
        fun `stores transitionData when provided`() {
            val transitions = listOf(
                ActivityTransitionData(DetectedActivityType.IN_VEHICLE, ActivityTransitionType.ENTER)
            )
            val transitionData = ActivityTransitionRequestData(transitionList = transitions)
            val data = ActivityRequestData(
                key = String::class,
                transitionData = transitionData
            )

            data.transitionData.shouldNotBeNull()
            data.transitionData?.transitionList?.size shouldBe 1
        }

        @Test
        fun `stores both changeData and transitionData`() {
            val changeData = ActivityChangeRequestData(detectionIntervalS = 10)
            val transitionData = ActivityTransitionRequestData(
                transitionList = listOf(
                    ActivityTransitionData(DetectedActivityType.STILL, ActivityTransitionType.EXIT)
                )
            )
            val data = ActivityRequestData(
                key = String::class,
                changeData = changeData,
                transitionData = transitionData
            )

            data.changeData.shouldNotBeNull()
            data.transitionData.shouldNotBeNull()
        }

        @Test
        fun `equality is based on all fields`() {
            val changeData = ActivityChangeRequestData(detectionIntervalS = 5)
            val a = ActivityRequestData(key = String::class, changeData = changeData)
            val b = ActivityRequestData(key = String::class, changeData = changeData)

            a shouldBe b
        }

        @Test
        fun `different keys produce different instances`() {
            val a = ActivityRequestData(key = String::class)
            val b = ActivityRequestData(key = Int::class)

            a shouldNotBe b
        }
    }
}
