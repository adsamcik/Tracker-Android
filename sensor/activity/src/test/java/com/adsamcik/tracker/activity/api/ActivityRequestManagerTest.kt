package com.adsamcik.tracker.activity.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityChangeRequestData
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionRequestData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.backend.ActivityRecognitionBackend
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Unit tests for [DefaultActivityRequestManager] covering request lifecycle,
 * callback dispatching, interval calculation, and cleanup.
 *
 * The manager delegates real-time recognition start/stop to the injected
 * [ActivityRecognitionBackend], which is mocked here so the tests stay isolated
 * from Google Play Services.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class ActivityRequestManagerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var backend: ActivityRecognitionBackend
    private lateinit var manager: DefaultActivityRequestManager

    @BeforeEach
    fun setup() {
        mockkStatic("com.adsamcik.tracker.activity.ActivityLogKt")
        every { com.adsamcik.tracker.activity.logActivity(any()) } just Runs

        mockkObject(Reporter)
        every { Reporter.report(any<Throwable>()) } just Runs
        every { Reporter.report(any<String>()) } just Runs
        every { Reporter.log(any()) } just Runs

        // hasActivityPermission is inline and checks Build.VERSION.SDK_INT < Q.
        // With @Config(sdk = [28]) the check passes automatically (no runtime
        // permission existed before Android Q), so startUpdates is reached.
        backend = mockk(relaxed = true)
        manager = DefaultActivityRequestManager(backend)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // Marker classes used as request keys
    private class TestClassA
    private class TestClassB
    private class TestClassC

    private fun changeRequest(intervalS: Int = 10): ActivityChangeRequestData {
        return ActivityChangeRequestData(
            detectionIntervalS = intervalS,
            callback = { _, _, _ -> }
        )
    }

    private fun transitionRequest(
        vararg transitions: ActivityTransitionData
    ): ActivityTransitionRequestData {
        return ActivityTransitionRequestData(
            transitionList = transitions.toList(),
            callback = { _, _, _ -> }
        )
    }

    @Nested
    inner class `request activity` {

        @Test
        fun `returns true on successful request with changeData`() {
            val request = ActivityRequestData(
                key = TestClassA::class,
                changeData = changeRequest()
            )

            val result = manager.requestActivity(context, request)

            result shouldBe true
        }

        @Test
        fun `returns true on successful request with transitionData`() {
            val transition = ActivityTransitionData(
                DetectedActivity.WALKING,
                ActivityTransitionType.ENTER
            )
            val request = ActivityRequestData(
                key = TestClassA::class,
                transitionData = transitionRequest(transition)
            )

            val result = manager.requestActivity(context, request)

            result shouldBe true
        }

        @Test
        fun `throws when both changeData and transitionData are null`() {
            val request = ActivityRequestData(
                key = TestClassA::class,
                changeData = null,
                transitionData = null
            )

            assertThrows<IllegalArgumentException> {
                manager.requestActivity(context, request)
            }
        }

        @Test
        fun `triggers activity recognition start with permission`() {
            val request = ActivityRequestData(
                key = TestClassA::class,
                changeData = changeRequest(intervalS = 15)
            )

            manager.requestActivity(context, request)

            verify { backend.startUpdates(any()) }
        }

    }

    @Nested
    inner class `remove activity request` {

        @Test
        fun `removing non-existent request reports error`() {
            manager.removeActivityRequest(context, TestClassC::class)

            verify { Reporter.report(match<String> { it.contains("not subscribed") }) }
        }

        @Test
        fun `removing last request stops recognition`() {
            val request = ActivityRequestData(
                key = TestClassA::class,
                changeData = changeRequest()
            )
            manager.requestActivity(context, request)

            manager.removeActivityRequest(context, TestClassA::class)

            verify { backend.stopUpdates() }
        }

        @Test
        fun `removing one of many requests does not stop recognition`() {
            val requestA = ActivityRequestData(
                key = TestClassA::class,
                changeData = changeRequest(intervalS = 10)
            )
            val requestB = ActivityRequestData(
                key = TestClassB::class,
                changeData = changeRequest(intervalS = 20)
            )
            manager.requestActivity(context, requestA)
            manager.requestActivity(context, requestB)

            manager.removeActivityRequest(context, TestClassA::class)

            verify(exactly = 0) { backend.stopUpdates() }
        }
    }

    @Nested
    inner class `callback dispatching` {

        @Test
        fun `onActivityUpdate invokes all registered change callbacks`() {
            var callbackAInvoked = false
            var callbackBInvoked = false

            val requestA = ActivityRequestData(
                key = TestClassA::class,
                changeData = ActivityChangeRequestData(
                    detectionIntervalS = 10,
                    callback = { _, _, _ -> callbackAInvoked = true }
                )
            )
            val requestB = ActivityRequestData(
                key = TestClassB::class,
                changeData = ActivityChangeRequestData(
                    detectionIntervalS = 20,
                    callback = { _, _, _ -> callbackBInvoked = true }
                )
            )
            manager.requestActivity(context, requestA)
            manager.requestActivity(context, requestB)

            val activity = ActivityInfo(DetectedActivity.WALKING, 80)
            manager.onActivityUpdate(context, activity, 1000L)

            callbackAInvoked shouldBe true
            callbackBInvoked shouldBe true
        }

        @Test
        fun `onActivityUpdate passes correct activity info to callback`() {
            var receivedActivity: ActivityInfo? = null
            var receivedElapsed: Long? = null

            val request = ActivityRequestData(
                key = TestClassA::class,
                changeData = ActivityChangeRequestData(
                    detectionIntervalS = 10,
                    callback = { _, activity, elapsed ->
                        receivedActivity = activity
                        receivedElapsed = elapsed
                    }
                )
            )
            manager.requestActivity(context, request)

            val activity = ActivityInfo(DetectedActivity.RUNNING, 95)
            manager.onActivityUpdate(context, activity, 5000L)

            receivedActivity shouldBe activity
            receivedElapsed shouldBe 5000L
        }

        @Test
        fun `removed request callback is not invoked`() {
            var callbackInvoked = false

            val request = ActivityRequestData(
                key = TestClassA::class,
                changeData = ActivityChangeRequestData(
                    detectionIntervalS = 10,
                    callback = { _, _, _ -> callbackInvoked = true }
                )
            )
            manager.requestActivity(context, request)
            manager.removeActivityRequest(context, TestClassA::class)

            val activity = ActivityInfo(DetectedActivity.WALKING, 80)
            manager.onActivityUpdate(context, activity, 1000L)

            callbackInvoked shouldBe false
        }

        @Test
        fun `transition-only requests do not receive change callbacks`() {
            var changeCallbackInvoked = false

            val transition = ActivityTransitionData(
                DetectedActivity.WALKING,
                ActivityTransitionType.ENTER
            )
            val request = ActivityRequestData(
                key = TestClassA::class,
                transitionData = transitionRequest(transition)
            )
            manager.requestActivity(context, request)

            val activity = ActivityInfo(DetectedActivity.WALKING, 80)
            manager.onActivityUpdate(context, activity, 1000L)

            changeCallbackInvoked shouldBe false
        }
    }

    @Nested
    inner class `last activity` {

        @Test
        fun `lastActivity delegates to backend`() {
            val expected = ActivityInfo(DetectedActivity.RUNNING, 85)
            every { backend.lastActivity } returns expected

            manager.lastActivity shouldBe expected
        }
    }

    @Nested
    inner class `request data contracts` {

        @Test
        fun `ActivityTransitionType ENTER has correct value`() {
            ActivityTransitionType.ENTER.value shouldBe 0
        }

        @Test
        fun `ActivityTransitionType EXIT has correct value`() {
            ActivityTransitionType.EXIT.value shouldBe 1
        }

        @Test
        fun `ActivityTransitionData stores activity and type`() {
            val data = ActivityTransitionData(DetectedActivity.WALKING, ActivityTransitionType.ENTER)

            data.activity shouldBe DetectedActivity.WALKING
            data.type shouldBe ActivityTransitionType.ENTER
        }

        @Test
        fun `ActivityRequestData stores all fields`() {
            val change = changeRequest(15)
            val transition = transitionRequest(
                ActivityTransitionData(DetectedActivity.RUNNING, ActivityTransitionType.EXIT)
            )
            val data = ActivityRequestData(
                key = TestClassA::class,
                changeData = change,
                transitionData = transition
            )

            data.key shouldBe TestClassA::class
            data.changeData shouldBe change
            data.transitionData shouldBe transition
        }

        @Test
        fun `ActivityChangeRequestData stores interval and callback`() {
            val data = changeRequest(30)

            data.detectionIntervalS shouldBe 30
        }
    }
}
