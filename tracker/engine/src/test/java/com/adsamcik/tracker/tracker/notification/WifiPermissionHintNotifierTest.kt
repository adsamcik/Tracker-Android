package com.adsamcik.tracker.tracker.notification

import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiPermissionHintNotifierTest {

	@Test
	fun `settings intent targets current Compose main activity`() {
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()

		val intent = WifiPermissionHintNotifier.createSettingsIntent(context)

		intent.component?.className shouldBe "com.adsamcik.tracker.app.activity.MainActivityCompose"
		intent.getStringExtra("navigate_to") shouldBe "settings"
	}
}
