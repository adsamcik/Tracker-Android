package com.adsamcik.tracker.game.goals

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.game.R

internal enum class GoalPeriod(val stringResource: Int) {
	DAY(R.string.goals_daily_goal),
	WEEK(R.string.goals_weekly_goal),
}

internal fun buildGoalReachedNotification(
	context: Context,
	period: GoalPeriod,
): Notification {
	val encouragement = context.resources.getStringArray(R.array.goals_encouragement).random()
	val periodString = context.getString(period.stringResource)
	val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
		putExtra("navigate_to", "dashboard")
		putExtra("scroll_to", "goals")
		addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
	}
	val pendingIntent = PendingIntent.getActivity(
		context,
		0,
		launchIntent,
		PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
	)
	return NotificationCompat.Builder(
		context,
		context.getString(com.adsamcik.tracker.shared.base.R.string.channel_goals_id),
	)
		.setContentTitle(
			context.getString(
				R.string.goals_reached_notification,
				encouragement,
				periodString,
			),
		)
		.setSmallIcon(R.drawable.ic_flag)
		.setContentIntent(pendingIntent)
		.setAutoCancel(true)
		.build()
}
