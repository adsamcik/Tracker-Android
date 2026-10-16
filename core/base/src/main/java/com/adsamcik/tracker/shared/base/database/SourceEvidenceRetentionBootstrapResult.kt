package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState

/**
 * Established original epoch/floor source state or a named epoch, floor, conflicting-evidence or
 * invalid-state boundary.
 */
sealed interface SourceEvidenceRetentionBootstrapResult {
	/**
	 * The requested epoch/floor is represented by the returned initialized source-evidence state.
	 */
	data class Ready(val state: SourceEvidenceState) :
		SourceEvidenceRetentionBootstrapResult

	/**
	 * The saved collected-data epoch differs from the initialization request.
	 */
	data object CollectedDataEpochChanged : SourceEvidenceRetentionBootstrapResult

	/**
	 * The saved monotonic retention floor differs from the requested floor.
	 */
	data object RetainedFromChanged : SourceEvidenceRetentionBootstrapResult

	/**
	 * Existing source data conflicts with the proposed initial evidence state.
	 */
	data object ConflictingEvidence : SourceEvidenceRetentionBootstrapResult

	/**
	 * The proposed initial epoch/floor/timestamps cannot form a valid source-evidence state.
	 */
	data object InvalidState : SourceEvidenceRetentionBootstrapResult
}

/**
 * Initializes the singleton evidence guard only for an exact, still-current lifecycle snapshot.
 *
 * SourcePolicy rows and immutable destination ownership are authority inputs, not collected
 * evidence. Every other populated v28 table is conservatively treated as evidence that a missing
 * singleton cannot safely describe. The caller must hold the cross-store retention operation lease
 * from before reading the lifecycle snapshot until this Room-only transaction returns.
 */
suspend fun AppDatabase.bootstrapSourceEvidenceForRetention(
	expectedCollectedDataEpoch: Long,
	expectedRetainedFromMs: Long?,
	updatedAtMs: Long,
): SourceEvidenceRetentionBootstrapResult {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedRetainedFromMs == null || expectedRetainedFromMs >= 0L)
	require(updatedAtMs >= 0L)
	return withTransaction {
		val sqlite = openHelper.writableDatabase
		val evidenceRowCount = sqlite.rowCount("source_evidence_state")
		if (evidenceRowCount > 1L) {
			return@withTransaction SourceEvidenceRetentionBootstrapResult.InvalidState
		}
		val dao = sourceEvidenceStateDao()
		if (evidenceRowCount == 1L) {
			val current = dao.get()
				?: return@withTransaction SourceEvidenceRetentionBootstrapResult.InvalidState
			return@withTransaction current.classifyForRetentionBootstrap(
				expectedCollectedDataEpoch,
				expectedRetainedFromMs,
			)
		}
		if (sqlite.hasConflictingRetentionEvidence()) {
			return@withTransaction SourceEvidenceRetentionBootstrapResult.ConflictingEvidence
		}
		dao.ensure(
			SourceEvidenceState(
				collectedDataEpoch = expectedCollectedDataEpoch,
				retainedFromMs = expectedRetainedFromMs,
				updatedAtMs = updatedAtMs,
			),
		)
		if (sqlite.rowCount("source_evidence_state") != 1L) {
			return@withTransaction SourceEvidenceRetentionBootstrapResult.InvalidState
		}
		val inserted = dao.get()
			?: return@withTransaction SourceEvidenceRetentionBootstrapResult.InvalidState
		inserted.classifyForRetentionBootstrap(
			expectedCollectedDataEpoch,
			expectedRetainedFromMs,
		)
	}
}

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
private fun SourceEvidenceState.classifyForRetentionBootstrap(
	expectedCollectedDataEpoch: Long,
	expectedRetainedFromMs: Long?,
): SourceEvidenceRetentionBootstrapResult {
	// Validate the singleton evidence row and its nonnegative scalar fields together.
	@Suppress("ComplexCondition")
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
	return when {
		collectedDataEpoch != expectedCollectedDataEpoch ->
			SourceEvidenceRetentionBootstrapResult.CollectedDataEpochChanged
		retainedFromMs != expectedRetainedFromMs ->
			SourceEvidenceRetentionBootstrapResult.RetainedFromChanged
		else -> SourceEvidenceRetentionBootstrapResult.Ready(this)
	}
}

private fun SupportSQLiteDatabase.hasConflictingRetentionEvidence(): Boolean {
	if (hasNonZeroAutoincrementHighWater()) {
		return true
	}
	val tables = query(
		"SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(cursor.getString(0))
		}
	}
	return tables.any { table ->
		when (table) {
			"player_profile" -> !hasExactPlayerProfileScaffold()
			"minigame_score" -> rowCount(table) != 0L
			in RETENTION_BOOTSTRAP_NON_EVIDENCE_TABLES -> false
			else -> rowCount(table) != 0L
		}
	}
}

private fun SupportSQLiteDatabase.hasNonZeroAutoincrementHighWater(): Boolean =
	query(
		"SELECT EXISTS(SELECT 1 FROM sqlite_sequence " +
			"WHERE (name IS NULL OR name != 'activity') AND (seq IS NULL OR seq != 0) LIMIT 1)",
	).use { cursor ->
		check(cursor.moveToFirst()) {
			"Unable to inspect AUTOINCREMENT high-water during retention bootstrap"
		}
		cursor.getInt(0) != 0
	}

private fun SupportSQLiteDatabase.hasExactPlayerProfileScaffold(): Boolean =
	query(
		"SELECT id, total_xp, level, xp_into_current_level, xp_for_next_level " +
			"FROM player_profile ORDER BY id LIMIT 2",
	).use { cursor ->
		if (!cursor.moveToFirst()) {
			return@use true
		}
		val exactDefault =
			cursor.getInt(0) == 1 &&
				cursor.getLong(1) == 0L &&
				cursor.getInt(2) == 1 &&
				cursor.getLong(3) == 0L &&
				cursor.getLong(4) == 30L
		exactDefault && !cursor.moveToNext()
	}

private fun SupportSQLiteDatabase.rowCount(table: String): Long =
	query("SELECT COUNT(*) FROM ${table.quotedIdentifier()}").use { cursor ->
		check(cursor.moveToFirst()) { "Unable to count $table during retention bootstrap" }
		cursor.getLong(0)
	}

private fun String.quotedIdentifier(): String = "\"${replace("\"", "\"\"")}\""

private val RETENTION_BOOTSTRAP_NON_EVIDENCE_TABLES = setOf(
	// Reusable user activity labels are reference data, not tracking observations.
	"activity",
	"android_metadata",
	"room_master_table",
	// Structural contract marker installed at database creation; it is not a captured fact.
	StepsCountDomainSchema.SCHEMA_MARKER_TABLE,
	"source_evidence_state",
	"source_destination_owner",
	"source_policy_authority",
	"source_policy",
	"source_consent_epoch",
	"activity_automation_epoch",
	"tracking_rollout_state",
)
