package com.adsamcik.tracker.tracker.source.runtime

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.wifi.WifiManager
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Test

class WifiSourceBackendTest {
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
