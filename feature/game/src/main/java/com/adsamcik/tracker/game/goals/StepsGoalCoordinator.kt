package com.adsamcik.tracker.game.goals

import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.game.repository.StepsCalendarAuthority
import com.adsamcik.tracker.game.repository.stepsCalendarAuthority
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.stats.api.repository.StepsNumericDecisionRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/** App-process owner for qualified Steps goal decisions and their durable action queue. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class StepsGoalCoordinator @Inject constructor(
	private val database: AppDatabase,
	private val source: StepsNumericDecisionRepository,
	private val settingsRepository: GoalsSettingsRepository,
	private val decisionReconciler: StepsGoalDecisionReconciler,
	private val rewardProjector: StepsGoalRewardProjector,
	private val notificationDispatcher: StepsGoalNotificationDispatcher,
) {
	suspend fun run(): Unit = coroutineScope {
		launch { observeCurrentPeriods() }
		launch { drainActions() }
	}

	private suspend fun observeCurrentPeriods() {
		GoalTracker.calendarInvalidations
			.onStart { emit(Unit) }
			.map { currentAuthority() }
			.distinctUntilChanged()
			.flatMapLatest { authority ->
				combine(
					settingsRepository.data.distinctUntilChanged(),
					source.observeDecisionBatch(requests(authority)),
				) { settings, _ -> authority to settings }
			}
			.collect { (authority, _) ->
				reconcileUntilSettled(authority)
			}
	}

	private suspend fun reconcileUntilSettled(authority: StepsCalendarAuthority) {
		while (authority == currentAuthority()) {
			val settings = settingsRepository.data.first()
			when (
				decisionReconciler.reconcile(
					authority = authority,
					settings = settings,
					observedAtMs = Time.nowMillis,
				)
			) {
				is StepsGoalDecisionReconcileResult.Applied -> return
				is StepsGoalDecisionReconcileResult.RetryableFailure -> delay(RETRY_DELAY_MS)
			}
		}
	}

	private suspend fun drainActions() {
		while (true) {
			currentCoroutineContext().ensureActive()
			val effects = database.stepsGoalEffectDao().observeActionable().first { it.isNotEmpty() }
			var retryNeeded = false
			effects.forEach { effect ->
				when (val reward = rewardProjector.project(effect.effectIdentity, effect.effectRevision)) {
					is StepsGoalRewardProjectionResult.RetryableFailure -> retryNeeded = true
					is StepsGoalRewardProjectionResult.Settled -> {
						if (reward.points == StepsGoalRewardComponentResult.RETRYABLE_FAILURE ||
							reward.xp == StepsGoalRewardComponentResult.RETRYABLE_FAILURE
						) {
							retryNeeded = true
						} else if (reward.points != StepsGoalRewardComponentResult.SUPERSEDED &&
							reward.xp != StepsGoalRewardComponentResult.SUPERSEDED
						) {
							retryNeeded = dispatchNotification(effect) || retryNeeded
						}
					}
				}
			}
			if (retryNeeded) delay(RETRY_DELAY_MS)
		}
	}

	private suspend fun dispatchNotification(
		effect: StepsGoalEffectEntity,
	): Boolean {
		val settings: GoalsSettingsState = settingsRepository.data.first()
		val enabledForCurrentPeriod = settings.notificationsEnabled &&
			isCurrentPeriod(effect, currentAuthority())
		return when (
			notificationDispatcher.dispatch(
				effectIdentity = effect.effectIdentity,
				effectRevision = effect.effectRevision,
				notificationsEnabled = enabledForCurrentPeriod,
				claimedAtMs = Time.nowMillis,
			)
		) {
			StepsGoalNotificationDispatchResult.RETRYABLE_GENERATION_CHANGED,
			StepsGoalNotificationDispatchResult.RETRYABLE_STORAGE_UNAVAILABLE -> true
			else -> false
		}
	}

	private fun isCurrentPeriod(
		effect: StepsGoalEffectEntity,
		authority: StepsCalendarAuthority,
	): Boolean = when (effect.periodKind) {
		StepsGoalEffectEntity.PERIOD_DAY ->
			effect.periodStartEpochDay == authority.today.toEpochDay()
		StepsGoalEffectEntity.PERIOD_WEEK ->
			effect.periodStartEpochDay == authority.startOfWeek.toEpochDay()
		else -> false
	}

	private fun currentAuthority(): StepsCalendarAuthority = stepsCalendarAuthority(
		now = Time.now,
		locale = Locale.getDefault(),
	)

	private fun requests(authority: StepsCalendarAuthority): List<StepsNumericSummaryRequest> = listOf(
		StepsNumericSummaryRequest(
			authority.today.toEpochDay(),
			authority.today.toEpochDay(),
			authority.zoneId,
		),
		StepsNumericSummaryRequest(
			authority.startOfWeek.toEpochDay(),
			authority.today.toEpochDay(),
			authority.zoneId,
		),
	)

	private companion object {
		const val RETRY_DELAY_MS = 5_000L
	}
}
