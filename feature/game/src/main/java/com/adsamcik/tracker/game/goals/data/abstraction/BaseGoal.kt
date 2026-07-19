package com.adsamcik.tracker.game.goals.data.abstraction

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import kotlin.coroutines.CoroutineContext

abstract class BaseGoal(
	protected val persistence: GoalPersistence,
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : Goal, CoroutineScope {
	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = dispatchers.main + job

	abstract val goalReachedPreferenceKey: String
	private lateinit var persistedGoalReachedKey: String

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
		set(value) {
			field = value
			val nowRounded = getGoalTime(Time.now)
			isReported = nowRounded == value
			if (getGoalTime(Time.now) < nowRounded) {
				Reporter.report(
						"""Goal ${javaClass.name} with key $persistedGoalReachedKey has future time 
						   set as goal. Current time: ${nowRounded}, 
						   Goal time: ${value},
						   Now time ${Time.today.toEpochMillis()}"""
				)
			}
			if (isEnabled) {
				launch { persistence.persist(persistedGoalReachedKey, value) }
			}
		}

	/**
	 * Caches lastReportTime
	 */
	private var isReported: Boolean = false

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
		persistedGoalReachedKey = goalReachedPreferenceKey
		persistence.load(persistedGoalReachedKey)?.let { lastReportTime = it }
		isEnabled = true
	}

	override suspend fun onDisable(context: Context) {
		onDisableInternal(context)
		isEnabled = false
	}

	override fun onSessionUpdated(session: TrackerSession, isNewSession: Boolean): Boolean {
		onSessionUpdatedInternal(session, isNewSession)
		return evaluateGoalReached()
	}

	protected abstract fun onSessionUpdatedInternal(session: TrackerSession, isNewSession: Boolean)

	protected fun evaluateGoalReached(): Boolean {
		if (!isReported && value >= target) {
			lastReportTime = getGoalTime(Time.now)
			return true
		}
		return false
	}

	override suspend fun onNewDay(context: Context, day: ZonedDateTime) {
		val time = getGoalTime(day)
		// This will trigger recount once a day if the goal is not reached, but
		// because the cost should not be very high, the extra code complexity does not seem
		// to be worth it.
		if (time != lastReportTime) {
			isReported = false
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
