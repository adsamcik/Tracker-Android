package com.adsamcik.tracker.tracker.notification

import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
@DisplayName("WifiPermissionHintNotifier")
class WifiPermissionHintNotifierTest {

	@Test
	fun `settings intent targets current Compose main activity`() {
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()

		val intent = WifiPermissionHintNotifier.createSettingsIntent(context)

		intent.component?.className shouldBe "com.adsamcik.tracker.app.activity.MainActivityCompose"
		intent.getStringExtra("navigate_to") shouldBe "settings"
	}
}
