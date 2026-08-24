package com.adsamcik.tracker.tracker.source.runtime

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.wifi.WifiManager
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import org.junit.Test

class WifiSourceBackendTest {
	@Test
	fun `registration cancellation and fatal errors propagate with exact handle retained`() {
		val removalAttempts = mutableListOf<BroadcastReceiver>()
		var failure: Throwable = CancellationException("cancel")
		val backend = AndroidWifiSourceBackend(
			wifiManager = mockk<WifiManager>(relaxed = true),
			registerReceiver = { _, _ -> throw failure },
			unregisterReceiver = { receiver -> removalAttempts += receiver },
		)

		assertFailsWith<CancellationException> { backend.start { } }
		backend.stop() shouldBe true
		failure = AssertionError("fatal")
		assertFailsWith<AssertionError> { backend.start { } }
		backend.stop() shouldBe true
		removalAttempts.size shouldBe 2
	}

	@Test
	fun `partial registration retains the provisional receiver for exact cleanup`() {
		val registrationAttempts = mutableListOf<BroadcastReceiver>()
		val removalAttempts = mutableListOf<BroadcastReceiver>()
		var failAfterSideEffect = true
		val backend = AndroidWifiSourceBackend(
			wifiManager = mockk<WifiManager>(relaxed = true),
			registerReceiver = { receiver, _: IntentFilter ->
				registrationAttempts += receiver
				if (failAfterSideEffect) error("injected partial registration failure")
			},
			unregisterReceiver = { receiver -> removalAttempts += receiver },
		)

		backend.start { } shouldBe false
		val provisional = registrationAttempts.single()
		backend.start { } shouldBe false
		registrationAttempts shouldBe listOf(provisional)

		backend.stop() shouldBe true
		removalAttempts shouldBe listOf(provisional)
		failAfterSideEffect = false
		backend.start { } shouldBe true
		registrationAttempts.size shouldBe 2
		(registrationAttempts.last() === provisional) shouldBe false
	}

	@Test
	fun `failed unregister retains the exact receiver and blocks a replacement`() {
		val registered = mutableListOf<BroadcastReceiver>()
		val removed = mutableListOf<BroadcastReceiver>()
		var rejectRemoval = true
		val backend = AndroidWifiSourceBackend(
			wifiManager = mockk<WifiManager>(relaxed = true),
			registerReceiver = { receiver, _: IntentFilter -> registered += receiver },
			unregisterReceiver = { receiver ->
				if (rejectRemoval) error("injected unregister failure")
				removed += receiver
			},
		)

		backend.start { } shouldBe true
		val first = registered.single()
		backend.stop() shouldBe false
		backend.start { } shouldBe false
		registered shouldBe listOf(first)

		rejectRemoval = false
		backend.stop() shouldBe true
		removed shouldBe listOf(first)
		backend.start { } shouldBe true
		registered.size shouldBe 2
		(registered.last() === first) shouldBe false
	}
}
