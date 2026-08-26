package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.QuarantinedSignalEntity

/**
 * Lease-based ownership operations for pending signal recovery.
 *
 * This is intentionally separate from [PendingSignalDao] so existing writers
 * and lightweight test doubles do not accidentally acquire recovery ownership.
 * Callers must use a freshly generated [claimToken] for every claim attempt.
 */
@Dao
interface PendingSignalClaimDao {
	@Query(
		"""
		SELECT * FROM pending_signal
		WHERE claim_token IS NULL
			OR claim_expires_at IS NULL
			OR claim_expires_at <= :nowMs
		ORDER BY id ASC
		LIMIT :limit
		""",
	)
	suspend fun selectClaimable(nowMs: Long, limit: Int): List<PendingSignalEntity>

	/**
	 * Compare-and-set claim. The availability predicate is repeated here so two
	 * coordinators that selected the same candidate cannot overwrite each other.
	 */
	@Query(
		"""
		UPDATE pending_signal
		SET claim_token = :claimToken,
			claim_expires_at = :leaseExpiresAtMs,
			delivery_attempt_count = delivery_attempt_count + 1
		WHERE id IN (:ids)
			AND (
				claim_token IS NULL
				OR claim_expires_at IS NULL
				OR claim_expires_at <= :nowMs
			)
		""",
	)
	suspend fun claimIds(
		ids: List<Long>,
		claimToken: String,
		nowMs: Long,
		leaseExpiresAtMs: Long,
	): Int

	@Query(
		"""
		SELECT * FROM pending_signal
		WHERE claim_token = :claimToken
		ORDER BY id ASC
		LIMIT :limit
		""",
	)
	suspend fun getClaimedByToken(claimToken: String, limit: Int): List<PendingSignalEntity>

	/**
	 * Claims up to [limit] currently unowned or expired rows. The selection and
	 * compare-and-set update share a Room transaction; a losing coordinator gets
	 * an empty result rather than another coordinator's rows.
	 */
	@Transaction
	suspend fun claimOldestAvailable(
		claimToken: String,
		nowMs: Long,
		leaseExpiresAtMs: Long,
		limit: Int,
	): List<PendingSignalEntity> {
		if (limit <= 0) return emptyList()
		val candidates = selectClaimable(nowMs, limit)
		if (candidates.isEmpty()) return emptyList()
		claimIds(
			ids = candidates.map(PendingSignalEntity::id),
			claimToken = claimToken,
			nowMs = nowMs,
			leaseExpiresAtMs = leaseExpiresAtMs,
		)
		return getClaimedByToken(claimToken, limit)
	}

	/** Acknowledge only rows still owned by this recovery coordinator. */
	@Query("DELETE FROM pending_signal WHERE id IN (:ids) AND claim_token = :claimToken")
	suspend fun deleteClaimedByIds(ids: List<Long>, claimToken: String): Int

	/** Compare-and-delete a live pending row that has not been leased for recovery. */
	@Query("DELETE FROM pending_signal WHERE id = :id AND claim_token IS NULL")
	suspend fun deleteUnclaimedById(id: Long): Int

	/** Give up all still-owned rows immediately after a transient failure. */
	@Query(
		"""
		UPDATE pending_signal
		SET claim_token = NULL, claim_expires_at = NULL
		WHERE claim_token = :claimToken
		""",
	)
	suspend fun releaseClaim(claimToken: String): Int

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertQuarantine(signal: QuarantinedSignalEntity): Long

	@Query(
		"""
		SELECT EXISTS(
			SELECT 1 FROM quarantined_signal WHERE source_pending_id = :sourcePendingId
		)
		""",
	)
	suspend fun hasQuarantineForSource(sourcePendingId: Long): Boolean

	/**
	 * Atomically records a permanent failure and removes its still-owned pending
	 * row. The conditional delete deliberately happens first: it is the
	 * ownership compare-and-set. If a lease was lost or expired, no quarantine
	 * insert is attempted. Once that delete succeeds, the surrounding Room
	 * transaction holds the write lock until the insert commits, so no other
	 * coordinator can deliver the same pending row in between.
	 */
	@Transaction
	suspend fun quarantineClaimed(
		signal: QuarantinedSignalEntity,
		claimToken: String,
	): Boolean {
		if (deleteClaimedByIds(listOf(signal.sourcePendingId), claimToken) != 1) return false

		val inserted = insertQuarantine(signal)
		if (inserted != -1L || hasQuarantineForSource(signal.sourcePendingId)) return true

		// Returning false here would commit the delete without a durable terminal
		// record. Throwing rolls the whole Room transaction back instead.
		throw IllegalStateException(
			"Unable to record quarantine for pending signal ${signal.sourcePendingId}",
		)
	}

	/**
	 * Atomically records a permanent failure for a live, unclaimed row and removes the source WAL
	 * entry. This is the non-recovery counterpart of [quarantineClaimed].
	 */
	@Transaction
	suspend fun quarantineUnclaimed(signal: QuarantinedSignalEntity): Boolean {
		if (deleteUnclaimedById(signal.sourcePendingId) != 1) return false

		val inserted = insertQuarantine(signal)
		if (inserted != -1L || hasQuarantineForSource(signal.sourcePendingId)) return true

		throw IllegalStateException(
			"Unable to record quarantine for pending signal ${signal.sourcePendingId}",
		)
	}
}
