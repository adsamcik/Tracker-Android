package com.adsamcik.tracker.game.goals

import android.content.Context
import android.content.Intent
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import com.adsamcik.tracker.game.GOALS_LOG_SOURCE
import com.adsamcik.tracker.game.goals.data.GoalListenable
import com.adsamcik.tracker.game.goals.data.PreferencesGoalPersistence
import com.adsamcik.tracker.game.goals.data.abstraction.Goal
import com.adsamcik.tracker.game.goals.data.implementation.DailyStepGoal
import com.adsamcik.tracker.game.goals.data.implementation.WeeklyStepGoal
import com.adsamcik.tracker.game.goals.receiver.GoalsSessionUpdateReceiver
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.base.notification.Notifications
import com.adsamcik.tracker.shared.utils.module.TrackerUpdateReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext


/**
 * Tracks goals
 */
internal object GoalTracker : CoroutineScope {
	// Temporary variables before generic UI is implemented
	val stepsDay: LiveData<Int> get() = goalList[0].value
	val goalDay: LiveData<Int> get() = goalList[0].target
	val stepsWeek: LiveData<Int> get() = goalList[1].value
	val goalWeek: LiveData<Int> get() = goalList[1].target

	private val goalList: MutableList<GoalListenable> = mutableListOf()

	private var mAppContext: Context? = null

	private var mLastSessionId: Long = -1

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	private fun requireContext() = requireNotNull(mAppContext)

	/**
	 * Initializes goal tracker.
	 */
	@AnyThread
	fun initialize(context: Context) {
		mAppContext = context.applicationContext

		val persistence = PreferencesGoalPersistence(context)
		listOf(
				DailyStepGoal(persistence),
				WeeklyStepGoal(persistence)
		)
				.map { GoalListenable(it) }
				.forEach {
					goalList.add(it)
				}

		launch(Dispatchers.Default) {
			goalList.forEach {
				it.onEnable(context)
			}
			launch(Dispatchers.Main) {
				registerSessionListener(context)
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

	private fun onGoalReached(goal: Goal) {
		Logger.log(
				LogData(
						message = "Reached goal of $goal steps at ${Time.now}",
						source = GOALS_LOG_SOURCE
				)
		)
		showNotification(goal)
		awardGoalPoints(goal)
	}

	private fun awardGoalPoints(goal: Goal) {
		val pointsDao = PointsDatabase.database(requireContext()).pointsAwardedDao()
		pointsDao.insert(
				PointsAwarded(
						Time.nowMillis,
						Points(goal.pointMultiplier * goal.target),
						AwardSource.GOAL
				)
		)
	}

	private fun showNotification(goal: Goal) {
		val context = requireContext()
		context.notificationManager.notify(
				Notifications.uniqueNotificationId(),
				goal.buildNotification(context)
		)
	}


	internal fun onNewDay() {
		val context = requireContext()
		val time = Time.now
		mLastSessionId = -1
		launch(Dispatchers.Default) {
			goalList.forEach { it.onNewDay(context, time) }
		}
		Logger.log(LogData(message = "New day reset at ${Time.now}", source = GOALS_LOG_SOURCE))
	}

	/**
	 * Called when new session data is available.
	 */
	internal fun update(session: TrackerSession) {
		val isNewSession = mLastSessionId != session.id
		mLastSessionId = session.id

		goalList.forEach {
			if (it.onSessionUpdated(session, isNewSession)) {
				onGoalReached(it.goal)
			}
		}
	}
}
