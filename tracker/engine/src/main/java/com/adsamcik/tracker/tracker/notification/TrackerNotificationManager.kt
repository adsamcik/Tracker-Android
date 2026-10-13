package com.adsamcik.tracker.tracker.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.receiver.TrackerNotificationReceiver

/**
 * Notification manager for tracker service and related modules.
 */
class TrackerNotificationManager(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") isUserInitiatedSession: Boolean,
    notificationStyled: Boolean = true,
) {
	private var notificationManager: NotificationManager = context.notificationManager

	private var useStyle = notificationStyled

	fun createBuilder(): NotificationCompat.Builder {
		return createBuilder(
			context,
			useStyle
		).addTrackerActions()
	}

	private fun createBuilder(publicTitleRes: Int): NotificationCompat.Builder = createBuilder(
		context = context,
		useStyle = useStyle,
		publicTitleRes = publicTitleRes,
	).addTrackerActions()

	private fun NotificationCompat.Builder.addTrackerActions(): NotificationCompat.Builder {
		val resources = context.resources
		val stopIntent = Intent(context, TrackerNotificationReceiver::class.java)

		stopIntent.putExtra(TrackerNotificationReceiver.ACTION_STRING, TrackerNotificationReceiver.STOP_TRACKING_ACTION)
		val stop = PendingIntent.getBroadcast(
			context,
			0,
			stopIntent,
			PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_IMMUTABLE)
		)
		addAction(
			com.adsamcik.tracker.shared.base.R.drawable.ic_pause_circle_filled_black_24dp,
			resources.getString(R.string.shortcut_end_session),
			stop,
		)
		return addTurnOffAction(context)
	}

	fun notify(builder: NotificationCompat.Builder) {
		notificationManager.notify(NOTIFICATION_ID, builder.build())
	}

	companion object {
		const val NOTIFICATION_ID: Int = -7643
		private const val START_FAILED_NOTIFICATION_ID: Int = -7644

		internal fun cancelTrackingNotification(context: Context) {
			context.notificationManager.cancel(NOTIFICATION_ID)
		}

		/** Background collection has no live session counters or live Stop action. */
		fun getBackgroundLocationForegroundNotification(context: Context): Notification =
			getBackgroundCollectionForegroundNotification(context,
				setOf(com.adsamcik.tracker.tracker.source.model.SourceKind.LOCATION))

		fun getBackgroundCollectionForegroundNotification(
			context: Context,
			sources: Set<com.adsamcik.tracker.tracker.source.model.SourceKind>,
		): Notification {
			val names = sources.sortedBy { it.stableCode }.map { source ->
				context.getString(when (source) {
					com.adsamcik.tracker.tracker.source.model.SourceKind.LOCATION -> R.string.notification_background_source_location
					com.adsamcik.tracker.tracker.source.model.SourceKind.WIFI -> R.string.notification_background_source_wifi
					com.adsamcik.tracker.tracker.source.model.SourceKind.CELL -> R.string.notification_background_source_cell
					else -> R.string.notification_background_source_other
				})
			}.joinToString(", ")
			return createBuilder(context, useStyle = false,
				publicTitleRes = R.string.notification_background_collection_title)
				.setContentTitle(context.getString(R.string.notification_background_collection_title))
				.setContentText(context.getString(R.string.notification_background_collection_text, names))
				.addTurnOffAction(context)
				.build()
		}

		fun getForegroundNotification(
			context: Context,
			usesLocation: Boolean,
			isUserInitiatedSession: Boolean? = null,
			notificationStyled: Boolean = true,
		): Notification {
			val builder = if (isUserInitiatedSession == null) {
				createBuilder(context, notificationStyled)
			} else {
				TrackerNotificationManager(
					context = context,
					isUserInitiatedSession = isUserInitiatedSession,
					notificationStyled = notificationStyled,
				).createBuilder()
			}
			return builder
				.setContentTitle(
					context.getString(
						if (usesLocation) {
							R.string.notification_starting
						} else {
							R.string.notification_starting_without_location
						}
					)
				)
				.setContentText(
					context.getString(
						if (usesLocation) {
							R.string.notification_tracker_active_ticker
						} else {
							R.string.notification_tracker_active_without_location
						}
					)
				)
				.build()
		}

		/** Honest providerless shell shown while the startup gate restores durable authority. */
		fun getStartupRecoveryForegroundNotification(
			context: Context,
			isUserInitiatedSession: Boolean,
		): Notification = TrackerNotificationManager(
			context = context,
			isUserInitiatedSession = isUserInitiatedSession,
		).createBuilder(R.string.notification_tracking_restoring_public)
			.setContentTitle(context.getString(R.string.notification_tracking_restoring_title))
			.setContentText(context.getString(R.string.notification_tracking_restoring_text))
			.build()

		fun postStartFailedNotification(context: Context) {
			TrackerNotificationChannels.ensureTrackingChannel(context)
			val resources = context.resources
			val builder = NotificationCompat.Builder(
				context,
				resources.getString(com.adsamcik.tracker.shared.base.R.string.channel_track_id)
			)
				.setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_signals)
				.setCategory(NotificationCompat.CATEGORY_ERROR)
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setContentTitle(
					resources.getString(R.string.notification_tracking_start_failed_title)
				)
				.setContentText(
					resources.getString(R.string.notification_tracking_start_failed_text)
				)
				.setAutoCancel(true)

			context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
				builder.setContentIntent(
					TaskStackBuilder.create(context).run {
						addNextIntentWithParentStack(launch)
						getPendingIntent(
							0,
							PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_IMMUTABLE)
						)
					}
				)
			}

			context.notificationManager.notify(START_FAILED_NOTIFICATION_ID, builder.build())
		}

		private fun createBuilder(
			context: Context,
			useStyle: Boolean,
			publicTitleRes: Int = R.string.notification_tracker_active_ticker,
		): NotificationCompat.Builder {
			TrackerNotificationChannels.ensureTrackingChannel(context)
			val resources = context.resources
			val intent =
				requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
			// Lock-screen safety: notification components are user-configurable and can
			// include latitude/longitude, current speed, transport mode, etc. Show a
			// redacted "Tracker is active" payload on the lock screen via publicVersion,
			// and hide the real content (VISIBILITY_PRIVATE) so coordinates can't be
			// glimpsed from a locked device. The full content is only revealed after
			// authentication.
			val publicVersion = NotificationCompat.Builder(
				context,
				resources.getString(com.adsamcik.tracker.shared.base.R.string.channel_track_id),
			)
				.setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_signals)
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setContentTitle(resources.getString(publicTitleRes))
				.setOngoing(true)
				.build()

			return NotificationCompat.Builder(
				context,
				resources.getString(com.adsamcik.tracker.shared.base.R.string.channel_track_id)
			)
				.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
				.setPublicVersion(publicVersion)
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setOnlyAlertOnce(true)
				.setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_signals) // the done icon
				.setTicker(resources.getString(R.string.notification_tracker_active_ticker)) // the done text
				.setWhen(Time.nowMillis) // the time stamp
				.setOngoing(true)
				.setContentIntent(TaskStackBuilder.create(context).run {
					addNextIntentWithParentStack(intent)
					getPendingIntent(
						0,
						PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_IMMUTABLE)
					)
				}).also {
					if (useStyle) {
						// Use Material 3 colors based on system theme instead of legacy StyleManager
						val isDarkTheme = context.resources.configuration.uiMode and 
							Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
						val backgroundColor = if (isDarkTheme) {
							0xFF1C1B1F.toInt() // Material 3 dark surface
						} else {
							0xFFFEF7FF.toInt() // Material 3 light surface  
						}
						it.setColor(backgroundColor)
							.setColorized(true)
					}
				}
		}
	}
}

private fun NotificationCompat.Builder.addTurnOffAction(context: Context): NotificationCompat.Builder {
	val intent = Intent(context, TrackerNotificationReceiver::class.java).putExtra(
		TrackerNotificationReceiver.ACTION_STRING,
		TrackerNotificationReceiver.TURN_OFF_TRACKING_ACTION,
	)
	val pendingIntent = PendingIntent.getBroadcast(
		context,
		2,
		intent,
		PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_IMMUTABLE),
	)
	return addAction(
		com.adsamcik.tracker.shared.base.R.drawable.ic_stop_black_24dp,
		context.getString(R.string.shortcut_turn_off_tracking),
		pendingIntent,
	)
}
