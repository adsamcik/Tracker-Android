package com.adsamcik.tracker.game.goals.data.abstraction

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import java.time.ZonedDateTime

/** Shared lifecycle and presentation-state behavior for concrete goal periods. */
abstract class BaseGoal(
	protected val persistence: GoalPersistence,
) : Goal {

	abstract val goalReachedPreferenceKey: String

	abstract val period: GoalPeriod
	override var onValueChanged: (value: Int) -> Unit = {}
	override var onTargetChanged: (value: Int) -> Unit = {}

	override var value: Int = 0
		protected set(value) {
			field = value
			onValueChanged(value)
		}

	override var target: Int = 0
		protected set(value) {
			field = value
			onTargetChanged(value)
		}

	/**
	 * Time of last report.
	 * Used instead of boolean to provide better durability against unreliable resets.
	 */
	private var lastReportTime: Int = 0

	override var isEnabled: Boolean = false
		protected set

	/**
	 * Should be implemented instead of onEnable
	 */
	protected abstract suspend fun onEnableInternal(context: Context)

	/**
	 * Should be implemented instead of onDisable
	 */
	protected abstract suspend fun onDisableInternal(context: Context)

	override suspend fun onEnable(context: Context) {
		onEnableInternal(context)
		updateFromDatabase(context)
		persistence.load(goalReachedPreferenceKey)?.let { lastReportTime = it }
		isEnabled = true
	}

	override suspend fun onDisable(context: Context) {
		onDisableInternal(context)
		isEnabled = false
	}

	override fun onSessionPresentationUpdated(
		session: TrackerSessionSnapshot,
		isNewSession: Boolean,
	) {
		onSessionUpdatedInternal(session, isNewSession)
	}

	protected abstract fun onSessionUpdatedInternal(
		session: TrackerSessionSnapshot,
		isNewSession: Boolean,
	)

	override suspend fun onNewDay(context: Context, day: ZonedDateTime) {
		val time = getGoalTime(day)
		// This will trigger recount once a day if the goal is not reached, but
		// because the cost should not be very high, the extra code complexity does not seem
		// to be worth it.
		if (time != lastReportTime) {
			updateFromDatabase(context)
		}
	}

	/**
	 * Called when value should be updated with data from database.
	 */
	protected abstract suspend fun updateFromDatabase(context: Context)

	/**
	 * Rounds date time to goal time.
	 */
	protected abstract fun getGoalTime(day: ZonedDateTime): Int

	override fun buildNotification(context: Context): Notification {
		val encouragement = context.resources.getStringArray(R.array.goals_encouragement).random()
		val periodString = context.getString(period.stringResource)

		// Build an intent to open the app without a compile-time dependency on :app
		// Use the default launch intent and pass the extra recognized by MainActivity
		val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
			putExtra("navigate_to", "dashboard")
			putExtra("scroll_to", "goals")
			addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
		}
		val pendingIntent = PendingIntent.getActivity(
			context,
			0,
			launchIntent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

	return NotificationCompat.Builder(
		context,
		context.getString(com.adsamcik.tracker.shared.base.R.string.channel_goals_id)
	)
				.setContentTitle(
						context.getString(
								R.string.goals_reached_notification,
								encouragement,
								periodString
						)
				)
				.setSmallIcon(R.drawable.ic_flag)
				.setContentIntent(pendingIntent)
				.setAutoCancel(true)
				.build()
	}

	/**
	 * Determines how long the goal is going to take
	 */
	enum class GoalPeriod {
		Day {
			override val stringResource: Int
				get() = R.string.goals_daily_goal
		},
		Week {
			override val stringResource: Int
				get() = R.string.goals_weekly_goal
		},
		Month {
			override val stringResource: Int
				get() = R.string.goals_monthly_goal
		};

		abstract val stringResource: Int
	}
}
