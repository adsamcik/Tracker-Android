package com.adsamcik.tracker.osm.imp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.adsamcik.tracker.osm.R

/**
 * Builds the foreground notification for [OsmImportWorker] and ensures the
 * dedicated notification channel exists.
 *
 * The channel is module-local: the OSM import is a rare user-initiated action
 * that should not be silenced together with the persistent tracking channel.
 *
 * Channel importance is LOW (no sound) — the user already knows the import is
 * happening because they tapped "Import" themselves.
 */
internal object OsmImportNotifications {

	const val CHANNEL_ID: String = "osm_import"
	const val NOTIFICATION_ID: Int = 906_000
	const val NOTIFICATION_ID_COMPLETED: Int = 906_001
	const val NOTIFICATION_ID_FAILED: Int = 906_002

	/**
	 * Builds the ongoing import progress notification. Used as the foreground
	 * service notification AND updated mid-import via [NotificationManagerCompat.notify].
	 */
	fun buildProgress(
		context: Context,
		fileName: String,
		waysProcessed: Long,
	): android.app.Notification {
		ensureChannel(context)
		return NotificationCompat.Builder(context, CHANNEL_ID)
			.setSmallIcon(android.R.drawable.stat_sys_download)
			.setContentTitle(context.getString(R.string.osm_import_notification_title))
			.setContentText(
				context.getString(
					R.string.osm_import_notification_content,
					fileName,
					waysProcessed,
				),
			)
			.setProgress(0, 0, true)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setSilent(true)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.apply {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					foregroundServiceBehavior =
						NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
				}
			}
			.build()
	}

	/** Builds the completion notification shown when the import finished successfully. */
	fun buildCompleted(context: Context, fileName: String): android.app.Notification {
		ensureChannel(context)
		return NotificationCompat.Builder(context, CHANNEL_ID)
			.setSmallIcon(android.R.drawable.stat_sys_download_done)
			.setContentTitle(context.getString(R.string.osm_import_complete_title))
			.setContentText(context.getString(R.string.osm_import_complete_content, fileName))
			.setAutoCancel(true)
			.setSilent(true)
			.setCategory(NotificationCompat.CATEGORY_STATUS)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build()
	}

	/** Builds the failure notification shown when parsing failed for any reason. */
	fun buildFailed(context: Context, fileName: String, reason: String): android.app.Notification {
		ensureChannel(context)
		return NotificationCompat.Builder(context, CHANNEL_ID)
			.setSmallIcon(android.R.drawable.stat_notify_error)
			.setContentTitle(context.getString(R.string.osm_import_failed_title))
			.setContentText(
				context.getString(R.string.osm_import_failed_content, fileName, reason),
			)
			.setAutoCancel(true)
			.setCategory(NotificationCompat.CATEGORY_ERROR)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setStyle(NotificationCompat.BigTextStyle().bigText(reason))
			.build()
	}

	private fun ensureChannel(context: Context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
		createChannel(context)
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private fun createChannel(context: Context) {
		val manager = context.getSystemService<NotificationManager>() ?: return
		if (manager.getNotificationChannel(CHANNEL_ID) != null) return
		val channel = NotificationChannel(
			CHANNEL_ID,
			context.getString(R.string.osm_import_notification_channel),
			NotificationManager.IMPORTANCE_LOW,
		).apply {
			description = context.getString(R.string.osm_import_notification_channel_description)
			enableVibration(false)
			enableLights(false)
		}
		manager.createNotificationChannel(channel)
	}
}
