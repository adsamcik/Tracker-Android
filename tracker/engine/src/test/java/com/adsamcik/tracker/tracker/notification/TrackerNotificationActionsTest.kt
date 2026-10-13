package com.adsamcik.tracker.tracker.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.receiver.TrackerNotificationReceiver
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerNotificationActionsTest {
	private fun contextWithLauncher(): Context {
		val application = ApplicationProvider.getApplicationContext<Context>()
		val packageManager = mockk<PackageManager>(relaxed = true)
		every { packageManager.getLaunchIntentForPackage(application.packageName) } returns
			Intent().setComponent(ComponentName(application.packageName, "test.MainActivity"))
		every { packageManager.getActivityInfo(any<ComponentName>(), any<Int>()) } returns ActivityInfo()
		return object : ContextWrapper(application) {
			override fun getPackageManager(): PackageManager = packageManager
		}
	}

	@Test
	fun manualSessionOffersEndAndOffAsDifferentCommands() {
		assertSessionCommands(userInitiated = true)
	}

	@Test
	fun automaticSessionOffersTheSameEndAndOffCommands() {
		assertSessionCommands(userInitiated = false)
	}

	private fun assertSessionCommands(userInitiated: Boolean) {
		val context = contextWithLauncher()
		val notification = TrackerNotificationManager(context, userInitiated).createBuilder().build()
		assertEquals(listOf(context.getString(R.string.shortcut_end_session),
			context.getString(R.string.shortcut_turn_off_tracking)), notification.actions.map { it.title.toString() })
		assertEquals(listOf(TrackerNotificationReceiver.STOP_TRACKING_ACTION,
			TrackerNotificationReceiver.TURN_OFF_TRACKING_ACTION), notification.commands())
		assertTrue(notification.actions.all { it.actionIntent.isImmutable })
	}

	@Test
	fun backgroundNotificationHasOffWithoutAnEndSessionCommand() {
		val context = contextWithLauncher()
		val notification = TrackerNotificationManager.getBackgroundCollectionForegroundNotification(
			context, setOf(SourceKind.WIFI, SourceKind.CELL))
		assertEquals(listOf(TrackerNotificationReceiver.TURN_OFF_TRACKING_ACTION), notification.commands())
	}

	private fun Notification.commands(): List<Int> = actions.map { action ->
		shadowOf(action.actionIntent).savedIntent.getIntExtra(TrackerNotificationReceiver.ACTION_STRING, -1)
	}
}
