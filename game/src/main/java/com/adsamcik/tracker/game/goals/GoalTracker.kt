package com.adsamcik.tracker.game.goals

import android.content.Context
import android.content.Intent
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.game.GOALS_LOG_SOURCE
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.goals.receiver.GoalsSessionUpdateReceiver
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
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.observer.PreferenceObserver
import com.adsamcik.tracker.shared.utils.module.TrackerUpdateReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.temporal.IsoFields
import kotlin.coroutines.CoroutineContext


/**
 * Tracks goals
 */
internal object GoalTracker : CoroutineScope {
	val stepsDay: NonNullLiveData<Int> get() = mMutableLiveStepsDay
	val goalDay: NonNullLiveData<Int> get() = mGoalDay
	val goalDayReached: NonNullLiveData<Boolean> get() = mGoalDayReached
	val stepsWeek: NonNullLiveData<Int> get() = mMutableLiveStepsWeek
	val goalWeek: NonNullLiveData<Int> get() = mGoalWeek
	val goalWeekReached: NonNullLiveData<Boolean> get() = mGoalWeekReached

	private var mAppContext: Context? = null

	private val mMutableLiveStepsDay = NonNullLiveMutableData(0)
	private var mStepsDay: Int = 0
		set(value) {
			field = value
			mMutableLiveStepsDay.postValue(value)
		}

	private var mGoalDay = NonNullLiveMutableData(0)
	private val mGoalDayReached = NonNullLiveMutableData(false)

	private val mMutableLiveStepsWeek = NonNullLiveMutableData(0)
	private var mStepsWeek: Int = 0
		set(value) {
			field = value
			mMutableLiveStepsWeek.postValue(value)
		}
	private var mGoalWeek = NonNullLiveMutableData(0)
	private val mGoalWeekReached = NonNullLiveMutableData(false)

	private var mLastStepCount: Int = 0
	private var mLastSessionId: Long = -1

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	/**
	 * Initializes goal tracker.
	 */
	@AnyThread
	fun initialize(context: Context) {
		mAppContext = context.applicationContext
		launch(Dispatchers.Main) {
			initializeGoalReached(context)
			initializeGoals(context)
			subscribeToLive()
			registerSessionListener(context)
			launch(Dispatchers.Default) {
				initializeStepCounts(context)
			}
		}
	}

	@MainThread
	private fun registerSessionListener(context: Context) {
		context.sendBroadcast(
				Intent(TrackerUpdateReceiver.ACTION_REGISTER_COMPONENT).putExtra(
						TrackerUpdateReceiver.RECEIVER_LISTENER_REGISTRATION_CLASSNAME,
						GoalsSessionUpdateReceiver::class.java.name
				),
				TrackerSession.BROADCAST_PERMISSION
		)
		Logger.log(
				LogData(
						message = "Attempted goal session listener registration",
						source = GOALS_LOG_SOURCE
				)
		)
	}

	@MainThread
	private fun subscribeToLive() {
		stepsDay.observeForever {
			checkDayStepsGoal(
					it,
					goalDay.value
			)
		}
		goalDay.observeForever {
			checkDayStepsGoal(
					stepsDay.value,
					it
			)
		}

		stepsWeek.observeForever {
			checkWeekStepsGoal(
					it,
					goalWeek.value
			)
		}

		goalWeek.observeForever {
			checkWeekStepsGoal(
					stepsWeek.value,
					it,
			)
		}
	}

	@MainThread
	private fun initializeGoalReached(context: Context) {
		val preferences = Preferences.getPref(context)
		mGoalDayReached.postValue(
				preferences.getBooleanRes(
						R.string.goals_day_goal_reached_key,
						false
				)
		)
		mGoalWeekReached.postValue(
				preferences.getBooleanRes(
						R.string.goals_week_goal_reached_key,
						false
				)
		)
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
					mGoalWeek.postValue(value)
				})

		PreferenceObserver.observe(
				context,
				R.string.settings_game_goals_day_steps_key,
				R.string.settings_game_goals_day_steps_default,
				{ value: Int ->
					mGoalDay.postValue(value)
				})
	}

	private fun checkDayStepsGoal(steps: Int, goal: Int) = checkStepsGoal(
			steps,
			goal,
			mGoalDayReached,
			R.string.goals_day_goal_reached_key,
			R.string.goals_day_goal_reached_notification
	)

	private fun checkWeekStepsGoal(steps: Int, goal: Int) = checkStepsGoal(
			steps,
			goal,
			mGoalWeekReached,
			R.string.goals_week_goal_reached_key,
			R.string.goals_week_goal_reached_notification
	)

	private fun checkStepsGoal(
			steps: Int,
			goal: Int,
			liveData: NonNullLiveMutableData<Boolean>,
			@StringRes goalKeyRes: Int,
			@StringRes messageRes: Int
	) {
		if (liveData.value && steps >= goal) {
			Logger.log(
					LogData(
							message = "Reached goal of $goal steps at ${Time.now}",
							source = GOALS_LOG_SOURCE
					)
			)
			showNotification(messageRes)
			setGoalReached(goalKeyRes, liveData, true)
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

	private fun setGoalReached(
			@StringRes goalKeyRes: Int,
			liveData: NonNullLiveMutableData<Boolean>,
			value: Boolean
	) {
		liveData.postValue(value)
		persistGoalReached(goalKeyRes, value)
	}

	private fun persistGoalReached(@StringRes goalKeyRes: Int, value: Boolean) {
		val context = requireNotNull(mAppContext)
		Preferences.getPref(context).edit {
			setBoolean(goalKeyRes, value)
		}
	}

	internal fun onNewDay() {
		setGoalReached(R.string.goals_day_goal_reached_key, mGoalDayReached, false)
		val today = Time.today
		if (today.minusDays(1)[IsoFields.WEEK_OF_WEEK_BASED_YEAR] !=
				today[IsoFields.WEEK_OF_WEEK_BASED_YEAR]) {
			mGoalWeekReached.postValue(false)
			setGoalReached(R.string.goals_week_goal_reached_key, mGoalWeekReached, false)
			Logger.log(
					LogData(
							message = "Goal week reset at ${Time.now}",
							source = GOALS_LOG_SOURCE
					)
			)
		}

		launch(Dispatchers.Default) {
			initializeStepCounts(requireNotNull(mAppContext))
		}
		Logger.log(LogData(message = "Goal day reset at ${Time.now}", source = GOALS_LOG_SOURCE))
	}

	/**
	 * Called when new session data is available.
	 */
	internal fun update(session: TrackerSession) {
		val diff = if (mLastSessionId != session.id) {
			mLastSessionId = session.id
			session.steps
		} else {
			session.steps - mLastStepCount
		}

		if (diff > 0) {
			mLastStepCount = session.steps

			val stepsToday = mStepsDay + diff
			val stepsWeek = mStepsWeek + diff

			mStepsDay = stepsToday
			mStepsWeek = stepsWeek
		}
	}
}
