package com.adsamcik.tracker.game.progression

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PlayerProfileEntity
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.database.data.XpLedgerEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

internal enum class SessionXpAwardResult {
	COMPLETED,
	RETRY_NEEDED,
}

internal enum class StepsGoalXpApplyResult {
	APPLIED,
	SETTLED_FROM_EXISTING_RECEIPT,
	ALREADY_SETTLED,
	SUPERSEDED,
}

/**
 * Owns the player XP/level economy that gates mini-game unlocks.
 *
 * XP is appended to the immutable `xp_ledger` (idempotent via the unique
 * `(source, source_id)` index) and the cached `player_profile` row is then
 * recomputed from the authoritative ledger total via [XpCurve]. Both writes run
 * inside a single Room transaction so a level can never drift from its ledger.
 *
 * This restores the leveling pipeline that was removed alongside the challenge
 * system: without it nothing ever wrote `player_profile`, the level stayed at 1,
 * and every mini-game (unlock levels 3/6/9) was permanently locked.
 */
@Singleton
class PlayerProgressionRepository @Inject constructor(
	private val database: AppDatabase,
	private val dispatchers: DispatchersProvider,
	private val metricDirtyTracker: MetricDirtyTracker,
	private val trackingStartupGate: TrackingStartupGate,
    private val trackingHistory: com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository,
) {

	/**
	 * Award passive XP for a completed tracking session. Capped per-session and
	 * by the remaining daily budget so leveling can't be farmed by spamming
	 * short sessions. Idempotent on the session id.
	 *
	 * The session's distance and duration come from the persisted `session_segment` — the
	 * [DomainEvent.SessionEnded] payload itself does not carry the authoritative aggregates,
	 * mirroring how points scoring loads the session by id. The lookup and award share one Room
	 * transaction inside the deletion generation fence. The legacy segment Steps value is
	 * intentionally excluded until a source-qualified award decision exists. Delayed processing uses
	 * the persisted segment end for earned time and its civil-day cap, using the current device zone.
	 * This does not establish historical stored-zone authority for source-qualified Steps goals.
	 */
	internal suspend fun awardSessionXp(
		event: DomainEvent.SessionEnded,
		expectedGeneration: Long,
	): SessionXpAwardResult {
		val sessionId = event.sessionId
		if (sessionId <= 0L) return SessionXpAwardResult.COMPLETED

		val inserted = trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
			val acceptedInsert = withContext(dispatchers.io) {
				database.withTransaction { insertSessionXp(sessionId) }
			}
			if (acceptedInsert) metricDirtyTracker.markDirty(LEVELING_DIRTY_TABLES)
			acceptedInsert
		}
		return if (inserted == null) {
			SessionXpAwardResult.RETRY_NEEDED
		} else {
			SessionXpAwardResult.COMPLETED
		}
	}

    /** Current source-qualified data replaces the existing recording receipt, including zero. */
    private suspend fun insertSessionXp(sessionId: Long): Boolean {
        val dao = database.xpLedgerDao()
        val old = dao.sessionEffect(sessionId)
        val read = trackingHistory.readSavedSessionReward(sessionId)
        val epoch: Long
        val revision: Long
        val awardedAt: Long
        val amount: Int
        when (read) {
            is com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Ready -> {
                epoch = read.value.dataEpoch
                revision = read.value.revision
                awardedAt = read.value.earnedAtMs
                amount = XpCalculator.sessionXp((read.value.distanceMeters ?:
                    0.0).coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat(),
                    read.value.durationMs)
            }
            is com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Removed -> {
                if (old == null) return false
                epoch = read.dataEpoch
                revision = read.revision
                awardedAt = old.earnedAt
                amount = 0
            }
            com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Legacy -> return false
            com.adsamcik.tracker.stats.api.repository.SavedSessionRewardRead.Unavailable ->
                throw IllegalStateException("Saved recording reward is unavailable")
        }
        return commitSessionXp(sessionId, old, epoch, revision, awardedAt, amount)
    }

    private suspend fun commitSessionXp(
        sessionId: Long, old: XpLedgerEntity?, epoch: Long, revision: Long, awardedAt: Long, amount: Int,
    ): Boolean {
        val dao = database.xpLedgerDao()
        if (old?.sourceRevision?.let { it >= revision } == true) return false
        val key = old?.sourceKey ?: "recording:$epoch:$sessionId"
        if (!hasRetainedSessionReceipt(key, sessionId)) return false
        val awardDay = dayBounds(awardedAt)
        val otherXp = (dao.getXpBetween(awardDay.startInclusive, awardDay.endExclusive) - (old?.amount ?:
            0)).coerceAtLeast(0L)
        val capped = amount.toLong().coerceAtMost((XpCalculator.DAILY_CAP - otherXp).coerceAtLeast(0L)).toInt()
        val changed = if (old == null) {
            dao.insertOrIgnore(XpLedgerEntity(amount = capped, source = XpSource.SESSION.name,
                sourceId = sessionId, sourceKey = key, sourceRevision = revision, earnedAt = awardedAt)) != -1L
        } else dao.replaceSessionEffect(sessionId, key, revision, capped) == 1
        if (changed) recomputePlayerProfile()
        return changed
    }

    private suspend fun hasRetainedSessionReceipt(key: String, sessionId: Long): Boolean {
        val keyParts = key.split(':')
        val originalEpoch = keyParts.takeIf { it.size == 3 && it[0] == "recording" &&
            it[2].toLongOrNull() == sessionId }?.get(1)?.toLongOrNull() ?: return false
        val state = database.sourceEvidenceStateDao().get() ?: return false
        return state.acceptsRetainedEpoch(originalEpoch)
    }

    /** Bounded crash catchup of existing receipts; edited labels and re-imports never earn again. */
    internal suspend fun reconcileSessionRewards(expectedGeneration: Long) {
        withContext(dispatchers.io) {
                var after = 0L
                var changed = false
                while (true) {
                    val count = trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
                    val rows = database.xpLedgerDao().sessionEffectPage(after, SESSION_REWARD_PAGE_SIZE)
                    changed = reconcileSessionRewardPage(rows) || changed
                    if (rows.isNotEmpty()) after = rows.last().id
                    rows.size
                    } ?: break
                    if (count < SESSION_REWARD_PAGE_SIZE) break
                }
                if (changed) metricDirtyTracker.markDirty(LEVELING_DIRTY_TABLES)
        }
    }
    private suspend fun reconcileSessionRewardPage(rows: List<XpLedgerEntity>): Boolean {
        var changed = false
        for (row in rows) {
            try {
                database.withTransaction { changed =
                    insertSessionXp(requireNotNull(row.sourceId)) || changed }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep this receipt and retry on the next saved-data change or app startup.
            }
        }
        return changed
    }

	/**
	 * Repairs the XP half of one mini-game reward while the caller holds the accepted startup-gate
	 * operation. The caller owns deletion serialization so this method must not acquire a nested
	 * [TrackingStartupGate.withReadyGenerationOperation] lease.
	 *
	 * @return `true` only when this call inserted a new XP ledger row.
	 */
	internal suspend fun awardMiniGameXpInsideAcceptedGeneration(
		points: Int,
		earnedAtMs: Long,
	): Boolean {
		val amount = XpCalculator.miniGameXp(points)
		if (amount <= 0) return false

		val inserted = withContext(dispatchers.io) {
			database.withTransaction {
				val ledgerId = database.xpLedgerDao().insertOrIgnore(
					XpLedgerEntity(
						amount = amount,
						source = XpSource.MINI_GAME.name,
						sourceId = earnedAtMs,
						earnedAt = earnedAtMs,
					),
				)
				if (ledgerId == -1L) return@withTransaction false
				recomputePlayerProfile()
				true
			}
		}
		if (inserted) metricDirtyTracker.markDirty(LEVELING_DIRTY_TABLES)
		return inserted
	}

	/**
	 * Projects one exact Steps goal effect while the caller holds an accepted startup generation.
	 *
	 * The revisioned XP receipt, cached profile repair, and exact effect acknowledgement share one
	 * Room transaction. A correction writes a zero-valued receipt instead of deleting identity, so a
	 * stale projector cannot restore XP. Zero receipts are excluded from reward-history queries.
	 */
	internal suspend fun applyStepsGoalXpInsideAcceptedGeneration(
		effectIdentity: String,
		effectRevision: Long,
	): StepsGoalXpApplyResult {
		require(effectIdentity.isNotBlank())
		require(effectRevision > 0L)
		var ledgerChanged = false
		val result = withContext(dispatchers.io) {
			database.withTransaction {
				val effect = database.stepsGoalEffectDao().get(effectIdentity)
					?: return@withTransaction StepsGoalXpApplyResult.SUPERSEDED
				if (effect.effectRevision != effectRevision) {
					return@withTransaction StepsGoalXpApplyResult.SUPERSEDED
				}
				if (effect.xpAppliedRevision >= effectRevision) {
					return@withTransaction StepsGoalXpApplyResult.ALREADY_SETTLED
				}

				val currentReceipt = database.xpLedgerDao().getRevisionedEffect(
					source = XpSource.GOAL.name,
					sourceKey = effectIdentity,
				)
				val zeroReceiptTime = currentReceipt?.exactZeroGoalTime(effect)
				// A diagnostic observation-time refresh is not a new reward revision. Keep the
				// existing zero receipt's time when completing an interrupted acknowledgement.
				val earnedAt = effect.firstCompletedAtMs ?: zeroReceiptTime ?: effect.updatedAtMs
				val receiptAlreadyExact = currentReceipt.matchesGoalReceipt(effectRevision, effect.desiredXp, earnedAt)
				if (!receiptAlreadyExact) {
					val accepted = database.xpLedgerDao().applyStepsGoalEffect(
						effectKey = effectIdentity,
						effectRevision = effectRevision,
						amount = effect.desiredXp,
						earnedAt = earnedAt,
					)
					check(accepted) { "Current Steps goal XP conflicts with its saved receipt" }
					recomputePlayerProfile()
					ledgerChanged = true
				}
				check(
					database.stepsGoalEffectDao().markXpApplied(effectIdentity, effectRevision) == 1,
				) { "Current Steps goal XP effect could not be acknowledged" }
				if (receiptAlreadyExact) {
					StepsGoalXpApplyResult.SETTLED_FROM_EXISTING_RECEIPT
				} else {
					StepsGoalXpApplyResult.APPLIED
				}
			}
		}
		if (ledgerChanged) metricDirtyTracker.markDirty(LEVELING_DIRTY_TABLES)
		return result
	}

	/**
	 * Recompute the cached profile from the authoritative ledger sum. Must run
	 * inside the same transaction as the ledger insert so the level reflects the
	 * just-added XP. Cheap: the ledger holds at most one row per session / run.
	 */
	private suspend fun recomputePlayerProfile() {
		val profileDao = database.playerProfileDao()
		profileDao.ensureExists()
		val current = profileDao.get() ?: PlayerProfileEntity()
		val totalXp = database.xpLedgerDao().getTotalXp()
		val snapshot = XpCurve.computeProfile(totalXp)
		profileDao.update(
			current.copy(
				totalXp = totalXp,
				level = snapshot.level,
				xpIntoCurrentLevel = snapshot.xpIntoCurrentLevel,
				xpForNextLevel = snapshot.xpForNextLevel,
			),
		)
	}

	private fun dayBounds(timeMillis: Long): DayBounds {
		val zoneId = ZoneId.systemDefault()
		val localDate = Instant.ofEpochMilli(timeMillis)
			.atZone(zoneId)
			.toLocalDate()
		return DayBounds(
			startInclusive = localDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
			endExclusive = localDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
		)
	}

	private data class DayBounds(
		val startInclusive: Long,
		val endExclusive: Long,
	)

	private companion object {
		/** Tables whose achievement rules depend on XP/level changes. */
		private val LEVELING_DIRTY_TABLES = setOf(
			MetricKeys.TABLE_XP_LEDGER,
			MetricKeys.TABLE_PLAYER_PROFILE,
		)
	}
}

private const val SESSION_REWARD_PAGE_SIZE = 100

private fun XpLedgerEntity.exactZeroGoalTime(effect: StepsGoalEffectEntity): Long? {
	if (effect.firstCompletedAtMs != null || effect.desiredXp != 0 || source != XpSource.GOAL.name) {
		return null
	}
	if (sourceKey != effect.effectIdentity || sourceRevision != effect.effectRevision || amount != 0 || sourceId != null) {
		return null
	}
	return earnedAt.takeIf { it >= 0L }
}

private fun XpLedgerEntity?.matchesGoalReceipt(revision: Long, amount: Int, earnedAt: Long): Boolean {
	if (this == null) {
		return false
	}
	return sourceRevision == revision && sourceId == null && this.amount == amount && this.earnedAt == earnedAt
}
