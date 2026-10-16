package com.adsamcik.tracker.shared.base.database.steps.imported

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity

/**
 * Full-clear preflight inside the deletion transaction. Portable files carry no local epoch, so
 * original Steps capture digests must outlive collected payload and repeated clears. A scope fence
 * forbids restoration; it does not qualify any captured metric or grant local provider authority.
 */
internal fun preserveStepsFullClearFences(
	sqlite: SupportSQLiteDatabase,
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	deletedAtMs: Long,
) {
	require(oldCollectedDataEpoch >= 0L)
	require(newCollectedDataEpoch > oldCollectedDataEpoch)
	require(deletedAtMs >= 0L)
	val storedEpoch = sqlite.query(
		"SELECT collected_data_epoch FROM source_evidence_state WHERE id = 1",
	).use { cursor ->
		check(cursor.moveToFirst()) { "Full clear requires durable epoch authority" }
		cursor.getLong(0)
	}
	check(storedEpoch == oldCollectedDataEpoch) {
		"Steps full clear must authenticate the stored old epoch"
	}
	authenticateAndReepochRetainedStepsFences(
		sqlite,
		oldCollectedDataEpoch,
		newCollectedDataEpoch,
		deletedAtMs,
	)
	val insert = sqlite.compileStatement(
		"INSERT OR IGNORE INTO source_deletion_fence " +
			"(source_kind, purpose, scope_kind, scope_identity_digest, fence_generation, " +
			"collected_data_epoch, deleted_at_ms, effect_checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
	)
	insert.use { statement ->
		fun install(digest: String) {
			val fence = readStepsFenceOrNull(sqlite, digest)
				?: SourceDeletionFenceEntity.createForOriginalRunDigest(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
					digest,
					1L,
					newCollectedDataEpoch,
					deletedAtMs,
				)
			SimpleSQLiteQuery.bind(
				statement,
				arrayOf<Any?>(
					fence.sourceKind.toLong(),
					fence.purpose,
					fence.scopeKind,
					fence.scopeIdentityDigest,
					fence.fenceGeneration,
					fence.collectedDataEpoch,
					fence.deletedAtMs,
					fence.effectChecksum,
				),
			)
			statement.executeInsert()
			val winner = readStepsFence(sqlite, digest)
			check(winner == fence) { "Steps full-clear fence winner is incompatible" }
		}
		// The source tables are the disk-backed traversal bound; no smaller deletion-only cap may
		// make an admitted run undeletable. Retain foreign digests verbatim, never re-hashed.
		sqlite.query("SELECT deletion_scope_digest FROM imported_steps_run").use { cursor ->
			while (cursor.moveToNext()) install(cursor.getString(0))
		}
		// Explicit immutable persisted Steps capture membership only. No control/ambient promotion,
		// wall-time ownership inference, sampleCount qualification, or reliance on stop settlement.
		sqlite.query(
			"""
			SELECT DISTINCT run.logical_tracking_id, run.service_run_id
			FROM source_service_run AS run
			JOIN session_manifest_version AS manifest
			  ON manifest.service_run_id = run.service_run_id
			 AND manifest.logical_tracking_id = run.logical_tracking_id
			JOIN session_manifest_source AS source
			  ON source.logical_tracking_id = manifest.logical_tracking_id
			 AND source.manifest_revision = manifest.manifest_revision
			WHERE source.source_kind = ${SourceDestinationOwnerEntity.SOURCE_STEPS}
			  AND source.purpose = 'SESSION_CAPTURE' AND source.persistence_eligible = 1
			""".trimIndent(),
		).use { cursor ->
			while (cursor.moveToNext()) {
				install(SourceDeletionFenceEntity.logicalServiceRunIdentity(
					SourceDestinationOwnerEntity.SOURCE_STEPS, StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
					cursor.getString(0), cursor.getString(1),
				))
			}
		}
	}
}

