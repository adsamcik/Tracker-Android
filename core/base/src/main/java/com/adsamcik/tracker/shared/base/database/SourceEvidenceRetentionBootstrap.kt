package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState

sealed interface SourceEvidenceRetentionBootstrapResult {
	data class Ready(val state: SourceEvidenceState) :
		SourceEvidenceRetentionBootstrapResult

	data object LifecycleChanged : SourceEvidenceRetentionBootstrapResult

	data object ConflictingEvidence : SourceEvidenceRetentionBootstrapResult

	data object InvalidState : SourceEvidenceRetentionBootstrapResult
}

/**
 * Initializes the singleton evidence guard only for an exact, still-current lifecycle snapshot.
 *
 * SourcePolicy rows and immutable destination ownership are authority inputs, not collected
 * evidence. Every other populated v28 table is conservatively treated as evidence that a missing
 * singleton cannot safely describe.
 */
suspend fun AppDatabase.bootstrapSourceEvidenceForRetention(
	expectedCollectedDataEpoch: Long,
	expectedRetainedFromMs: Long?,
	updatedAtMs: Long,
	isLifecycleSnapshotCurrent: suspend () -> Boolean,
): SourceEvidenceRetentionBootstrapResult {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedRetainedFromMs == null || expectedRetainedFromMs >= 0L)
	require(updatedAtMs >= 0L)
	return try {
		withTransaction {
			if (!isLifecycleSnapshotCurrent()) throw LifecycleChangedDuringBootstrap
			val dao = sourceEvidenceStateDao()
			val current = dao.get()
			if (current != null) {
				return@withTransaction current.classifyForRetentionBootstrap(
					expectedCollectedDataEpoch,
					expectedRetainedFromMs,
				)
			}
			if (openHelper.writableDatabase.hasConflictingRetentionEvidence()) {
				return@withTransaction SourceEvidenceRetentionBootstrapResult.ConflictingEvidence
			}
			if (!isLifecycleSnapshotCurrent()) throw LifecycleChangedDuringBootstrap
			dao.ensure(
				SourceEvidenceState(
					collectedDataEpoch = expectedCollectedDataEpoch,
					retainedFromMs = expectedRetainedFromMs,
					updatedAtMs = updatedAtMs,
				),
			)
			val inserted = dao.get()
				?: return@withTransaction SourceEvidenceRetentionBootstrapResult.InvalidState
			if (!isLifecycleSnapshotCurrent()) throw LifecycleChangedDuringBootstrap
			inserted.classifyForRetentionBootstrap(
				expectedCollectedDataEpoch,
				expectedRetainedFromMs,
			)
		}
	} catch (_: LifecycleChangedDuringBootstrap) {
		SourceEvidenceRetentionBootstrapResult.LifecycleChanged
	}
}

private fun SourceEvidenceState.classifyForRetentionBootstrap(
	expectedCollectedDataEpoch: Long,
	expectedRetainedFromMs: Long?,
): SourceEvidenceRetentionBootstrapResult {
	if (
		id != SourceEvidenceState.SINGLETON_ID ||
		revision < 0L ||
		collectedDataEpoch < 0L ||
		retainedFromMs?.let { it < 0L } == true ||
		deletedSourceEventHighWaterOrdinal < 0L ||
		updatedAtMs < 0L
	) {
		return SourceEvidenceRetentionBootstrapResult.InvalidState
	}
	return if (
		collectedDataEpoch == expectedCollectedDataEpoch &&
		retainedFromMs == expectedRetainedFromMs
	) {
		SourceEvidenceRetentionBootstrapResult.Ready(this)
	} else {
		SourceEvidenceRetentionBootstrapResult.LifecycleChanged
	}
}

private fun SupportSQLiteDatabase.hasConflictingRetentionEvidence(): Boolean {
	val tables = query(
		"SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(cursor.getString(0))
		}
	}
	return tables.any { table ->
		table !in RETENTION_BOOTSTRAP_NON_EVIDENCE_TABLES &&
			query("SELECT EXISTS(SELECT 1 FROM ${table.quotedIdentifier()} LIMIT 1)").use { cursor ->
				check(cursor.moveToFirst()) { "Unable to inspect $table during retention bootstrap" }
				cursor.getInt(0) != 0
			}
	}
}

private fun String.quotedIdentifier(): String = "\"${replace("\"", "\"\"")}\""

private object LifecycleChangedDuringBootstrap :
	IllegalStateException("Collected-data lifecycle changed during retention bootstrap")

private val RETENTION_BOOTSTRAP_NON_EVIDENCE_TABLES = setOf(
	"android_metadata",
	"room_master_table",
	"source_evidence_state",
	"source_destination_owner",
	"source_policy_authority",
	"source_policy",
	"source_consent_epoch",
	"activity_automation_epoch",
	"tracking_rollout_state",
	"session_activity",
	"player_profile",
	"minigame_score",
)
