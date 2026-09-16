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
	val insert = sqlite.compileStatement(
		"INSERT OR IGNORE INTO source_deletion_fence " +
			"(source_kind, purpose, scope_kind, scope_identity_digest, fence_generation, " +
			"collected_data_epoch, deleted_at_ms, effect_checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
	)
	insert.use { statement ->
		fun install(digest: String) {
			val fence = SourceDeletionFenceEntity.createForOriginalRunDigest(
				SourceDestinationOwnerEntity.SOURCE_STEPS, StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				digest, 1L, newCollectedDataEpoch, deletedAtMs,
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
		}
		// Retain original foreign digests verbatim, not re-hashed portable identities.
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
