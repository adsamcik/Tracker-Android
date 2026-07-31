package com.adsamcik.tracker.activity.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityChangeRequestData
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionRequestData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.backend.ActivityRecognitionBackend
import com.adsamcik.tracker.activity.api.backend.ActivityUpdate
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [DefaultActivityRequestManager] covering request lifecycle,
 * callback dispatching, interval calculation, and cleanup.
 *
 * The manager delegates real-time recognition start/stop to the injected
 * [ActivityRecognitionBackend], which is mocked here so the tests stay isolated
 * from Google Play Services.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityRequestManagerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var backend: ActivityRecognitionBackend
    private lateinit var manager: DefaultActivityRequestManager

    @Before
    fun setup() {
        // hasActivityPermission is inline and checks Build.VERSION.SDK_INT < Q.
        // With @Config(sdk = [28]) the check passes automatically (no runtime
        // permission existed before Android Q), so startUpdates is reached.
        backend = mockk(relaxed = true)
        coEvery { backend.startUpdates(any()) } returns true
        manager = DefaultActivityRequestManager(backend)
    }

    // Marker classes used as request keys
    private class TestClassA
    private class TestClassB
    private class TestClassC

    private fun changeRequest(intervalS: Int = 10): ActivityChangeRequestData {
        return ActivityChangeRequestData(detectionIntervalS = intervalS)
    }

    private fun transitionRequest(
        vararg transitions: ActivityTransitionData
    ): ActivityTransitionRequestData {
        return ActivityTransitionRequestData(
            transitionList = transitions.toList()
        )
    }

    // region request activity
    @Test
    fun `request activity returns true on successful request with changeData`() = runTest {
        val request = ActivityRequestData(
            key = TestClassA::class,
            changeData = changeRequest()
        )

        val result = manager.requestActivity(context, request)

        result shouldBe true
    }

    @Test
    fun `request activity returns true on successful request with transitionData`() = runTest {
        val transition = ActivityTransitionData(
            DetectedActivityType.WALKING,
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
    fun `request activity throws when both changeData and transitionData are null`() = runTest {
        val request = ActivityRequestData(
            key = TestClassA::class,
            changeData = null,
            transitionData = null
        )

        shouldThrow<IllegalArgumentException> {
            manager.requestActivity(context, request)
        }
    }

    @Test
    fun `request activity triggers activity recognition start with permission`() = runTest {
        val request = ActivityRequestData(
            key = TestClassA::class,
            changeData = changeRequest(intervalS = 15)
        )

        manager.requestActivity(context, request)

        coVerify { backend.startUpdates(any()) }
    }

    @Test
    fun `failed start is not cached and an identical request retries`() = runTest {
        var attempts = 0
        coEvery { backend.startUpdates(any()) } answers {
            attempts++
            attempts > 1
        }
        val request = ActivityRequestData(
            key = TestClassA::class,
            changeData = changeRequest(intervalS = 15),
        )

        manager.requestActivity(context, request) shouldBe false
        manager.requestActivity(context, request) shouldBe true

        coVerify(exactly = 2) { backend.startUpdates(any()) }
    }

    @Test
    fun `failed restoration invalidates the applied configuration cache`() = runTest {
        coEvery { backend.startUpdates(any()) } returnsMany listOf(true, false, false, true)
        val original = ActivityRequestData(
            key = TestClassA::class,
            changeData = changeRequest(intervalS = 15),
        )
        val replacement = ActivityRequestData(
            key = TestClassB::class,
            changeData = changeRequest(intervalS = 5),
        )

        manager.requestActivity(context, original) shouldBe true
        manager.requestActivity(context, replacement) shouldBe false
        manager.requestActivity(context, original) shouldBe true

        coVerify(exactly = 4) { backend.startUpdates(any()) }
    }

    @Test
    fun `cancellation during restoration is propagated`() = runTest {
        coEvery { backend.startUpdates(any()) } returns true andThen false andThenThrows
            CancellationException("cancelled")
        val original = ActivityRequestData(
            key = TestClassA::class,
            changeData = changeRequest(intervalS = 15),
        )
        val replacement = ActivityRequestData(
            key = TestClassB::class,
            changeData = changeRequest(intervalS = 5),
        )

        manager.requestActivity(context, original) shouldBe true

        shouldThrow<CancellationException> {
            manager.requestActivity(context, replacement)
        }
    }
    // endregion

    // region remove activity request

        @Test
        fun `removing from unknown initial state cleans possible stale backend subscription`() = runTest {
            manager.removeActivityRequest(context, TestClassC::class)

            coVerify(exactly = 1) { backend.stopUpdates() }
        }

        @Test
        fun `removing non-existent request is a no-op once empty configuration is known`() = runTest {
            val request = ActivityRequestData(
                key = TestClassA::class,
                changeData = changeRequest(),
            )
            manager.requestActivity(context, request)
            manager.removeActivityRequest(context, TestClassA::class)

            manager.removeActivityRequest(context, TestClassC::class)

            coVerify(exactly = 1) { backend.stopUpdates() }
        }

        @Test
        fun `removing last request stops recognition`() = runTest {
            val request = ActivityRequestData(
                key = TestClassA::class,
                changeData = changeRequest()
            )
            manager.requestActivity(context, request)

            manager.removeActivityRequest(context, TestClassA::class)

            coVerify { backend.stopUpdates() }
        }

        @Test
        fun `failed stop retains the request for cleanup retry`() = runTest {
            var stopAttempts = 0
            coEvery { backend.stopUpdates() } coAnswers {
                stopAttempts++
                if (stopAttempts == 1) error("remove failed")
            }
            val request = ActivityRequestData(
                key = TestClassA::class,
                changeData = changeRequest(),
            )
            manager.requestActivity(context, request)

            shouldThrow<IllegalStateException> {
                manager.removeActivityRequest(context, TestClassA::class)
            }
            manager.removeActivityRequest(context, TestClassA::class)

            coVerify(exactly = 2) { backend.stopUpdates() }
        }

        @Test
        fun `failed initial registration cleanup remains retryable`() = runTest {
            coEvery { backend.startUpdates(any()) } returns false
            var stopAttempts = 0
            coEvery { backend.stopUpdates() } coAnswers {
                stopAttempts++
                if (stopAttempts == 1) error("partial registration cleanup failed")
            }
            val request = ActivityRequestData(
                key = TestClassA::class,
                changeData = changeRequest(),
            )

            manager.requestActivity(context, request) shouldBe false
            manager.removeActivityRequest(context, TestClassA::class)

            coVerify(exactly = 2) { backend.stopUpdates() }
        }

        @Test
        fun `cleanup retry for absent failed request preserves remaining active requests`() = runTest {
            coEvery { backend.startUpdates(any()) } returnsMany listOf(true, false, false, true)
            val activeRequest = ActivityRequestData(
                key = TestClassA::class,
                changeData = changeRequest(intervalS = 15),
            )
            val failedRequest = ActivityRequestData(
                key = TestClassB::class,
                changeData = changeRequest(intervalS = 5),
            )

            manager.requestActivity(context, activeRequest) shouldBe true
            manager.requestActivity(context, failedRequest) shouldBe false
            manager.removeActivityRequest(context, TestClassB::class)

            coVerify(exactly = 0) { backend.stopUpdates() }
            coVerify(exactly = 4) { backend.startUpdates(any()) }
        }

        @Test
        fun `removing one of many requests does not stop recognition`() = runTest {
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

            coVerify(exactly = 0) { backend.stopUpdates() }
        }
    // endregion

    // region last activity

        @Test
        fun `lastActivity delegates to backend`() {
            val expected = RecognizedActivity(DetectedActivityType.RUNNING, 85)
            every { backend.lastActivity } returns expected

            manager.lastActivity shouldBe expected
        }

        @Test
        fun `activityUpdates delegates to backend flow`() {
            val updates = MutableSharedFlow<ActivityUpdate>()
            every { backend.activityUpdates } returns updates

            manager.activityUpdates shouldBe updates
        }

        @Test
        fun `transitionUpdates delegates to backend flow`() {
            val updates = MutableSharedFlow<List<TransitionUpdate>>()
            every { backend.transitionUpdates } returns updates

            manager.transitionUpdates shouldBe updates
        }
    // endregion

    // region request data contracts

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
            val data = ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER)

            data.activity shouldBe DetectedActivityType.WALKING
            data.type shouldBe ActivityTransitionType.ENTER
        }

        @Test
        fun `ActivityRequestData stores all fields`() {
            val change = changeRequest(15)
            val transition = transitionRequest(
                ActivityTransitionData(DetectedActivityType.RUNNING, ActivityTransitionType.EXIT)
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
    // endregion
}