private fun authenticateAndReepochRetainedStepsFences(
	sqlite: SupportSQLiteDatabase,
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	clearedAtMs: Long,
) {
	val storedCount = sqlite.query(
		"SELECT COUNT(*) FROM source_deletion_fence WHERE source_kind = ? AND purpose = ?",
		arrayOf<Any?>(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		),
	).use { cursor ->
		check(cursor.moveToFirst())
		cursor.getLong(0)
	}
	check(storedCount >= 0L)
	val distinctScopeCount = sqlite.query(
		"SELECT COUNT(DISTINCT scope_identity_digest) FROM source_deletion_fence " +
			"WHERE source_kind = ? AND purpose = ?",
		arrayOf<Any?>(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		),
	).use { cursor ->
		check(cursor.moveToFirst())
		cursor.getLong(0)
	}
	check(distinctScopeCount == storedCount) {
		"Steps full-clear fence identities conflict across stored scopes"
	}
	var authenticatedCount = 0L
	sqlite.query(
		"SELECT source_kind, purpose, scope_kind, scope_identity_digest, fence_generation, " +
			"collected_data_epoch, deleted_at_ms, effect_checksum FROM source_deletion_fence " +
			"WHERE source_kind = ? AND purpose = ? ORDER BY scope_identity_digest",
		arrayOf<Any?>(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		),
	).use { cursor ->
		while (cursor.moveToNext()) {
			reepochRetainedStepsFence(sqlite, cursor, oldCollectedDataEpoch, newCollectedDataEpoch, clearedAtMs)
			authenticatedCount = Math.addExact(authenticatedCount, 1L)
		}
	}
	check(authenticatedCount == storedCount)
}

private fun reepochRetainedStepsFence(
	sqlite: SupportSQLiteDatabase,
	cursor: Cursor,
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	clearedAtMs: Long,
) {
	val current = cursor.readStepsDeletionFence()
	check(current.scopeKind == SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN)
	check(current.collectedDataEpoch <= oldCollectedDataEpoch)
	check(current.deletedAtMs <= clearedAtMs)
	val replacement = SourceDeletionFenceEntity.createForOriginalRunDigest(
		sourceKind = current.sourceKind,
		purpose = current.purpose,
		scopeIdentityDigest = current.scopeIdentityDigest,
		fenceGeneration = current.fenceGeneration,
		collectedDataEpoch = newCollectedDataEpoch,
		deletedAtMs = current.deletedAtMs,
	)
	sqlite.compileStatement(
		"UPDATE source_deletion_fence SET collected_data_epoch = ?, effect_checksum = ? " +
			"WHERE source_kind = ? AND purpose = ? AND scope_kind = ? " +
			"AND scope_identity_digest = ? AND collected_data_epoch = ? " +
			"AND fence_generation = ? AND deleted_at_ms = ? AND effect_checksum = ?",
	).use { statement ->
		SimpleSQLiteQuery.bind(
			statement,
			arrayOf<Any?>(
				replacement.collectedDataEpoch,
				replacement.effectChecksum,
				current.sourceKind.toLong(),
				current.purpose,
				current.scopeKind,
				current.scopeIdentityDigest,
				current.collectedDataEpoch,
				current.fenceGeneration,
				current.deletedAtMs,
				current.effectChecksum,
			),
		)
		check(statement.executeUpdateDelete() == 1) {
			"Steps full-clear fence epoch transition lost its exact winner"
		}
	}
}

private fun readStepsFenceOrNull(
	sqlite: SupportSQLiteDatabase,
	scopeIdentityDigest: String,
): SourceDeletionFenceEntity? = sqlite.query(
	"SELECT source_kind, purpose, scope_kind, scope_identity_digest, fence_generation, " +
		"collected_data_epoch, deleted_at_ms, effect_checksum FROM source_deletion_fence " +
		"WHERE source_kind = ? AND purpose = ? AND scope_kind = ? " +
		"AND scope_identity_digest = ? LIMIT 1",
	arrayOf<Any?>(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeIdentityDigest,
	),
).use { cursor ->
	if (!cursor.moveToFirst()) {
		null
	} else {
		cursor.readStepsDeletionFence()
	}
}

private fun readStepsFence(
	sqlite: SupportSQLiteDatabase,
	scopeIdentityDigest: String,
): SourceDeletionFenceEntity = checkNotNull(readStepsFenceOrNull(sqlite, scopeIdentityDigest)) {
	"Steps full-clear fence winner disappeared"
}

private fun Cursor.readStepsDeletionFence() = SourceDeletionFenceEntity(
	sourceKind = getInt(getColumnIndexOrThrow("source_kind")),
	purpose = getString(getColumnIndexOrThrow("purpose")),
	scopeKind = getString(getColumnIndexOrThrow("scope_kind")),
	scopeIdentityDigest = getString(getColumnIndexOrThrow("scope_identity_digest")),
	fenceGeneration = getLong(getColumnIndexOrThrow("fence_generation")),
	collectedDataEpoch = getLong(getColumnIndexOrThrow("collected_data_epoch")),
	deletedAtMs = getLong(getColumnIndexOrThrow("deleted_at_ms")),
	effectChecksum = getString(getColumnIndexOrThrow("effect_checksum")),
)
