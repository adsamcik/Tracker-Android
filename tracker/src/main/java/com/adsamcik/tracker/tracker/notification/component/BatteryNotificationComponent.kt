package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class BatteryNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
			this::class.java.simpleName,
			0,
			isInTitle = false,
			isInContent = true
		)

	override val titleRes: Int
		get() = R.string.battery_title

	override fun generateText(
		context: Context,
		session: TrackerSession,
		data: CollectionData
	): String? {
		val level = getBatteryLevel(context)
		if (level < 0) return null
		if (level > LOW_BATTERY_THRESHOLD) return null
		return context.getString(R.string.battery_low_warning, level)
	}

	private fun getBatteryLevel(context: Context): Int {
		val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
			?: return -1
		val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
		val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
		if (level < 0 || scale <= 0) return -1
		return (level * 100) / scale
	}

	companion object {
		private const val LOW_BATTERY_THRESHOLD = 15
	}
}
