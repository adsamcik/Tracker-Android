package com.adsamcik.tracker.game.goals

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.game.GOALS_LOG_SOURCE
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import com.adsamcik.tracker.shared.base.misc.NonNullLiveData
import com.adsamcik.tracker.shared.base.misc.NonNullLiveMutableData
import com.adsamcik.tracker.shared.base.notification.Notifications
import com.adsamcik.tracker.shared.preferences.observer.PreferenceObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.reflect.KMutableProperty0


/**
 * Tracks goals
 */
internal object GoalTracker {
	val stepsDay: NonNullLiveData<Int> get() = mMutableLiveStepsDay
	val goalDay: NonNullLiveData<Int> get() = mGoalDay
	val stepsWeek: NonNullLiveData<Int> get() = mMutableLiveStepsWeek
	val goalWeek: NonNullLiveData<Int> get() = mGoalWeek

	private var mAppContext: Context? = null

	private val mMutableLiveStepsDay = NonNullLiveMutableData(0)
	private var mStepsDay: Int = 0
		set(value) {
			field = value
			mMutableLiveStepsDay.postValue(value)
		}

	private var mGoalDay = NonNullLiveMutableData(0)
	private var mGoalDayReached = false

	private val mMutableLiveStepsWeek = NonNullLiveMutableData(0)
	private var mStepsWeek: Int = 0
		set(value) {
			field = value
			mMutableLiveStepsWeek.postValue(value)
		}
	private var mGoalWeek = NonNullLiveMutableData(0)
	private var mGoalWeekReached = false

	private var mLastStepCount: Int = 0
	private var mLastSessionId: Long = -1

	/**
	 * Initializes goal tracker.
	 */
	@WorkerThread
	fun initialize(context: Context) {
		mAppContext = context.applicationContext
		initializeStepCounts(context)
		GlobalScope.launch(Dispatchers.Main) {
			initializeGoals(context)
			subscribeToLive()
		}
	}

	@MainThread
	private fun subscribeToLive() {
		stepsDay.observeForever {
			checkStepsGoal(
					it,
					goalDay.value,
					::mGoalDayReached,
					R.string.goals_day_goal_reached
			)
		}
		goalDay.observeForever {
			checkStepsGoal(
					stepsDay.value,
					it,
					::mGoalDayReached,
					R.string.goals_day_goal_reached
			)
		}

		stepsWeek.observeForever {
			checkStepsGoal(
					it,
					goalWeek.value,
					::mGoalWeekReached,
					R.string.goals_week_goal_reached
			)
		}

		goalWeek.observeForever {
			checkStepsGoal(
					stepsWeek.value,
					it,
					::mGoalWeekReached,
					R.string.goals_week_goal_reached
			)
		}
	}

	@WorkerThread
	private fun initializeStepCounts(context: Context) {
		val now = Time.now
		val lastWeek = now.minusWeeks(1L)
		val dayStartMillis = Time.todayMillis
		val lastWeekSessions = AppDatabase
				.database(context)
				.sessionDao()
				.getAllBetween(lastWeek.toEpochMillis(), now.toEpochMillis())

		val weekSum = lastWeekSessions.sumBy { it.steps }
		val todaySum = lastWeekSessions
				.filter { it.start >= dayStartMillis }
				.sumBy { it.steps }

		mMutableLiveStepsDay.postValue(todaySum)
		mMutableLiveStepsWeek.postValue(weekSum)
	}

	@MainThread
	private fun initializeGoals(context: Context) {
		PreferenceObserver.observe(
				context,
				R.string.settings_game_goals_week_steps_key,
				R.string.settings_game_goals_week_steps_default,
				{ value: Int ->
					if (mGoalWeek.value < value) {
						mGoalWeekReached = false
					}
					mGoalWeek.postValue(value)
				})

		PreferenceObserver.observe(
				context,
				R.string.settings_game_goals_day_steps_key,
				R.string.settings_game_goals_day_steps_default,
				{ value: Int ->
					if (mGoalDay.value < value) {
						mGoalDayReached = false
					}
					mGoalDay.postValue(value)
				})
	}

	private fun checkStepsGoal(
			steps: Int,
			goal: Int,
			reached: KMutableProperty0<Boolean>,
			@StringRes messageRes: Int
	) {
		if (reached.get() && steps >= goal) {
			reached.set(true)
			Logger.log(LogData(message = "Reached goal of $goal steps", source = GOALS_LOG_SOURCE))
			showNotification(messageRes)
		}
	}

	private fun showNotification(@StringRes messageRes: Int) {
		val context = requireNotNull(mAppContext)
		context.notificationManager.notify(
				Notifications.uniqueNotificationId(),
				NotificationCompat.Builder(
						context,
						context.getString(com.adsamcik.tracker.R.string.channel_goals_id)
				)
						.setContentTitle(context.getString(messageRes))
						.setSmallIcon(com.adsamcik.tracker.R.drawable.ic_signals)
						.build()
		)
	}

	/**
	 * Called when new session data is available.
	 */
	fun update(session: TrackerSession) {
		val diff = if (mLastSessionId != session.id) {
			mLastSessionId = session.id
			session.steps
		} else {
			session.steps - mLastStepCount
		}

		mLastStepCount = session.steps

		val stepsToday = mStepsDay + diff
		val stepsWeek = mStepsWeek + diff

		mStepsDay = stepsToday
		mStepsWeek = stepsWeek
	}
}
