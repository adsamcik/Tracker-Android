package com.adsamcik.tracker.game.goals

import android.content.Context
import androidx.annotation.AnyThread
import com.adsamcik.tracker.game.GOALS_LOG_SOURCE
import com.adsamcik.tracker.game.goals.data.GoalListenable
import com.adsamcik.tracker.game.goals.data.PreferencesGoalPersistence
import com.adsamcik.tracker.game.goals.data.abstraction.Goal
import com.adsamcik.tracker.game.goals.data.implementation.DailyStepGoal
import com.adsamcik.tracker.game.goals.data.implementation.WeeklyStepGoal
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.base.notification.Notifications
import com.adsamcik.tracker.shared.utils.module.TrackerSessionChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.temporal.ChronoField
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.coroutines.CoroutineContext


/**
 * Tracks goals and exposes reactive Flow-based state.
 */
internal object GoalTracker : CoroutineScope {
	private val dispatchers = DefaultDispatchersProvider
	// Reactive step and goal state (daily and weekly)
	val stepsDay: StateFlow<Int> get() = goalList[0].value
	val goalDay: StateFlow<Int> get() = goalList[0].target
	val stepsWeek: StateFlow<Int> get() = goalList[1].value
	val goalWeek: StateFlow<Int> get() = goalList[1].target

	private val goalList: MutableList<GoalListenable> = mutableListOf()

	private var mAppContext: Context? = null
	@Volatile
	private var initialized = false

	private var mLastSessionId: Long = -1

	private val job = SupervisorJob()
	private val mutex = Mutex()

	override val coroutineContext: CoroutineContext
		get() = dispatchers.default + job

	private fun requireContext() = requireNotNull(mAppContext)

	/**
	 * Initializes goal tracker.
	 * @param sessionChannel shared channel for per-cycle session updates (replaces broadcast registration)
	 */
	@AnyThread
	fun initialize(context: Context, sessionChannel: TrackerSessionChannel) {
		if (initialized) return
		var startObserver = false

		synchronized(this) {
			if (initialized) return@synchronized

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
			initialized = true
			startObserver = true
		}
		if (!startObserver) return

		launch(dispatchers.default) {
			goalList.forEach {
				it.onEnable(context)
			}

			// Observe per-cycle session updates via shared channel
			sessionChannel.sessions
				.onEach { session -> update(session) }
				.launchIn(this)

			Logger.log(
				LogData(
					message = "Goal session listener registered via TrackerSessionChannel",
					source = GOALS_LOG_SOURCE,
				),
			)
		}
	}

	private suspend fun onGoalReached(goal: Goal) {
		Logger.log(
				LogData(
						message = "Reached goal of $goal steps at ${Time.now}",
						source = GOALS_LOG_SOURCE
				)
		)
		showNotification(goal)
		awardGoalPoints(goal)
	}

	private suspend fun awardGoalPoints(goal: Goal) {
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


	internal suspend fun onNewDay() {
		val context = requireContext()
		val time = Time.now
		mutex.withLock {
			mLastSessionId = -1
			goalList.forEach { it.onNewDay(context, time) }
		}
		Logger.log(LogData(message = "New day reset at ${Time.now}", source = GOALS_LOG_SOURCE))
	}

	/**
	 * Called when new session data is available.
	 */
	internal suspend fun update(session: TrackerSession) {
		mutex.withLock {
			val isNewSession = mLastSessionId != session.id
			mLastSessionId = session.id

			goalList.forEach {
				if (it.onSessionUpdated(session, isNewSession)) {
					onGoalReached(it.goal)
				}
			}
		}
	}

	internal suspend fun updateCumulativeSteps(totalSteps: Int) {
		if (mAppContext == null || goalList.size < 2) return
		val context = requireContext()
		val dailyTotal = totalSteps.coerceAtLeast(0)
		val weeklyTotal = withContext(dispatchers.io) {
			loadCurrentWeekStepsWithTodayOverride(context, dailyTotal)
		}
		mutex.withLock {
			mLastSessionId = -1L

			if (goalList[0].onCumulativeStepsUpdated(dailyTotal)) {
				onGoalReached(goalList[0].goal)
			}
			if (goalList[1].onCumulativeStepsUpdated(weeklyTotal)) {
				onGoalReached(goalList[1].goal)
			}
		}
	}

	private suspend fun loadCurrentWeekStepsWithTodayOverride(context: Context, todayTotal: Int): Int {
		val tripDao = AppDatabase.database(context).tripDao()
		val todayFromDb = tripDao
			.getBetween(Time.today.toEpochMillis(), Time.tomorrow.toEpochMillis())
			.sumOf { it.steps ?: 0 }
		val now = Time.now
		val weekStart = now
			.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1L)
			.with(ChronoField.NANO_OF_DAY, 0L)
		val weekEnd = weekStart.plusWeeks(1L)
		val weekTotal = tripDao
			.getBetween(weekStart.toEpochMillis(), weekEnd.toEpochMillis())
			.sumOf { it.steps ?: 0 }

		return (weekTotal - todayFromDb + todayTotal).coerceAtLeast(0)
	}
}
