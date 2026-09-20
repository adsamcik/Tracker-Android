package com.adsamcik.tracker.shared.base.database.steps.imported

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity

/**
 * Full-clear preflight inside the deletion transaction. Portable files carry no local epoch, so
 * original Steps capture digests must outlive collected payload and repeated clears. A scope fence
 * forbids restoration; it does not qualify any captured metric or grant local provider authority.
 */
@Suppress("MagicNumber") // Positional SQL bindings follow the explicit INSERT column order below.
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
			statement.bindLong(1, fence.sourceKind.toLong())
			statement.bindString(2, fence.purpose)
			statement.bindString(3, fence.scopeKind)
			statement.bindString(4, fence.scopeIdentityDigest)
			statement.bindLong(5, fence.fenceGeneration)
			statement.bindLong(6, fence.collectedDataEpoch)
			statement.bindLong(7, fence.deletedAtMs)
			statement.bindString(8, fence.effectChecksum)
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
		arrayOf(
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
		arrayOf(
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
		arrayOf(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		),
	).use { cursor ->
		while (cursor.moveToNext()) {
			val current = SourceDeletionFenceEntity(
				sourceKind = cursor.getInt(0),
				purpose = cursor.getString(1),
				scopeKind = cursor.getString(2),
				scopeIdentityDigest = cursor.getString(3),
				fenceGeneration = cursor.getLong(4),
				collectedDataEpoch = cursor.getLong(5),
				deletedAtMs = cursor.getLong(6),
				effectChecksum = cursor.getString(7),
			)
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
				statement.bindLong(1, replacement.collectedDataEpoch)
				statement.bindString(2, replacement.effectChecksum)
				statement.bindLong(3, current.sourceKind.toLong())
				statement.bindString(4, current.purpose)
				statement.bindString(5, current.scopeKind)
				statement.bindString(6, current.scopeIdentityDigest)
				statement.bindLong(7, current.collectedDataEpoch)
				statement.bindLong(8, current.fenceGeneration)
				statement.bindLong(9, current.deletedAtMs)
				statement.bindString(10, current.effectChecksum)
				check(statement.executeUpdateDelete() == 1) {
					"Steps full-clear fence epoch transition lost its exact winner"
				}
			}
			authenticatedCount = Math.addExact(authenticatedCount, 1L)
		}
	}
	check(authenticatedCount == storedCount)
}

private fun readStepsFenceOrNull(
	sqlite: SupportSQLiteDatabase,
	scopeIdentityDigest: String,
): SourceDeletionFenceEntity? = sqlite.query(
	"SELECT source_kind, purpose, scope_kind, scope_identity_digest, fence_generation, " +
		"collected_data_epoch, deleted_at_ms, effect_checksum FROM source_deletion_fence " +
		"WHERE source_kind = ? AND purpose = ? AND scope_kind = ? " +
		"AND scope_identity_digest = ? LIMIT 1",
	arrayOf(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeIdentityDigest,
	),
).use { cursor ->
	if (!cursor.moveToFirst()) {
		null
	} else {
		SourceDeletionFenceEntity(
			sourceKind = cursor.getInt(0),
			purpose = cursor.getString(1),
			scopeKind = cursor.getString(2),
			scopeIdentityDigest = cursor.getString(3),
			fenceGeneration = cursor.getLong(4),
			collectedDataEpoch = cursor.getLong(5),
			deletedAtMs = cursor.getLong(6),
			effectChecksum = cursor.getString(7),
		)
	}
}

private fun readStepsFence(
	sqlite: SupportSQLiteDatabase,
	scopeIdentityDigest: String,
): SourceDeletionFenceEntity = checkNotNull(readStepsFenceOrNull(sqlite, scopeIdentityDigest)) {
	"Steps full-clear fence winner disappeared"
}
