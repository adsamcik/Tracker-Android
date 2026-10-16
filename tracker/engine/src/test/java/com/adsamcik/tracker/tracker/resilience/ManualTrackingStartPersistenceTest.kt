package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.process.ProcessIncarnationIdProvider
import com.adsamcik.tracker.tracker.api.ManualTrackingStartSelection
import com.adsamcik.tracker.tracker.api.TRACKING_TURN_OFF_EXIT_PREFIX
import com.adsamcik.tracker.tracker.api.TrackingCaptureSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualTrackingStartPersistenceTest {
	private val context: Context = ApplicationProvider.getApplicationContext()
	private val selection = ManualTrackingStartSelection(mapOf(TrackingCaptureSource.WIFI to 2L))
	private val processIncarnationIdProvider = ProcessIncarnationIdProvider()
	private fun authority() = SharedPreferencesTrackingLifecycleCommandAuthority(
		context, Dispatchers.IO, processIncarnationIdProvider = processIncarnationIdProvider,
	)

	@Before
	fun clearStore() {
		context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE).edit().clear().commit()
	}

	@Test
	fun `original Start survives same-process store recreation and consumption stays durable`(): Unit = runBlocking {
		val first = authority()
		val request = requireNotNull(first.reserveManualStartIfGeneration(0L, selection))
		val reopened = authority()
		reopened.pendingManualStart shouldBe request
		reopened.consumeManualStart(request.command.generation, selection) shouldBe request.command
		authority().pendingManualStart shouldBe null
		authority().consumeManualStart(request.command.generation, selection) shouldBe null
	}

	@Test
	fun `a pending permission repair cannot resume Start in a different process`(): Unit = runBlocking {
		val request = requireNotNull(authority().reserveManualStartIfGeneration(0L, selection))
		val newProcess = SharedPreferencesTrackingLifecycleCommandAuthority(
			context, Dispatchers.IO, processIncarnationIdProvider = ProcessIncarnationIdProvider(),
		)
		newProcess.pendingManualStart shouldBe null
		newProcess.consumeManualStart(request.command.generation, selection) shouldBe null
	}

	@Test
	fun `concurrent permission callbacks consume the original Start once`(): Unit = runBlocking {
		val store = authority()
		val request = requireNotNull(store.reserveManualStartIfGeneration(0L, selection))
		val callbacks = List(8) { async { store.consumeManualStart(request.command.generation, selection) } }
		callbacks.map { it.await() }.count { it != null } shouldBe 1
		authority().pendingManualStart shouldBe null
	}

	@Test
	fun `handled End still prevents permission callback after process recreation`(): Unit = runBlocking {
		val store = authority()
		val request = requireNotNull(store.reserveManualStartIfGeneration(0L, selection))
		val stop = requireNotNull(store.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1L))
		store.markStopHandled(stop) shouldBe true
		val reopened = authority()
		reopened.pendingManualStart shouldBe null
		reopened.consumeManualStart(request.command.generation, selection) shouldBe null
	}

	@Test
	fun `Off and observed user stop invalidate a pending Start durably`(): Unit = runBlocking {
		for (exit in listOf(TRACKING_TURN_OFF_EXIT_PREFIX + "off", "user-requested:exit")) {
			clearStore()
			val store = authority()
			val request = requireNotNull(store.reserveManualStartIfGeneration(0L, selection))
			if (exit.startsWith(TRACKING_TURN_OFF_EXIT_PREFIX)) {
				requireNotNull(store.reserveTurnOff(exit, 1L, 1L, null) {})
			} else store.recordUserRequestedExit(exit) shouldBe true
			val reopened = authority()
			reopened.pendingManualStart shouldBe null
			reopened.consumeManualStart(request.command.generation, selection) shouldBe null
		}
	}

	@Test
	fun `a newer Start and its choices survive cancellation from the older permission dialog`(): Unit = runBlocking {
		val store = authority()
		val old = requireNotNull(store.reserveManualStartIfGeneration(0L, selection))
		val newer = requireNotNull(store.reserveManualStartIfGeneration(old.command.generation, selection))
		store.cancelManualStart(old.command.generation) shouldBe false
		authority().pendingManualStart shouldBe newer
		store.consumeManualStart(old.command.generation, selection) shouldBe null
		store.consumeManualStart(newer.command.generation, selection) shouldBe newer.command
	}

	@Test
	fun `denied repair stays canceled when the app reopens`(): Unit = runBlocking {
		val store = authority()
		val request = requireNotNull(store.reserveManualStartIfGeneration(0L, selection))
		store.cancelManualStart(request.command.generation) shouldBe true
		authority().consumeManualStart(request.command.generation, selection) shouldBe null
	}

	@Test
	fun `Stop during preflight prevents a delayed reservation`(): Unit = runBlocking {
		val store = authority()
		val expected = store.latestCommandGeneration()
		store.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 1L)
		store.reserveManualStartIfGeneration(expected, selection) shouldBe null
		authority().pendingManualStart shouldBe null
	}
}
