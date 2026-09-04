package com.adsamcik.tracker.game.repository

import android.app.Application
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.data.Points
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.points.database.PointsAwardedDao
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.stats.api.scheduler.AchievementEvaluationScheduler
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class DefaultGameRepository @Inject constructor(
	private val application: Application,
	@ApplicationScope private val scope: CoroutineScope,
	private val trackerStateReader: TrackerStateReader,
	private val dispatchers: DispatchersProvider,
	private val database: AppDatabase,
	private val progressionRepository: PlayerProgressionRepository,
	private val metricDirtyTracker: MetricDirtyTracker,
	private val achievementScheduler: AchievementEvaluationScheduler,
	private val goalsSettingsRepository: GoalsSettingsRepository,
	private val stepsNumericSummaryRepository: StepsNumericSummaryRepository,
	private val trackingStartupGate: TrackingStartupGate,
) : GameRepository {
	private val pointsDao by lazy { PointsDatabase.database(application).pointsAwardedDao() }
	private val rewardEnsurer by lazy {
		MiniGameRewardEnsurer(
			pointsDao = pointsDao,
			dispatchers = dispatchers,
			trackingStartupGate = trackingStartupGate,
			awardProgression = progressionRepository::awardMiniGameXpForGeneration,
			markMiniGameMetricsDirty = {
				metricDirtyTracker.markDirty(setOf(MetricKeys.TABLE_MINI_GAME_SCORE))
			},
			scheduleAchievementEvaluation = achievementScheduler::scheduleEvaluation,
		)
	}

	init {
		GoalTracker.initialize(
			context = application,
			trackerStateReader = trackerStateReader,
			progressionRepository = progressionRepository,
			achievementScheduler = achievementScheduler,
			settingsRepository = goalsSettingsRepository,
		)
	}

	private fun startOfDay(now: Long): Long = Instant.ofEpochMilli(now)
		.atZone(ZoneId.systemDefault())
		.toLocalDate()
		.atStartOfDay(ZoneId.systemDefault())
		.toInstant()
		.toEpochMilli()

	override fun getPointsToday(): Flow<Int> = flow {
		emitAll(pointsDao.countBetweenFlow(startOfDay(Time.nowMillis), Time.nowMillis))
	}.map { it.toInt() }.flowOn(dispatchers.io)

	private val stepsSummaryState by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
		sourceQualifiedStepsSummaryFlow(
			repository = stepsNumericSummaryRepository,
			invalidations = stepsSummaryInvalidations(),
			dailyGoal = GoalTracker.goalDay,
			weeklyGoal = GoalTracker.goalWeek,
			weeklyDailyLimit = goalsSettingsRepository.data
				.map { settings -> settings.weeklyProgressDailyLimit }
				.distinctUntilChanged(),
			currentDateTime = { Time.now },
			currentLocale = { Locale.getDefault() },
		).stateIn(
			scope = scope,
			started = SharingStarted.WhileSubscribed(
				stopTimeoutMillis = 0L,
				replayExpirationMillis = 0L,
			),
			initialValue = null,
		)
	}

	override fun getStepsSummary(): StateFlow<StepsSummaryData?> = stepsSummaryState

	private fun stepsSummaryInvalidations(): Flow<Unit> = merge(
		GoalTracker.stepsDay.drop(1).map { Unit },
		GoalTracker.stepsWeek.drop(1).map { Unit },
		dailySummaryInvalidations(),
	)

	/**
	 * Selected-session deletion repairs this table in its authoritative transaction. A portable
	 * importer must likewise repair or touch each affected daily-summary row before live import
	 * refresh can be claimed; source-evidence-only import writes are intentionally not guessed here.
	 */
	private fun dailySummaryInvalidations(): Flow<Unit> = flow {
		val authority = stepsCalendarAuthority(
			now = Time.now,
			locale = Locale.getDefault(),
		)
		emitAll(
			database.dailySummaryDao().getBetweenFlow(
				authority.startOfWeek.toEpochDay(),
				authority.today.toEpochDay(),
			).map { Unit }.catch { failure ->
				if (failure is CancellationException) {
					throw failure
				}
				emit(Unit)
			},
		)
	}

	override fun getPlayerProfile(): Flow<PlayerProfileUi?> = database.playerProfileDao().observe()
		.map { entity -> entity?.let { PlayerProfileUi(it.level, it.totalXp, it.xpIntoCurrentLevel, it.xpForNextLevel) } }
		.flowOn(dispatchers.io)

	override suspend fun ensureMiniGameReward(reward: GameReward): GameRewardEnsureResult =
		rewardEnsurer.ensure(reward)

	override suspend fun creditMiniGameXp(gameId: String, xp: Int, earnedAtMs: Long) {
		ensureMiniGameReward(
			GameReward(
				rewardId = miniGameRewardId(gameId, earnedAtMs),
				gameId = gameId,
				points = xp,
				earnedAtMs = earnedAtMs,
			),
		)
	}
}

internal fun miniGameRewardId(gameId: String, earnedAtMs: Long): String =
	"minigame:$gameId:$earnedAtMs"

internal class MiniGameRewardEnsurer(
	private val pointsDao: PointsAwardedDao,
	private val dispatchers: DispatchersProvider,
	private val trackingStartupGate: TrackingStartupGate,
	private val awardProgression: suspend (
		points: Int,
		earnedAtMs: Long,
		expectedGeneration: Long,
	) -> Boolean,
	private val markMiniGameMetricsDirty: () -> Unit,
	private val scheduleAchievementEvaluation: () -> Unit,
) {
	private val mutex = Mutex()

	suspend fun ensure(reward: GameReward): GameRewardEnsureResult {
		if (reward.rewardId != miniGameRewardId(reward.gameId, reward.earnedAtMs)) {
			return GameRewardEnsureResult.Rejected(GameRewardRejectionReason.INVALID_REWARD)
		}
		val expectedGeneration = trackingStartupGate.currentGeneration
		try {
			if (trackingStartupGate.reconcile() !is TrackingStartupResult.Ready) {
				return persistenceUnavailable()
			}
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Throwable) {
			return persistenceUnavailable()
		}
		return mutex.withLock {
			try {
				val source = "minigame:${reward.gameId}"
				val alreadyAwarded = trackingStartupGate.withReadyGenerationOperation(
					expectedGeneration,
				) {
					withContext(dispatchers.io) {
						val exists = pointsDao.hasAwardAt(reward.earnedAtMs, source)
						if (!exists && reward.points > 0) {
							pointsDao.insert(
								PointsAwarded(
									time = reward.earnedAtMs,
									value = Points(reward.points.toDouble()),
									source = AwardSource(source),
								),
							)
						}
						exists
					}
				} ?: return@withLock persistenceUnavailable()

				// Retried deliberately: the progression ledger is independently
				// idempotent and this repairs a points-written/progression-missed split.
				if (!awardProgression(reward.points, reward.earnedAtMs, expectedGeneration)) {
					return@withLock persistenceUnavailable()
				}
				trackingStartupGate.withReadyGeneration(expectedGeneration) {
					markMiniGameMetricsDirty()
					scheduleAchievementEvaluation()
					if (alreadyAwarded) {
						GameRewardEnsureResult.AlreadyEnsured
					} else {
						GameRewardEnsureResult.Created
					}
				} ?: persistenceUnavailable()
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (_: Throwable) {
				persistenceUnavailable()
			}
		}
	}

	private fun persistenceUnavailable() = GameRewardEnsureResult.Rejected(
		GameRewardRejectionReason.PERSISTENCE_UNAVAILABLE,
	)
}
