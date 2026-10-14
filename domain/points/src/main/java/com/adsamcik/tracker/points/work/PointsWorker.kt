package com.adsamcik.tracker.points.work

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.hilt.work.HiltWorker
import com.adsamcik.tracker.points.data.AwardSource
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.points.scoring.PointsScorer
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.work.getNonNegativeLongOrNull
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider

@HiltWorker
internal class PointsWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val appDatabaseProvider: Provider<AppDatabase>,
	private val pointsDatabaseProvider: Provider<PointsDatabase>,
	private val trackingStartupGate: TrackingStartupGate,
    private val trackingHistory: com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository,
) : CoroutineWorker(
	context,
	workerParams
) {
	override suspend fun doWork(): Result {
		val id = this.inputData.getNonNegativeLongOrNull(ARG_ID) ?: return Result.failure()
		val startupGeneration = trackingStartupGate.currentGeneration
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
		}
		return trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			val appDatabase = appDatabaseProvider.get()
			val pointsDatabase = pointsDatabaseProvider.get()
            val operationEpoch = inputData.getNonNegativeLongOrNull(ARG_EPOCH)
                ?: return@withReadyGenerationOperation Result.success()
            val state = appDatabase.sourceEvidenceStateDao().get()
            if (state == null || state.collectedDataEpoch != operationEpoch) {
                return@withReadyGenerationOperation Result.success()
            }
            val pointsDao = pointsDatabase.pointsAwardedDao()
            val read = trackingHistory.readSavedSessionReward(id)
            val exactKey = (read as? com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Ready)?.value?.effectKey
            val exact = exactKey?.let { pointsDao.getRevisionedEffect(AwardSource.SESSION.value, it) }
            val receipts = exact?.let { listOf(it) } ?: pointsDao.sessionEffects(id)
            if (receipts.size > 1) return@withReadyGenerationOperation Result.failure()
            val old = receipts.singleOrNull()
            val oldKey = old?.effectKey
            if (oldKey != null) {
                val parts = oldKey.split(':')
                val originalEpoch = parts.takeIf { it.size == 3 && it[0] == "recording" &&
                    it[2].toLongOrNull() == id }?.get(1)?.toLongOrNull()
                if (originalEpoch == null || !state.acceptsRetainedEpoch(originalEpoch)) {
                    return@withReadyGenerationOperation Result.success()
                }
            }
            val key: String
            val revision: Long
            val earnedAt: Long
            val valueMicros: Long
            when (read) {
                is com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Ready -> {
                    val saved = read.value
                    if (!state.acceptsRetainedEpoch(saved.dataEpoch)) return@withReadyGenerationOperation Result.success()
                    key = oldKey ?: saved.effectKey
                    revision = saved.revision
                    earnedAt = saved.earnedAtMs
                    val points = PointsScorer().calculateFallbackPoints(saved.distanceMeters ?: 0.0,
                        saved.durationMs / 60_000.0)
                    val micros = points * 1_000_000.0
                    if (!micros.isFinite() || micros >= Long.MAX_VALUE.toDouble()) return@withReadyGenerationOperation Result.failure()
                    valueMicros = micros.toLong().coerceAtLeast(0L)
                }
                is com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Removed -> {
                    if (old == null) return@withReadyGenerationOperation Result.success()
                    key = requireNotNull(oldKey)
                    revision = read.revision
                    earnedAt = old.time
                    valueMicros = 0L
                }
                com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Legacy ->
                    return@withReadyGenerationOperation Result.success()
                com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Unavailable ->
                    return@withReadyGenerationOperation Result.retry()
            }
            pointsDatabase.withTransaction {
                pointsDao.applySessionEffect(key, revision, earnedAt, valueMicros)
            }
			Result.success()
		} ?: Result.success()
	}

	companion object {
		private const val ARG_ID = TrackerSession.RECEIVER_SESSION_ID
        const val ARG_EPOCH = "data_epoch"
	}
}
