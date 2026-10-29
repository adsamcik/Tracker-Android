package com.adsamcik.tracker.tracker.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import com.adsamcik.tracker.tracker.resilience.SharedPreferencesTrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterSetupResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual app-owned observer and durable command store; provider work is observed at its existing reconciler. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundCollectionResumeTest {
	private val context: Context = ApplicationProvider.getApplicationContext()
	private val dispatcher = StandardTestDispatcher()
	private val entryPoint = mockk<BackgroundTrackingApiEntryPoint>(relaxed = true)
	private lateinit var commands: SharedPreferencesTrackingLifecycleCommandAuthority
	private val reconciledOffStates = mutableListOf<Boolean>()

	@Before
	fun setUp() {
		BackgroundTrackingApi.shutdown()
		context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE).edit().clear().commit()
		Dispatchers.setMain(dispatcher)
		commands = SharedPreferencesTrackingLifecycleCommandAuthority(context,
			UnconfinedTestDispatcher(dispatcher.scheduler), BootClockDomainProvider { "boot" })
		val reader = mockk<CurrentTrackingPurposeAvailabilityReader>()
		every { reader.availability } returns MutableStateFlow(CurrentTrackingPurposeAvailability.SAFE_DEFAULT)
		every { reader.authorityRevision } returns MutableStateFlow(TrackingPurposeAuthorityRevision.UNAVAILABLE)
		every { entryPoint.currentTrackingPurposeAvailabilityReader() } returns reader
		every { entryPoint.trackingLifecycleCommandAuthority() } returns commands
		every { entryPoint.chosenSourceWriterSetup().stateChanges() } returns flowOf(emptyList())
		coEvery { entryPoint.chosenSourceWriterSetup().ensureCurrentChosenSources() } returns
			ChosenSourceWriterSetupResult(emptyMap())
		coEvery { entryPoint.trackingPurposeSettingsReconciler().reconcileCurrentSettings() } answers {
			reconciledOffStates += commands.pendingCollectionResumeExitId != null
			TrackingPurposeSettingsReconciliationResult.Complete(emptySet())
		}
		// No settings, readiness or policy changes can supply the Resume wakeup in these cases.
		every { entryPoint.trackingParamsRepository().data } returns emptyFlow()
		every { entryPoint.sourcePolicyRepository().states } returns emptyFlow()
		mockkObject(PreferenceFlows)
		every { PreferenceFlows.boolean(any(), any<Int>(), any<Int>()) } returns emptyFlow()
		every { PreferenceFlows.intFromString(any(), any<Int>(), any<Int>()) } returns emptyFlow()
		BackgroundTrackingApi::class.java.getDeclaredField("entryPoint").apply { isAccessible = true }
			.set(BackgroundTrackingApi, entryPoint)
	}

	@After
	fun tearDown() {
		BackgroundTrackingApi.shutdown()
		unmockkObject(PreferenceFlows)
		Dispatchers.resetMain()
		context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE).edit().clear().commit()
	}

	@Test
	fun `committed sessionless Resume wakes the app owner after its Settings caller is gone`() = runTest(dispatcher) {
		val exit = TRACKING_TURN_OFF_EXIT_PREFIX + "settings-resume"
		commands.recordUserRequestedExit(exit) shouldBe true
		BackgroundTrackingApi.initialize(context)
		runCurrent()
		reconciledOffStates shouldBe listOf(true)
		val settingsScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
		settingsScope.async { commands.resumeCollectionAfterExplicitTurnOff(exit) }.await() shouldBe true
		settingsScope.cancel()
		// The application observer has not run yet, and is independent of that cancelled scope.
		reconciledOffStates shouldBe listOf(true)
		runCurrent()
		reconciledOffStates shouldBe listOf(true, false)
		commands.pendingCollectionResumeExitId shouldBe null
		commands.latestCommandGeneration() shouldBe 1L
		commands.acceptedStartGeneration.value shouldBe 0L
	}

	@Test
	fun `newer Off wins before the app processes a queued Resume wakeup`() = runTest(dispatcher) {
		val exit = TRACKING_TURN_OFF_EXIT_PREFIX + "first-off"
		commands.recordUserRequestedExit(exit) shouldBe true
		BackgroundTrackingApi.initialize(context)
		runCurrent()
		reconciledOffStates shouldBe listOf(true)
		commands.resumeCollectionAfterExplicitTurnOff(exit) shouldBe true
		val newerExit = TRACKING_TURN_OFF_EXIT_PREFIX + "newer-off"
		commands.recordUserRequestedExit(newerExit) shouldBe true
		commands.resumeCollectionAfterExplicitTurnOff(exit) shouldBe false
		runCurrent()
		reconciledOffStates.all { it } shouldBe true
		commands.pendingCollectionResumeExitId shouldBe newerExit
		commands.collectionResumeRequired.value shouldBe true
		commands.acceptedStartGeneration.value shouldBe 0L
	}
}
