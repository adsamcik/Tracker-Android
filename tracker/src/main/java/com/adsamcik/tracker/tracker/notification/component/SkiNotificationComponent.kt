package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.extension.formatSpeed
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponentEntryPoint
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent
import dagger.hilt.android.EntryPointAccessors

/**
 * Notification component that displays real-time ski stats when ski detection
 * is active and confirmed. Shows run count, vertical drop, and max speed.
 *
 * Returns null (hidden) when not skiing or ski detection not active.
 */
internal class SkiNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
			this::class.java.simpleName,
			0,
			isInTitle = false,
			isInContent = true
		)

	override val titleRes: Int
		get() = R.string.ski_notification_title

	override fun generateText(
		context: Context,
		session: TrackerSession,
		data: CollectionData
	): String? {
		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			NotificationComponentEntryPoint::class.java
		)
		val skiState = entryPoint.trackerServiceController().skiStateFlow.value
			?: return null

		if (!skiState.isConfirmedSkiSession) return null

		val lengthSystem = TrackerSettingsQuick.lengthSystem(context)
		val runNumber = skiState.totalRunCount.coerceAtLeast(1)
		val verticalText = context.resources.formatDistance(
			skiState.totalVerticalM.toDouble(),
			1,
			lengthSystem
		)
		val maxSpeedText = context.resources.formatSpeed(
			context,
			skiState.currentRunMaxSpeedMps.toDouble(),
			1
		)

		return context.getString(
			R.string.ski_notification_text,
			runNumber,
			verticalText,
			maxSpeedText
		)
	}
}
