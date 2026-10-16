package com.adsamcik.tracker.tracker.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingStartDispatchResult
import com.adsamcik.tracker.tracker.api.TrackingStartRequestCoordinator
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.resilience.SharedPreferencesTrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual public Start API and durable command comparison, before Room or Android enqueue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticStartCommandFenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var commands: SharedPreferencesTrackingLifecycleCommandAuthority
    private val coordinator = mockk<TrackingStartRequestCoordinator>()
    private val field = TrackerServiceApi::class.java.getDeclaredField("cachedEntryPoint").apply { isAccessible = true }
    private var previousEntryPoint: Any? = null

    @Before
    fun setup() {
        context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE).edit().clear().commit()
        commands = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
        previousEntryPoint = field.get(TrackerServiceApi)
        // The API's Hilt entry point is module-internal. Supply only its two source boundaries,
        // keeping the actual API and command store; any unexpected Android/service access fails.
        val type = field.type
        field.set(TrackerServiceApi, Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.name) {
                "trackingLifecycleCommandAuthority" -> commands
                "trackingStartRequestCoordinator" -> coordinator
                else -> error("Unexpected entry point access: ${method.name}")
            }
        })
    }

    @After
    fun restoreEntryPoint() { field.set(TrackerServiceApi, previousEntryPoint) }

    @Test
    fun `callback captured before End cannot reserve Start after that End is handled`(): Unit = runBlocking {
        val captured = commands.latestCommandGeneration()
        val stop = requireNotNull(commands.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L))
        commands.markStopHandled(stop) shouldBe true
        rejectCaptured(captured)
        commands.latestCommandGeneration() shouldBe stop.generation
    }

    @Test
    fun `callback captured before Off cannot reserve Start after collection is stopped`(): Unit = runBlocking {
        val captured = commands.latestCommandGeneration()
        commands.recordUserRequestedExit("off") shouldBe true
        val afterOff = commands.latestCommandGeneration()
        rejectCaptured(captured)
        commands.latestCommandGeneration() shouldBe afterOff
        commands.pendingCollectionResumeExitId shouldBe "off"
    }

    @Test
    fun `callback captured before another Start cannot replace the newer Start`(): Unit = runBlocking {
        val captured = commands.latestCommandGeneration()
        val newer = requireNotNull(commands.reserveStart())
        rejectCaptured(captured)
        commands.latestCommandGeneration() shouldBe newer.generation
    }

    @Test
    fun `automatic compatibility API cannot invent a missing original callback generation`(): Unit = runBlocking {
        TrackerServiceApi.startService(context, false, automaticTrigger = mockk()) shouldBe false
        TrackerServiceApi.startServiceAndAwaitEnqueueResult(context, false,
            automaticTrigger = mockk()) shouldBe TrackingStartDispatchResult.Terminal("AUTOMATIC_START_CONTEXT_MISSING")
        commands.latestCommandGeneration() shouldBe 0L
        coVerify(exactly = 0) { coordinator.prepare(any()) }
    }

    private suspend fun rejectCaptured(generation: Long) {
        TrackerServiceApi.startServiceAndAwaitEnqueueResult(context, false,
            automaticTrigger = mockk<AutomaticTrackingStartTrigger>(), expectedCommandGeneration = generation) shouldBe
            TrackingStartDispatchResult.Terminal("START_COMMAND_SUPERSEDED")
        coVerify(exactly = 0) { coordinator.prepare(any()) }
    }
}
