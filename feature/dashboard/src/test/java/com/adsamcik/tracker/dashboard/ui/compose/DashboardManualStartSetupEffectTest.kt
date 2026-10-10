package com.adsamcik.tracker.dashboard.ui.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reaches the route's real effect; API changes and request work are explicit fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardManualStartSetupEffectTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = mockk<Context>()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    @Before fun installChanges() {
        mockkObject(TrackerServiceApi)
        every { TrackerServiceApi.manualTrackingStartChanges(context) } returns changes
    }

    @After fun removeApiFixture() {
        unmockkObject(TrackerServiceApi)
    }

    @Test fun `Ready signal during the original request tail resumes that same Start`() {
        val state = PendingStart()
        val initialTail = CompletableDeferred<Unit>()
        composeRule.setContent { state.Content() }
        composeRule.waitUntil(5_000) { changes.subscriptionCount.value == 1 }
        composeRule.runOnIdle { state.launchTracked { initialTail.await() } }

        signal()
        composeRule.runOnIdle { assertEquals(emptyList<Long>(), state.requests.toList()) }
        composeRule.runOnIdle { initialTail.complete(Unit) }
        composeRule.waitUntil(5_000) { state.requests.size == 1 }
        composeRule.runOnIdle {
            assertEquals(listOf(17L), state.requests.toList())
            state.pending = false
            state.generation = null
        }
        composeRule.waitUntil(5_000) { changes.subscriptionCount.value == 0 }
    }

    @Test fun `cancelled pending generation cannot retry a retained Ready signal after the request tail`() {
        val state = PendingStart()
        val initialTail = CompletableDeferred<Unit>()
        composeRule.setContent { state.Content() }
        composeRule.waitUntil(5_000) { changes.subscriptionCount.value == 1 }
        composeRule.runOnIdle { state.launchTracked { initialTail.await() } }
        signal()

        composeRule.runOnIdle {
            state.pending = false
            state.generation = null
        }
        composeRule.waitUntil(5_000) { changes.subscriptionCount.value == 0 }
        composeRule.runOnIdle { initialTail.complete(Unit) }
        signal()
        composeRule.runOnIdle { assertEquals(emptyList<Long>(), state.requests.toList()) }
    }


    private fun signal() = composeRule.runOnIdle { assertTrue(changes.tryEmit(Unit)) }

    private inner class PendingStart {
        var visible by mutableStateOf(true)
        var pending by mutableStateOf(true)
        var generation by mutableStateOf<Long?>(17L)
        val requests = CopyOnWriteArrayList<Long>()
        private val activeRequests = mutableSetOf<Job>()
        lateinit var scope: CoroutineScope
        var onRequest: suspend (Long) -> Unit = {}

        fun launchTracked(block: suspend () -> Unit): Job {
            val request = scope.launch { block() }
            activeRequests += request
            request.invokeOnCompletion { activeRequests -= request }
            return request
        }

        @Composable fun Content() {
            if (visible) {
                scope = rememberCoroutineScope()
                DashboardManualStartSetupEffect(
                    context = context,
                    pendingSetup = pending,
                    pendingGeneration = generation,
                    awaitRequests = { activeRequests.toList().forEach { it.join() } },
                    onContinue = { originalGeneration ->
                        launchTracked {
                            requests += originalGeneration
                            onRequest(originalGeneration)
                        }
                    },
                )
            }
        }
    }
}
