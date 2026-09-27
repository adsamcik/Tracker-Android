package com.adsamcik.tracker.tracker.policy

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatteryAwarePolicyPowerSaveTest {
	@Test
	fun `power saver flow emits initial state and distinct mode transitions`() = runTest {
		val application = ApplicationProvider.getApplicationContext<Application>()
		val powerManager = application.getSystemService(PowerManager::class.java)
		val shadowPowerManager = shadowOf(powerManager)
		shadowPowerManager.setIsPowerSaveMode(false)
		val subject = BatteryAwarePolicy(application)
		val observed = async { subject.powerSaveModeUpdates.take(3).toList() }
		runCurrent()

		shadowPowerManager.setIsPowerSaveMode(true)
		application.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
		shadowOf(Looper.getMainLooper()).idle()
		runCurrent()
		application.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
		shadowOf(Looper.getMainLooper()).idle()
		runCurrent()
		shadowPowerManager.setIsPowerSaveMode(false)
		application.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
		shadowOf(Looper.getMainLooper()).idle()
		runCurrent()

		observed.await() shouldBe listOf(false, true, false)
	}
}
