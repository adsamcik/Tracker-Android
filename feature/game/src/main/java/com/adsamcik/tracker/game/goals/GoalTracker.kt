package com.adsamcik.tracker.game.goals

import android.content.Context
import androidx.annotation.AnyThread
import com.adsamcik.tracker.game.goals.data.GoalListenable
import com.adsamcik.tracker.game.goals.data.GoalsSettingsGoalPersistence
import com.adsamcik.tracker.game.goals.data.abstraction.Goal
import com.adsamcik.tracker.game.goals.data.implementation.DailyStepGoal
import com.adsamcik.tracker.game.goals.data.implementation.WeeklyStepGoal
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsDefaults
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.base.notification.Notifications
import com.adsamcik.tracker.stats.api.scheduler.AchievementEvaluationScheduler
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

internal object GoalTracker : CoroutineScope {
	private val dispatchers = DefaultDispatchersProvider
	private val goalList: MutableList<GoalListenable> = mutableListOf()

	val stepsDay: StateFlow<Int> get() = goalList[DAILY_INDEX].value
	val goalDay: StateFlow<Int> get() = goalList[DAILY_INDEX].target
	val stepsWeek: StateFlow<Int> get() = goalList[WEEKLY_INDEX].value
	val goalWeek: StateFlow<Int> get() = goalList[WEEKLY_INDEX].target

	private var mAppContext: Context? = null
	private val initialized = AtomicBoolean(false)
	private var progressionRepository: PlayerProgressionRepository? = null
	private var achievementScheduler: AchievementEvaluationScheduler? = null
	private var latestSettings: GoalsSettingsState? = null
	@Volatile
	private var notificationsEnabled = GoalsSettingsDefaults.NOTIFICATIONS_ENABLED

	private var mLastSessionId: Long = -1
	private val job = SupervisorJob()
	private val mutex = Mutex()

	override val coroutineContext: CoroutineContext
		get() = dispatchers.default + job

	private fun requireContext() = requireNotNull(mAppContext)

	/**
	 * Integration entry point for callers that already own the repository instance.
	 * The first successful initializer owns the process-wide collectors.
	 */
	@AnyThread
	fun initialize(
		context: Context,
		trackerStateReader: TrackerStateReader,
		progressionRepository: PlayerProgressionRepository,
		achievementScheduler: AchievementEvaluationScheduler,
		settingsRepository: GoalsSettingsRepository,
	) {
		if (!initialized.compareAndSet(false, true)) return

		synchronized(this) {
			mAppContext = context.applicationContext
			this.progressionRepository = progressionRepository
			this.achievementScheduler = achievementScheduler
			val persistence = GoalsSettingsGoalPersistence(settingsRepository)
			goalList += GoalListenable(
				DailyStepGoal(
					persistence = persistence,
					initialTarget = GoalsSettingsDefaults.DAILY_STEP_GOAL,
				),
			)
			goalList += GoalListenable(
				WeeklyStepGoal(
					persistence = persistence,
					initialTarget = GoalsSettingsDefaults.WEEKLY_STEP_GOAL,
					initialDailyLimit = GoalsSettingsDefaults.WEEKLY_DAILY_LIMIT,
				),
			)
		}

		launch {
			goalList.forEach { it.onEnable(requireContext()) }
			applySettings(settingsRepository.data.first())

			settingsRepository.data
				.distinctUntilChanged()
				.onEach { settings ->
					if (settings != latestSettings) applySettings(settings)
				}
				.launchIn(this)

			trackerStateReader.sessionFlow
				.filterNotNull()
				.onEach(::update)
				.launchIn(this)

		}
	}

	private suspend fun applySettings(settings: GoalsSettingsState) {
		notificationsEnabled = settings.notificationsEnabled
		val previousSettings = latestSettings
		mutex.withLock {
			val dailyGoal = goalList[DAILY_INDEX].goal as DailyStepGoal
			val weeklyGoal = goalList[WEEKLY_INDEX].goal as WeeklyStepGoal

			if (previousSettings == null) {
				dailyGoal.replaceTarget(settings.dailyStepGoal)
			} else if (previousSettings.dailyStepGoal != settings.dailyStepGoal) {
				goalList[DAILY_INDEX].onTargetPresentationUpdated(settings.dailyStepGoal)
			}

			val weeklyConfigurationChanged = previousSettings == null ||
				previousSettings.weeklyStepGoal != settings.weeklyStepGoal ||
				previousSettings.weeklyProgressDailyLimit != settings.weeklyProgressDailyLimit
			if (weeklyConfigurationChanged) {
				weeklyGoal.updateConfiguration(
					target = settings.weeklyStepGoal,
					dailyLimit = settings.weeklyProgressDailyLimit,
				)
			}
			latestSettings = settings
		}
	}

	private suspend fun onGoalReached(goal: Goal) {
		dispatchGoalCompletion(
			notificationsEnabled = notificationsEnabled,
			notify = { showNotification(goal) },
			awardPoints = { awardGoalPoints(goal) },
			awardProgression = {
				if (goal is DailyStepGoal) {
					progressionRepository?.awardGoalXp(Time.nowMillis)
					achievementScheduler?.scheduleEvaluation()
				}
			},
		)
	}

	private suspend fun awardGoalPoints(goal: Goal) {
		PointsDatabase.database(requireContext()).pointsAwardedDao().insert(
			PointsAwarded(
				Time.nowMillis,
				Points(goal.pointMultiplier * goal.target),
				AwardSource.GOAL,
			),
		)
	}

	private fun showNotification(goal: Goal) {
		val context = requireContext()
		context.notificationManager.notify(
			Notifications.uniqueNotificationId(),
			goal.buildNotification(context),
		)
	}

	internal suspend fun onNewDay() {
		val context = requireContext()
		mutex.withLock {
			mLastSessionId = -1
			goalList.forEach { it.onNewDay(context, Time.now) }
		}
	}

	internal suspend fun update(session: TrackerSessionSnapshot) {
		mutex.withLock {
			val isNewSession = mLastSessionId != session.id
			mLastSessionId = session.id
			goalList.forEach {
				it.onSessionPresentationUpdated(session, isNewSession)
			}
		}
	}

	internal suspend fun updateUnqualifiedCumulativeStepsPresentation(totalSteps: Int) {
		if (mAppContext == null || goalList.size < GOAL_COUNT) return
		val dailyTotal = totalSteps.coerceAtLeast(0)
		mutex.withLock {
			goalList[DAILY_INDEX].onCumulativeStepsPresentationUpdated(dailyTotal)
			goalList[WEEKLY_INDEX].onCumulativeStepsPresentationUpdated(dailyTotal)
		}
	}

	/*
	 * Raw TrackerSessionSnapshot, Trip, and DailySummaryUpdated values intentionally stop at the
	 * presentation methods above. Re-enable completion only through a new source-qualified decision
	 * entry point that also defines correction/deletion race semantics; never reconnect these paths.
	 */

	private const val DAILY_INDEX = 0
	private const val WEEKLY_INDEX = 1
	private const val GOAL_COUNT = 2
}

internal suspend fun dispatchGoalCompletion(
	notificationsEnabled: Boolean,
	notify: () -> Unit,
	awardPoints: suspend () -> Unit,
	awardProgression: suspend () -> Unit,
) {
	awardPoints()
	awardProgression()
	if (notificationsEnabled) notify()
}
