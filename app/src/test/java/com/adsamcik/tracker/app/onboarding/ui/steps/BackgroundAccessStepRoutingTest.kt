package com.adsamcik.tracker.app.onboarding.ui.steps

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BackgroundAccessStepRoutingTest {

	@Test
	@Config(sdk = [29])
	fun `API 29 uses the background runtime permission route`() {
		backgroundLocationGrantRoute(29) shouldBe BackgroundLocationGrantRoute.RUNTIME_PERMISSION
	}

	@Test
	@Config(sdk = [30])
	fun `API 30 routes background access to app Settings with the platform label`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val localizedContext = mockk<Context>()
		val packageManager = mockk<PackageManager>()
		every { localizedContext.packageManager } returns packageManager
		every { packageManager.backgroundPermissionOptionLabel } returns "Localized all-the-time option"

		backgroundLocationGrantRoute(30) shouldBe BackgroundLocationGrantRoute.APP_LOCATION_SETTINGS
		localizedContext.backgroundPermissionOptionLabel() shouldBe "Localized all-the-time option"
		context.appLocationSettingsIntent().let { intent ->
			intent.action shouldBe Settings.ACTION_APPLICATION_DETAILS_SETTINGS
			intent.data?.scheme shouldBe "package"
			intent.data?.schemeSpecificPart shouldBe context.packageName
		}
	}

	@Test
	@Config(sdk = [34])
	fun `API 37 keeps using the Settings route rather than a runtime request`() {
		backgroundLocationGrantRoute(37) shouldBe BackgroundLocationGrantRoute.APP_LOCATION_SETTINGS
	}
}
