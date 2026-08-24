package com.adsamcik.tracker.shared.base.time

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBootClockDomainProviderTest {
	private val application = ApplicationProvider.getApplicationContext<Application>()

	@Test
	fun `readable Android boot count has one canonical persisted representation`() =
		withRestoredBootCount {
			Settings.Global.putInt(application.contentResolver, Settings.Global.BOOT_COUNT, 42)

			AndroidBootClockDomainProvider(application).current() shouldBe
				"android-boot-count:42"
		}

	@Test
	fun `unreadable boot count uses one process-stable conservative domain`() =
		withRestoredBootCount {
			Settings.Global.putString(
				application.contentResolver,
				Settings.Global.BOOT_COUNT,
				null,
			)

			val first = AndroidBootClockDomainProvider(application).current()
			val second = AndroidBootClockDomainProvider(application).current()

			first shouldStartWith "process:"
			second shouldBe first
		}

	private fun withRestoredBootCount(block: () -> Unit) {
		val resolver = application.contentResolver
		val previous = Settings.Global.getString(resolver, Settings.Global.BOOT_COUNT)
		try {
			block()
		} finally {
			Settings.Global.putString(resolver, Settings.Global.BOOT_COUNT, previous)
		}
	}
}
