package com.adsamcik.tracker.game.goals.data.abstraction

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.goals.data.GoalPersistence
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import com.afollestad.materialdialogs.utils.MDUtil.getStringArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.ZonedDateTime
import kotlin.coroutines.CoroutineContext

abstract class BaseGoal(protected val persistence: GoalPersistence) : Goal, CoroutineScope {
	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	abstract val goalPreferenceKeyRes: Int
	abstract val goalPreferenceDefaultRes: Int

	abstract val goalReachedKeyRes: Int
	private lateinit var goalReachedKey: String

	abstract val notificationMessageRes: Int
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
						"""Goal ${javaClass.name} with key $goalReachedKey has future time 
						   set as goal. Current time: ${nowRounded}, 
						   Goal time: ${value},
						   Now time ${Time.today.toEpochMillis()}"""
				)
			}
			if (isEnabled) {
				persistence.persist(goalReachedKey, value)
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
		goalReachedKey = context.getString(goalReachedKeyRes)
		persistence.load(goalReachedKey)?.let { lastReportTime = it }
		isEnabled = true
	}

	override suspend fun onDisable(context: Context) {
		onDisableInternal(context)
		isEnabled = false
	}

	override fun onSessionUpdated(session: TrackerSession, isNewSession: Boolean): Boolean {
		onSessionUpdatedInternal(session, isNewSession)
		if (!isReported && value >= target) {
			lastReportTime = getGoalTime(Time.now)
			return true
		}
		return false
	}

	protected abstract fun onSessionUpdatedInternal(session: TrackerSession, isNewSession: Boolean)

	override fun onNewDay(context: Context, day: ZonedDateTime) {
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
	protected abstract fun updateFromDatabase(context: Context)

	/**
	 * Rounds date time to goal time.
	 */
	protected abstract fun getGoalTime(day: ZonedDateTime): Int

	override fun buildNotification(context: Context): Notification {
		val encouragement = context.getStringArray(R.array.goals_encouragement).random()
		return NotificationCompat.Builder(
				context,
				context.getString(com.adsamcik.tracker.R.string.channel_goals_id)
		)
				.setContentTitle(context.getString(notificationMessageRes, encouragement))
				.setSmallIcon(R.drawable.ic_flag)
				.build()
	}
}
