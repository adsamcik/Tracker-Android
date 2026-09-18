@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.adsamcik.tracker.shared.base.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.shared.base.database.data.CellCapturedDeletedRunEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionProtectedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionRunMarker
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity

internal fun preserveWifiFullClearAuthority(
	sqlite: SupportSQLiteDatabase,
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	newRetainedFromMs: Long?,
	clearedAtMs: Long,
) {
	requireFullClearEpoch(sqlite, oldCollectedDataEpoch, newCollectedDataEpoch, clearedAtMs)
	reepochSourceDeletionFences(
		sqlite,
		SourceDestinationOwnerEntity.SOURCE_WIFI,
		oldCollectedDataEpoch,
		newCollectedDataEpoch,
	)
	reepochWifiCaptureDeletionGenerations(sqlite, oldCollectedDataEpoch, newCollectedDataEpoch)
	reepochImportedWifiRunDeletions(sqlite, oldCollectedDataEpoch, newCollectedDataEpoch)
	reepochImportedWifiEntryDeletions(sqlite, oldCollectedDataEpoch, newCollectedDataEpoch)
	fenceLiveImportedWifi(sqlite, oldCollectedDataEpoch, newCollectedDataEpoch, clearedAtMs)
	fenceLiveSourceRuns(
		sqlite,
		SourceDestinationOwnerEntity.SOURCE_WIFI,
		newCollectedDataEpoch,
		clearedAtMs,
	)
	reepochSelectedWifiDeletionReceipts(
		sqlite,
		oldCollectedDataEpoch,
		newCollectedDataEpoch,
		newRetainedFromMs,
	)
}

internal fun preserveCellFullClearAuthority(
	database: AppDatabase,
	sqlite: SupportSQLiteDatabase,
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	newRetainedFromMs: Long?,
	clearedAtMs: Long,
) {
	requireFullClearEpoch(sqlite, oldCollectedDataEpoch, newCollectedDataEpoch, clearedAtMs)
	reepochSourceDeletionFences(
		sqlite,
		SourceDestinationOwnerEntity.SOURCE_CELL,
		oldCollectedDataEpoch,
		newCollectedDataEpoch,
	)
	reepochCellCaptureDeletionGenerations(sqlite, oldCollectedDataEpoch, newCollectedDataEpoch)
	reepochImportedCellRunDeletions(sqlite, oldCollectedDataEpoch, newCollectedDataEpoch)
	reepochImportedCellEntryDeletions(sqlite, oldCollectedDataEpoch, newCollectedDataEpoch)
	reepochImportedCellDeletionReceipts(
		sqlite,
		oldCollectedDataEpoch,
		newCollectedDataEpoch,
		newRetainedFromMs,
	)
	reepochCapturedCellDeletionReceipts(
		sqlite,
		oldCollectedDataEpoch,
		newCollectedDataEpoch,
		newRetainedFromMs,
	)
	database.fenceLiveImportedCell(
		sqlite,
		oldCollectedDataEpoch,
		newCollectedDataEpoch,
		newRetainedFromMs,
		clearedAtMs,
	)
	fenceLiveSourceRuns(
		sqlite,
		SourceDestinationOwnerEntity.SOURCE_CELL,
		newCollectedDataEpoch,
		clearedAtMs,
	)
}

private fun requireFullClearEpoch(
	sqlite: SupportSQLiteDatabase,
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	clearedAtMs: Long,
) {
	require(oldCollectedDataEpoch >= 0L)
	require(newCollectedDataEpoch > oldCollectedDataEpoch)
	require(clearedAtMs >= 0L)
	val stored = sqlite.query(
		"SELECT collected_data_epoch FROM source_evidence_state WHERE id = 1",
	).use { cursor ->
		check(cursor.moveToFirst()) { "Full clear requires source-evidence authority" }
		cursor.getLong(0)
	}
	check(stored == oldCollectedDataEpoch) {
		"Source-specific full clear must authenticate the stored old epoch"
	}
}

private fun reepochSourceDeletionFences(
	sqlite: SupportSQLiteDatabase,
	sourceKind: Int,
	oldEpoch: Long,
	newEpoch: Long,
) {
	sqlite.forEachBounded(
		"SELECT * FROM source_deletion_fence WHERE source_kind = ? " +
			"AND purpose = ? ORDER BY scope_identity_digest",
		arrayOf(sourceKind, SessionManifestPurposeCode.SESSION_CAPTURE),
	) { cursor ->
		val current = cursor.sourceDeletionFence()
		check(current.collectedDataEpoch == oldEpoch)
		val replacement = SourceDeletionFenceEntity.createForOriginalRunDigest(
			sourceKind = current.sourceKind,
			purpose = current.purpose,
			scopeIdentityDigest = current.scopeIdentityDigest,
			fenceGeneration = current.fenceGeneration,
			collectedDataEpoch = newEpoch,
			deletedAtMs = current.deletedAtMs,
		)
		sqlite.executeUpdateExact(
			"UPDATE source_deletion_fence SET collected_data_epoch = ?, effect_checksum = ? " +
				"WHERE source_kind = ? AND purpose = ? AND scope_kind = ? " +
				"AND scope_identity_digest = ? AND effect_checksum = ?",
			newEpoch,
			replacement.effectChecksum,
			current.sourceKind,
			current.purpose,
			current.scopeKind,
			current.scopeIdentityDigest,
			current.effectChecksum,
		)
	}
}

private fun fenceLiveSourceRuns(
	sqlite: SupportSQLiteDatabase,
	sourceKind: Int,
	newEpoch: Long,
	clearedAtMs: Long,
) {
	sqlite.forEachBounded(
		"""
		SELECT DISTINCT run.logical_tracking_id, run.service_run_id
		FROM source_service_run AS run
		JOIN session_manifest_version AS manifest
		  ON manifest.service_run_id = run.service_run_id
		 AND manifest.logical_tracking_id = run.logical_tracking_id
		JOIN session_manifest_source AS source
		  ON source.logical_tracking_id = manifest.logical_tracking_id
		 AND source.manifest_revision = manifest.manifest_revision
		WHERE source.source_kind = ?
		  AND source.purpose = ?
		  AND source.persistence_eligible = 1
		ORDER BY run.logical_tracking_id, run.service_run_id
		""".trimIndent(),
		arrayOf(sourceKind, SessionManifestPurposeCode.SESSION_CAPTURE),
	) { cursor ->
		val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
			sourceKind = sourceKind,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			logicalTrackingId = cursor.string("logical_tracking_id"),
			serviceRunId = cursor.string("service_run_id"),
			fenceGeneration = 1L,
			collectedDataEpoch = newEpoch,
			deletedAtMs = clearedAtMs,
		)
		insertOrAuthenticateSourceFence(sqlite, fence)
	}
}

private fun insertOrAuthenticateSourceFence(
	sqlite: SupportSQLiteDatabase,
	expected: SourceDeletionFenceEntity,
) {
	val stored = sqlite.query(
		"SELECT * FROM source_deletion_fence WHERE source_kind = ? AND purpose = ? " +
			"AND scope_kind = ? AND scope_identity_digest = ? LIMIT 1",
		arrayOf(
			expected.sourceKind,
			expected.purpose,
			expected.scopeKind,
			expected.scopeIdentityDigest,
		),
	).use { cursor -> if (cursor.moveToFirst()) cursor.sourceDeletionFence() else null }
	if (stored == null) {
		sqlite.executeInsert(
			"INSERT INTO source_deletion_fence(" +
				"source_kind, purpose, scope_kind, scope_identity_digest, fence_generation, " +
				"collected_data_epoch, deleted_at_ms, effect_checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
			expected.sourceKind,
			expected.purpose,
			expected.scopeKind,
			expected.scopeIdentityDigest,
			expected.fenceGeneration,
			expected.collectedDataEpoch,
			expected.deletedAtMs,
			expected.effectChecksum,
		)
	} else {
		check(stored.collectedDataEpoch == expected.collectedDataEpoch)
	}
}

private fun reepochImportedWifiRunDeletions(
	sqlite: SupportSQLiteDatabase,
	oldEpoch: Long,
	newEpoch: Long,
) {
	sqlite.forEachBounded(
		"SELECT * FROM imported_wifi_deletion_generation ORDER BY run_identity",
	) { cursor ->
		val current = cursor.importedWifiRunDeletion()
		check(current.collectedDataEpoch == oldEpoch)
		val replacement = ImportedWifiDeletionGenerationEntity.create(
			current.runIdentity,
			current.entryIdentity,
			current.deletionScopeDigest,
			newEpoch,
			current.generation,
			current.deletedAtMs,
		)
		sqlite.executeUpdateExact(
			"UPDATE imported_wifi_deletion_generation SET collected_data_epoch = ?, " +
				"effect_checksum = ? WHERE run_identity = ? AND effect_checksum = ?",
			newEpoch,
			replacement.effectChecksum,
			current.runIdentity,
			current.effectChecksum,
		)
	}
}

private fun reepochWifiCaptureDeletionGenerations(
	sqlite: SupportSQLiteDatabase,
	oldEpoch: Long,
	newEpoch: Long,
) {
	sqlite.forEachBounded(
		"SELECT * FROM wifi_capture_deletion_generation " +
			"ORDER BY logical_tracking_id, service_run_id",
	) { cursor ->
		val current = cursor.wifiCaptureDeletionGeneration()
		check(current.collectedDataEpoch == oldEpoch)
		sqlite.executeUpdateExact(
			"UPDATE wifi_capture_deletion_generation SET collected_data_epoch = ? " +
				"WHERE logical_tracking_id = ? AND service_run_id = ? " +
				"AND collected_data_epoch = ?",
			newEpoch,
			current.logicalTrackingId,
			current.serviceRunId,
			oldEpoch,
		)
	}
}

private fun reepochImportedWifiEntryDeletions(
	sqlite: SupportSQLiteDatabase,
	oldEpoch: Long,
	newEpoch: Long,
) {
	sqlite.forEachBounded(
		"SELECT * FROM imported_wifi_entry_deletion ORDER BY entry_identity",
	) { cursor ->
		val current = cursor.importedWifiEntryDeletion()
		check(current.collectedDataEpoch == oldEpoch)
		val replacement = ImportedWifiEntryDeletionEntity.create(
			current.entryIdentity,
			newEpoch,
			current.deletedImportRevision,
			current.deletedAtMs,
		)
		sqlite.executeUpdateExact(
			"UPDATE imported_wifi_entry_deletion SET collected_data_epoch = ?, effect_checksum = ? " +
				"WHERE entry_identity = ? AND effect_checksum = ?",
			newEpoch,
			replacement.effectChecksum,
			current.entryIdentity,
			current.effectChecksum,
		)
	}
}

private fun fenceLiveImportedWifi(
	sqlite: SupportSQLiteDatabase,
	oldEpoch: Long,
	newEpoch: Long,
	clearedAtMs: Long,
) {
	sqlite.forEachBounded(
		"""
		SELECT entry.*
		FROM imported_wifi_entry_revision AS entry
		WHERE entry.import_revision = (
			SELECT MAX(history.import_revision)
			FROM imported_wifi_entry_revision AS history
			WHERE history.identity = entry.identity
		)
		ORDER BY entry.identity
		""".trimIndent(),
	) { cursor ->
		val entry = cursor.importedWifiEntry()
		check(entry.collectedDataEpoch == oldEpoch)
		check(importedWifiEntryDeletion(sqlite, entry.identity) == null)
		val runDeletions = mutableListOf<ImportedWifiDeletionGenerationEntity>()
		sqlite.forEachBounded(
			"SELECT * FROM imported_wifi_run WHERE entry_identity = ? " +
				"AND entry_import_revision = ? ORDER BY identity",
			arrayOf(entry.identity, entry.importRevision),
		) { runCursor ->
			val run = runCursor.importedWifiRun()
			check(run.collectedDataEpoch == oldEpoch)
			val deletion = ImportedWifiDeletionGenerationEntity.create(
				run.identity,
				entry.identity,
				run.deletionScopeDigest,
				newEpoch,
				1L,
				clearedAtMs,
			)
			insertOrAuthenticateImportedWifiRunDeletion(sqlite, deletion)
			runDeletions += deletion
		}
		check(runDeletions.isNotEmpty())
		val deletion = ImportedWifiEntryDeletionEntity.create(
			entry.identity,
			newEpoch,
			entry.importRevision,
			clearedAtMs,
		)
		sqlite.executeInsert(
			"INSERT INTO imported_wifi_entry_deletion(" +
				"entry_identity, collected_data_epoch, deleted_import_revision, deleted_at_ms, " +
				"effect_checksum) VALUES (?, ?, ?, ?, ?)",
			deletion.entryIdentity,
			deletion.collectedDataEpoch,
			deletion.deletedImportRevision,
			deletion.deletedAtMs,
			deletion.effectChecksum,
		)
	}
}

private fun insertOrAuthenticateImportedWifiRunDeletion(
	sqlite: SupportSQLiteDatabase,
	expected: ImportedWifiDeletionGenerationEntity,
) {
	val stored = importedWifiRunDeletion(sqlite, expected.runIdentity)
	if (stored == null) {
		sqlite.executeInsert(
			"INSERT INTO imported_wifi_deletion_generation(" +
				"run_identity, entry_identity, deletion_scope_digest, collected_data_epoch, " +
				"generation, deleted_at_ms, effect_checksum) VALUES (?, ?, ?, ?, ?, ?, ?)",
			expected.runIdentity,
			expected.entryIdentity,
			expected.deletionScopeDigest,
			expected.collectedDataEpoch,
			expected.generation,
			expected.deletedAtMs,
			expected.effectChecksum,
		)
	} else {
		check(stored == expected)
	}
}

private fun reepochSelectedWifiDeletionReceipts(
	sqlite: SupportSQLiteDatabase,
	oldEpoch: Long,
	newEpoch: Long,
	newRetainedFromMs: Long?,
) {
	sqlite.forEachBounded(
		"SELECT * FROM wifi_selected_deletion_receipt " +
			"ORDER BY selection_identity, origin",
	) { cursor ->
		val current = cursor.wifiSelectedDeletionReceipt()
		check(current.collectedDataEpoch == oldEpoch)
		val protected = mutableListOf<WifiSelectedDeletionProtectedIdentityEntity>()
		sqlite.forEachBounded(
			"SELECT * FROM wifi_selected_deletion_protected_identity " +
				"WHERE selection_identity = ? AND receipt_origin = ? " +
				"ORDER BY identity_kind, protected_identity",
			arrayOf(current.selectionIdentity, current.origin),
		) { protectedCursor ->
			val value = protectedCursor.wifiSelectedProtectedIdentity()
			check(value.collectedDataEpoch == oldEpoch)
			val replacement = WifiSelectedDeletionProtectedIdentityEntity.create(
				selectionIdentity = value.selectionIdentity,
				receiptOrigin = value.receiptOrigin,
				identityKind = value.identityKind,
				protectedIdentity = value.protectedIdentity,
				ownerEntryIdentity = value.ownerEntryIdentity,
				ownerRunIdentity = value.ownerRunIdentity,
				deletionScopeDigest = value.deletionScopeDigest,
				aggregateOwnerIdentity = value.aggregateOwnerIdentity,
				aggregateOwnerSemanticRevision = value.aggregateOwnerSemanticRevision,
				revisionCount = value.revisionCount,
				revisionSetChecksum = value.revisionSetChecksum,
				collectedDataEpoch = newEpoch,
			)
			sqlite.executeUpdateExact(
				"UPDATE wifi_selected_deletion_protected_identity " +
					"SET collected_data_epoch = ?, effect_checksum = ? " +
					"WHERE selection_identity = ? AND receipt_origin = ? AND identity_kind = ? " +
					"AND protected_identity = ? AND effect_checksum = ?",
				newEpoch,
				replacement.effectChecksum,
				value.selectionIdentity,
				value.receiptOrigin,
				value.identityKind,
				value.protectedIdentity,
				value.effectChecksum,
			)
			protected += replacement
		}
		val protectedScopes = protected.mapNotNull(
			WifiSelectedDeletionProtectedIdentityEntity::deletionScopeDigest,
		).distinct()
		val sourceFences = protectedScopes.mapNotNull { scope ->
			sourceDeletionFence(
				sqlite,
				SourceDestinationOwnerEntity.SOURCE_WIFI,
				scope,
			)
		}
		if (current.origin == WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL) {
			check(sourceFences.size == protectedScopes.size)
		}
		val runMarkers = protected.filter {
			it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_RUN
		}.map { run ->
			val scope = requireNotNull(run.deletionScopeDigest)
			val fence = sourceFences.singleOrNull { it.scopeIdentityDigest == scope }
			val imported = if (current.origin == WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED) {
				requireNotNull(importedWifiRunDeletion(sqlite, run.protectedIdentity))
			} else {
				check(importedWifiRunDeletion(sqlite, run.protectedIdentity) == null)
				null
			}
			WifiSelectedDeletionRunMarker(
				runIdentity = run.protectedIdentity,
				entryIdentity = current.selectionIdentity,
				deletionScopeDigest = scope,
				collectedDataEpoch = newEpoch,
				generation = imported?.generation ?: requireNotNull(fence).fenceGeneration,
				deletedAtMs = imported?.deletedAtMs ?: requireNotNull(fence).deletedAtMs,
			)
		}
		val replacement = WifiSelectedDeletionReceiptEntity.create(
			selectionIdentity = current.selectionIdentity,
			origin = current.origin,
			collectedDataEpoch = newEpoch,
			selectedImportRevision = current.selectedImportRevision,
			selectedContentChecksum = current.selectedContentChecksum,
			startTimeMs = current.startTimeMs,
			endTimeMs = current.endTimeMs,
			protectedIdentities = protected,
			runDeletionRows = runMarkers,
			sourceFences = sourceFences,
			retainedFromMs = newRetainedFromMs,
			deletedAtMs = current.deletedAtMs,
		)
		sqlite.executeUpdateExact(
			"UPDATE wifi_selected_deletion_receipt SET collected_data_epoch = ?, " +
				"retained_from_ms = ?, " +
				"protected_identity_set_checksum = ?, run_deletion_set_checksum = ?, " +
				"source_fence_set_checksum = ?, effect_checksum = ? " +
				"WHERE selection_identity = ? AND origin = ? AND effect_checksum = ?",
			newEpoch,
			newRetainedFromMs,
			replacement.protectedIdentitySetChecksum,
			replacement.runDeletionSetChecksum,
			replacement.sourceFenceSetChecksum,
			replacement.effectChecksum,
			current.selectionIdentity,
			current.origin,
			current.effectChecksum,
		)
		check(
			sqlite.query(
				"SELECT * FROM wifi_selected_deletion_receipt " +
					"WHERE selection_identity = ? AND origin = ? LIMIT 1",
				arrayOf(current.selectionIdentity, current.origin),
			).use { stored ->
				stored.moveToFirst() && stored.wifiSelectedDeletionReceipt() == replacement
			},
		) { "Wi-Fi deletion receipt re-epoch was not authenticated before full-clear commit" }
	}
}

private fun reepochImportedCellRunDeletions(
	sqlite: SupportSQLiteDatabase,
	oldEpoch: Long,
	newEpoch: Long,
) {
	sqlite.forEachBounded(
		"SELECT * FROM imported_cell_deletion_generation ORDER BY run_identity",
	) { cursor ->
		val current = cursor.importedCellRunDeletion()
		check(current.collectedDataEpoch == oldEpoch)
		val replacement = ImportedCellDeletionGenerationEntity.create(
			current.runIdentity,
			current.entryIdentity,
			current.deletionScopeDigest,
			newEpoch,
			current.generation,
			current.deletedAtMs,
		)
		sqlite.executeUpdateExact(
			"UPDATE imported_cell_deletion_generation SET collected_data_epoch = ?, " +
				"effect_checksum = ? WHERE run_identity = ? AND effect_checksum = ?",
			newEpoch,
			replacement.effectChecksum,
			current.runIdentity,
			current.effectChecksum,
		)
	}
}

private fun reepochCellCaptureDeletionGenerations(
	sqlite: SupportSQLiteDatabase,
	oldEpoch: Long,
	newEpoch: Long,
) {
	sqlite.forEachBounded(
		"SELECT * FROM cell_capture_deletion_generation " +
			"ORDER BY logical_tracking_id, service_run_id",
	) { cursor ->
		val current = cursor.cellCaptureDeletionGeneration()
		check(current.collectedDataEpoch == oldEpoch)
		sqlite.executeUpdateExact(
			"UPDATE cell_capture_deletion_generation SET collected_data_epoch = ? " +
				"WHERE logical_tracking_id = ? AND service_run_id = ? " +
				"AND collected_data_epoch = ?",
			newEpoch,
			current.logicalTrackingId,
			current.serviceRunId,
			oldEpoch,
		)
	}
}

private fun reepochImportedCellEntryDeletions(
	sqlite: SupportSQLiteDatabase,
	oldEpoch: Long,
	newEpoch: Long,
) {
	sqlite.forEachBounded(
		"SELECT * FROM imported_cell_entry_deletion ORDER BY entry_identity",
	) { cursor ->
		val current = cursor.importedCellEntryDeletion()
		check(current.collectedDataEpoch == oldEpoch)
		val replacement = ImportedCellEntryDeletionEntity.create(
			current.entryIdentity,
			newEpoch,
			current.deletedImportRevision,
			current.deletedAtMs,
		)
		sqlite.executeUpdateExact(
			"UPDATE imported_cell_entry_deletion SET collected_data_epoch = ?, effect_checksum = ? " +
				"WHERE entry_identity = ? AND effect_checksum = ?",
			newEpoch,
			replacement.effectChecksum,
			current.entryIdentity,
			current.effectChecksum,
		)
	}
}

private fun AppDatabase.fenceLiveImportedCell(
	sqlite: SupportSQLiteDatabase,
	oldEpoch: Long,
	newEpoch: Long,
	newRetainedFromMs: Long?,
	clearedAtMs: Long,
) {
	val dao = importedCellDao()
	sqlite.forEachBounded(
		"""
		SELECT entry.*
		FROM imported_cell_entry_revision AS entry
		WHERE entry.import_revision = (
			SELECT MAX(history.import_revision)
			FROM imported_cell_entry_revision AS history
			WHERE history.identity = entry.identity
		)
		ORDER BY entry.identity
		""".trimIndent(),
	) { cursor ->
		val entry = cursor.importedCellEntry()
		check(entry.collectedDataEpoch == oldEpoch)
		check(importedCellEntryDeletion(sqlite, entry.identity) == null)
		val headers = dao.entryRevisionsForFullClear(
			entry.identity,
			com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao.MAX_REVISIONS_PER_ENTRY + 1,
		)
		val receipts = dao.receiptsForFullClear(
			entry.identity,
			com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao.MAX_RECEIPTS_PER_ENTRY + 1,
		)
		val runs = dao.runsForFullClear(
			entry.identity,
			com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE + 1,
		)
		val observations = dao.observationsForFullClear(
			entry.identity,
			com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
				.MAX_OBSERVATION_ROWS_PER_LINEAGE + 1,
		)
		val lineage = ImportedCellLineageAuthenticator.authenticate(
			identity = entry.identity,
			expectedCollectedDataEpoch = oldEpoch,
			headers = headers,
			receipts = receipts,
			runs = runs,
			observations = observations,
		)
		val latest = lineage.revisions.last()
		check(latest.header == entry)
		val protected = deletedCellIdentityMarkers(lineage)
		val uniqueRuns = runs.distinctBy(ImportedCellRunEntity::identity)
		val runDeletions = mutableListOf<ImportedCellDeletionGenerationEntity>()
		uniqueRuns.forEach { run ->
			val deletion = ImportedCellDeletionGenerationEntity.create(
				run.identity,
				entry.identity,
				run.deletionScopeDigest,
				newEpoch,
				1L,
				clearedAtMs,
			)
			insertOrAuthenticateImportedCellRunDeletion(sqlite, deletion)
			insertOrAuthenticateSourceFence(
				sqlite,
				SourceDeletionFenceEntity.createForOriginalRunDigest(
					SourceDestinationOwnerEntity.SOURCE_CELL,
					SessionManifestPurposeCode.SESSION_CAPTURE,
					run.deletionScopeDigest,
					1L,
					newEpoch,
					clearedAtMs,
				),
			)
			runDeletions += deletion
		}
		check(runDeletions.isNotEmpty())
		val deletion = ImportedCellEntryDeletionEntity.create(
			entry.identity,
			newEpoch,
			entry.importRevision,
			clearedAtMs,
		)
		sqlite.executeInsert(
			"INSERT INTO imported_cell_entry_deletion(" +
				"entry_identity, collected_data_epoch, deleted_import_revision, deleted_at_ms, " +
				"effect_checksum) VALUES (?, ?, ?, ?, ?)",
			deletion.entryIdentity,
			deletion.collectedDataEpoch,
			deletion.deletedImportRevision,
			deletion.deletedAtMs,
			deletion.effectChecksum,
		)
		val receipt = ImportedCellEntryDeletionReceiptEntity.create(
			entryDeletion = deletion,
			deletedContentChecksum = latest.entry.contentChecksum.value,
			sessionMode = latest.entry.sessionMode.name,
			subscriptionGrouping = latest.entry.subscriptionGrouping.name,
			startTimeMs = latest.entry.startTimeMs,
			endTimeMs = latest.entry.endTimeMs,
			receivedAtMs = latest.header.receivedAtMs,
			retainedFromMs = newRetainedFromMs,
			revisionCount = lineage.revisions.size,
			receiptCount = lineage.receipts.size,
			runCount = uniqueRuns.size,
			observationCount = observations.size,
			protectedIdentities = protected,
		)
		dao.insertEntryDeletionReceiptForFullClear(receipt)
		dao.insertDeletedIdentitiesForFullClear(protected)
	}
}

private fun deletedCellIdentityMarkers(
	lineage: AuthenticatedImportedCellLineage,
): List<ImportedCellDeletedIdentityEntity> {
	val markers = linkedMapOf<String, ImportedCellDeletedIdentityEntity>()
	fun bind(marker: ImportedCellDeletedIdentityEntity) {
		val previous = markers.putIfAbsent(marker.protectedIdentity, marker)
		check(previous == null || previous == marker)
	}
	val latest = lineage.revisions.last()
	val entryIdentity = latest.header.identity
	bind(ImportedCellDeletedIdentityEntity.create(
		protectedIdentity = entryIdentity,
		entryIdentity = entryIdentity,
		identityKind = ImportedCellDeletedIdentityEntity.ENTRY,
		contentChecksum = latest.entry.contentChecksum.value,
	))
	latest.entry.runs.forEach { run ->
		bind(ImportedCellDeletedIdentityEntity.create(
			protectedIdentity = run.identity.value,
			entryIdentity = entryIdentity,
			identityKind = ImportedCellDeletedIdentityEntity.RUN,
			runIdentity = run.identity.value,
			deletionScopeDigest = run.deletionScopeDigest.value,
			runStartTimeMs = run.startTimeMs,
			runEndTimeMs = run.endTimeMs,
			contentChecksum = run.contentChecksum.value,
			includedInLatest = true,
			captureCoverage = run.captureCoverage.name,
			availability = run.availability.name,
			acquisitionCompleteness = run.acquisitionCompleteness.name,
			retentionLoss = run.retentionLoss,
			subscriptionGrouping = run.subscriptionGrouping.name,
		))
		bind(ImportedCellDeletedIdentityEntity.create(
			protectedIdentity = run.deletionScopeDigest.value,
			entryIdentity = entryIdentity,
			identityKind = ImportedCellDeletedIdentityEntity.DELETION_SCOPE,
			runIdentity = run.identity.value,
			deletionScopeDigest = run.deletionScopeDigest.value,
			includedInLatest = true,
		))
	}
	val terminalObservations =
		linkedMapOf<String, Pair<String, PortableCapturedCellObservationV1>>()
	lineage.revisions.forEach { revision ->
		revision.entry.runs.forEach { run ->
			run.observations.forEach { observation ->
				terminalObservations[observation.identity.value] = run.identity.value to observation
			}
		}
	}
	val latestObservationOrdinals = latest.entry.runs.flatMap { run ->
		run.observations.mapIndexed { ordinal, observation ->
			observation.identity.value to ordinal
		}
	}.toMap()
	terminalObservations.values.forEach { (runIdentity, observation) ->
		val latestOrdinal = latestObservationOrdinals[observation.identity.value]
		bind(ImportedCellDeletedIdentityEntity.create(
			protectedIdentity = observation.identity.value,
			entryIdentity = entryIdentity,
			identityKind = ImportedCellDeletedIdentityEntity.OBSERVATION,
			runIdentity = runIdentity,
			aggregateOwnerIdentity = observation.aggregateOwnerIdentity?.value,
			contentChecksum = observation.contentChecksum.value,
			includedInLatest = latestOrdinal != null,
			observationOrdinal = latestOrdinal,
		))
	}
	check(markers.size <= ImportedCellEntryDeletionReceiptEntity.MAX_PROTECTED_IDENTITIES)
	return markers.values.toList()
}

private fun insertOrAuthenticateImportedCellRunDeletion(
	sqlite: SupportSQLiteDatabase,
	expected: ImportedCellDeletionGenerationEntity,
) {
	val stored = importedCellRunDeletion(sqlite, expected.runIdentity)
	if (stored == null) {
		sqlite.executeInsert(
			"INSERT INTO imported_cell_deletion_generation(" +
				"run_identity, entry_identity, deletion_scope_digest, collected_data_epoch, " +
				"generation, deleted_at_ms, effect_checksum) VALUES (?, ?, ?, ?, ?, ?, ?)",
			expected.runIdentity,
			expected.entryIdentity,
			expected.deletionScopeDigest,
			expected.collectedDataEpoch,
			expected.generation,
			expected.deletedAtMs,
			expected.effectChecksum,
		)
	} else {
		check(stored == expected)
	}
}

private fun reepochImportedCellDeletionReceipts(
	sqlite: SupportSQLiteDatabase,
	oldEpoch: Long,
	newEpoch: Long,
	newRetainedFromMs: Long?,
) {
	sqlite.forEachBounded(
		"SELECT * FROM imported_cell_entry_deletion_receipt ORDER BY entry_identity",
	) { cursor ->
		val current = cursor.importedCellDeletionReceipt()
		check(current.collectedDataEpoch == oldEpoch)
		val deletion = requireNotNull(importedCellEntryDeletion(sqlite, current.entryIdentity))
		check(deletion.collectedDataEpoch == newEpoch)
		val protected = mutableListOf<ImportedCellDeletedIdentityEntity>()
		sqlite.forEachBounded(
			"SELECT * FROM imported_cell_deleted_identity WHERE entry_identity = ? " +
				"ORDER BY protected_identity",
			arrayOf(current.entryIdentity),
		) { protectedCursor ->
			protected += protectedCursor.importedCellDeletedIdentity()
		}
		val replacement = ImportedCellEntryDeletionReceiptEntity.create(
			entryDeletion = deletion,
			deletedContentChecksum = current.deletedContentChecksum,
			sessionMode = current.sessionMode,
			subscriptionGrouping = current.subscriptionGrouping,
			startTimeMs = current.startTimeMs,
			endTimeMs = current.endTimeMs,
			receivedAtMs = current.receivedAtMs,
			retainedFromMs = newRetainedFromMs,
			revisionCount = current.expectedRevisionCount,
			receiptCount = current.expectedReceiptCount,
			runCount = current.expectedRunCount,
			observationCount = current.expectedObservationCount,
			protectedIdentities = protected,
		)
		sqlite.executeUpdateExact(
			"UPDATE imported_cell_entry_deletion_receipt SET collected_data_epoch = ?, " +
				"retained_from_ms = ?, effect_checksum = ? " +
				"WHERE entry_identity = ? AND effect_checksum = ?",
			newEpoch,
			newRetainedFromMs,
			replacement.effectChecksum,
			current.entryIdentity,
			current.effectChecksum,
		)
		check(
			sqlite.query(
				"SELECT * FROM imported_cell_entry_deletion_receipt " +
					"WHERE entry_identity = ? LIMIT 1",
				arrayOf(current.entryIdentity),
			).use { stored ->
				stored.moveToFirst() && stored.importedCellDeletionReceipt() == replacement
			},
		) { "Cell deletion receipt re-epoch was not authenticated before full-clear commit" }
	}
}

private fun reepochCapturedCellDeletionReceipts(
	sqlite: SupportSQLiteDatabase,
	oldEpoch: Long,
	newEpoch: Long,
	newRetainedFromMs: Long?,
) {
	sqlite.forEachBounded(
		"SELECT * FROM cell_captured_entry_deletion_receipt ORDER BY logical_tracking_id",
	) { cursor ->
		val current = cursor.cellCapturedDeletionReceipt()
		check(current.collectedDataEpoch == oldEpoch)
		val runs = mutableListOf<CellCapturedDeletedRunEntity>()
		sqlite.forEachBounded(
			"SELECT * FROM cell_captured_deleted_run WHERE logical_tracking_id = ? " +
				"ORDER BY start_time_ms, session_segment_id, service_run_id",
			arrayOf(current.logicalTrackingId),
		) { runCursor ->
			val oldRun = runCursor.cellCapturedDeletedRun()
			check(oldRun.collectedDataEpoch == oldEpoch)
			val replacement = CellCapturedDeletedRunEntity.create(
				logicalTrackingId = oldRun.logicalTrackingId,
				serviceRunId = oldRun.serviceRunId,
				sessionSegmentId = oldRun.sessionSegmentId,
				startTimeMs = oldRun.startTimeMs,
				endTimeMs = oldRun.endTimeMs,
				collectedDataEpoch = newEpoch,
				deletedAtMs = oldRun.deletedAtMs,
			)
			check(replacement.scopeIdentityDigest == oldRun.scopeIdentityDigest)
			requireNotNull(sourceDeletionFence(
				sqlite,
				SourceDestinationOwnerEntity.SOURCE_CELL,
				replacement.scopeIdentityDigest,
			))
			sqlite.executeUpdateExact(
				"UPDATE cell_captured_deleted_run SET collected_data_epoch = ?, " +
					"effect_checksum = ? WHERE logical_tracking_id = ? AND service_run_id = ? " +
					"AND effect_checksum = ?",
				newEpoch,
				replacement.effectChecksum,
				oldRun.logicalTrackingId,
				oldRun.serviceRunId,
				oldRun.effectChecksum,
			)
			runs += replacement
		}
		val replacement = CellCapturedEntryDeletionReceiptEntity.create(
			logicalTrackingId = current.logicalTrackingId,
			entryIdentity = current.entryIdentity,
			collectedDataEpoch = newEpoch,
			runFootprints = runs,
			retainedFromMs = newRetainedFromMs,
			deletedAtMs = current.deletedAtMs,
		)
		sqlite.executeUpdateExact(
			"UPDATE cell_captured_entry_deletion_receipt SET collected_data_epoch = ?, " +
				"run_footprint_set_checksum = ?, retained_from_ms = ?, effect_checksum = ? " +
				"WHERE logical_tracking_id = ? AND effect_checksum = ?",
			newEpoch,
			replacement.runFootprintSetChecksum,
			newRetainedFromMs,
			replacement.effectChecksum,
			current.logicalTrackingId,
			current.effectChecksum,
		)
		check(
			sqlite.query(
				"SELECT * FROM cell_captured_entry_deletion_receipt " +
					"WHERE logical_tracking_id = ? LIMIT 1",
				arrayOf(current.logicalTrackingId),
			).use { stored ->
				stored.moveToFirst() && stored.cellCapturedDeletionReceipt() == replacement
			},
		) { "Captured Cell deletion receipt re-epoch was not authenticated before commit" }
	}
}

private fun importedWifiEntryDeletion(
	sqlite: SupportSQLiteDatabase,
	entryIdentity: String,
): ImportedWifiEntryDeletionEntity? = sqlite.query(
	"SELECT * FROM imported_wifi_entry_deletion WHERE entry_identity = ? LIMIT 1",
	arrayOf(entryIdentity),
).use { cursor -> if (cursor.moveToFirst()) cursor.importedWifiEntryDeletion() else null }

private fun importedWifiRunDeletion(
	sqlite: SupportSQLiteDatabase,
	runIdentity: String,
): ImportedWifiDeletionGenerationEntity? = sqlite.query(
	"SELECT * FROM imported_wifi_deletion_generation WHERE run_identity = ? LIMIT 1",
	arrayOf(runIdentity),
).use { cursor -> if (cursor.moveToFirst()) cursor.importedWifiRunDeletion() else null }

private fun importedCellEntryDeletion(
	sqlite: SupportSQLiteDatabase,
	entryIdentity: String,
): ImportedCellEntryDeletionEntity? = sqlite.query(
	"SELECT * FROM imported_cell_entry_deletion WHERE entry_identity = ? LIMIT 1",
	arrayOf(entryIdentity),
).use { cursor -> if (cursor.moveToFirst()) cursor.importedCellEntryDeletion() else null }

private fun importedCellRunDeletion(
	sqlite: SupportSQLiteDatabase,
	runIdentity: String,
): ImportedCellDeletionGenerationEntity? = sqlite.query(
	"SELECT * FROM imported_cell_deletion_generation WHERE run_identity = ? LIMIT 1",
	arrayOf(runIdentity),
).use { cursor -> if (cursor.moveToFirst()) cursor.importedCellRunDeletion() else null }

private fun sourceDeletionFence(
	sqlite: SupportSQLiteDatabase,
	sourceKind: Int,
	scopeIdentityDigest: String,
): SourceDeletionFenceEntity? = sqlite.query(
	"SELECT * FROM source_deletion_fence WHERE source_kind = ? AND purpose = ? " +
		"AND scope_kind = ? AND scope_identity_digest = ? LIMIT 1",
	arrayOf(
		sourceKind,
		SessionManifestPurposeCode.SESSION_CAPTURE,
		SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeIdentityDigest,
	),
).use { cursor -> if (cursor.moveToFirst()) cursor.sourceDeletionFence() else null }

private inline fun SupportSQLiteDatabase.forEachBounded(
	sql: String,
	bindArgs: Array<out Any?> = emptyArray(),
	block: (Cursor) -> Unit,
) {
	var count = 0
	query(sql, bindArgs).use { cursor ->
		while (cursor.moveToNext()) {
			count = Math.addExact(count, 1)
			check(count <= ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS) {
				"Full-clear authority exceeds the shared source bound"
			}
			block(cursor)
		}
	}
}

private fun SupportSQLiteDatabase.executeUpdateExact(
	sql: String,
	vararg values: Any?,
) {
	compileStatement(sql).use { statement ->
		statement.bindAll(values)
		check(statement.executeUpdateDelete() == 1) { "Full-clear authority update lost ownership" }
	}
}

private fun SupportSQLiteDatabase.executeInsert(
	sql: String,
	vararg values: Any?,
) {
	compileStatement(sql).use { statement ->
		statement.bindAll(values)
		check(statement.executeInsert() != -1L) { "Full-clear authority insert failed" }
	}
}

private fun SupportSQLiteStatement.bindAll(values: Array<out Any?>) {
	values.forEachIndexed { index, value ->
		val position = index + 1
		when (value) {
			null -> bindNull(position)
			is String -> bindString(position, value)
			is Boolean -> bindLong(position, if (value) 1L else 0L)
			is Int -> bindLong(position, value.toLong())
			is Long -> bindLong(position, value)
			is Float -> bindDouble(position, value.toDouble())
			is Double -> bindDouble(position, value)
			else -> error("Unsupported SQLite binding type: ${value::class.java.name}")
		}
	}
}

private fun Cursor.sourceDeletionFence() = SourceDeletionFenceEntity(
	sourceKind = int("source_kind"),
	purpose = string("purpose"),
	scopeKind = string("scope_kind"),
	scopeIdentityDigest = string("scope_identity_digest"),
	fenceGeneration = long("fence_generation"),
	collectedDataEpoch = long("collected_data_epoch"),
	deletedAtMs = long("deleted_at_ms"),
	effectChecksum = string("effect_checksum"),
)

private fun Cursor.importedWifiEntryDeletion() = ImportedWifiEntryDeletionEntity(
	entryIdentity = string("entry_identity"),
	collectedDataEpoch = long("collected_data_epoch"),
	deletedImportRevision = long("deleted_import_revision"),
	deletedAtMs = long("deleted_at_ms"),
	effectChecksum = string("effect_checksum"),
)

private fun Cursor.wifiCaptureDeletionGeneration() = WifiCaptureDeletionGenerationEntity(
	logicalTrackingId = string("logical_tracking_id"),
	serviceRunId = string("service_run_id"),
	collectedDataEpoch = long("collected_data_epoch"),
	generation = long("generation"),
	updatedAtMs = long("updated_at_ms"),
)

private fun Cursor.importedWifiRunDeletion() = ImportedWifiDeletionGenerationEntity(
	runIdentity = string("run_identity"),
	entryIdentity = string("entry_identity"),
	deletionScopeDigest = string("deletion_scope_digest"),
	collectedDataEpoch = long("collected_data_epoch"),
	generation = long("generation"),
	deletedAtMs = long("deleted_at_ms"),
	effectChecksum = string("effect_checksum"),
)

private fun Cursor.importedWifiEntry() = ImportedWifiEntryRevisionEntity(
	identity = string("identity"),
	importRevision = long("import_revision"),
	supersedesImportRevision = nullableLong("supersedes_import_revision"),
	contentChecksum = string("content_checksum"),
	sourceFormat = string("source_format"),
	sourceSchemaVersion = int("source_schema_version"),
	sessionMode = string("session_mode"),
	startTimeMs = long("start_time_ms"),
	endTimeMs = long("end_time_ms"),
	collectedDataEpoch = long("collected_data_epoch"),
	importJobId = string("import_job_id"),
	importEntryKey = string("import_entry_key"),
	importSourceName = string("import_source_name"),
	receivedAtMs = long("received_at_ms"),
)

private fun Cursor.importedWifiRun() = ImportedWifiRunEntity(
	entryIdentity = string("entry_identity"),
	entryImportRevision = long("entry_import_revision"),
	identity = string("identity"),
	deletionScopeDigest = string("deletion_scope_digest"),
	contentChecksum = string("content_checksum"),
	startTimeMs = long("start_time_ms"),
	endTimeMs = long("end_time_ms"),
	captureCoverage = string("capture_coverage"),
	availability = string("availability"),
	acquisitionCompleteness = string("acquisition_completeness"),
	hasUnresolvedProviderRange = boolean("has_unresolved_provider_range"),
	retentionLoss = boolean("retention_loss"),
	collectedDataEpoch = long("collected_data_epoch"),
	scopeDeletionGeneration = long("scope_deletion_generation"),
)

private fun Cursor.wifiSelectedDeletionReceipt() = WifiSelectedDeletionReceiptEntity(
	selectionIdentity = string("selection_identity"),
	origin = string("origin"),
	collectedDataEpoch = long("collected_data_epoch"),
	selectedImportRevision = nullableLong("selected_import_revision"),
	selectedContentChecksum = nullableString("selected_content_checksum"),
	startTimeMs = long("start_time_ms"),
	endTimeMs = long("end_time_ms"),
	expectedRunCount = int("expected_run_count"),
	expectedObservationCount = int("expected_observation_count"),
	expectedProtectedIdentityCount = int("expected_protected_identity_count"),
	protectedIdentitySetChecksum = string("protected_identity_set_checksum"),
	runDeletionSetChecksum = string("run_deletion_set_checksum"),
	sourceFenceSetChecksum = string("source_fence_set_checksum"),
	retainedFromMs = nullableLong("retained_from_ms"),
	deletedAtMs = long("deleted_at_ms"),
	effectChecksum = string("effect_checksum"),
)

private fun Cursor.wifiSelectedProtectedIdentity() =
	WifiSelectedDeletionProtectedIdentityEntity(
		selectionIdentity = string("selection_identity"),
		receiptOrigin = string("receipt_origin"),
		identityKind = string("identity_kind"),
		protectedIdentity = string("protected_identity"),
		ownerEntryIdentity = string("owner_entry_identity"),
		ownerRunIdentity = nullableString("owner_run_identity"),
		deletionScopeDigest = nullableString("deletion_scope_digest"),
		aggregateOwnerIdentity = nullableString("aggregate_owner_identity"),
		aggregateOwnerSemanticRevision = nullableLong("aggregate_owner_semantic_revision"),
		revisionCount = int("revision_count"),
		revisionSetChecksum = string("revision_set_checksum"),
		collectedDataEpoch = long("collected_data_epoch"),
		effectChecksum = string("effect_checksum"),
	)

private fun Cursor.importedCellEntryDeletion() = ImportedCellEntryDeletionEntity(
	entryIdentity = string("entry_identity"),
	collectedDataEpoch = long("collected_data_epoch"),
	deletedImportRevision = long("deleted_import_revision"),
	deletedAtMs = long("deleted_at_ms"),
	effectChecksum = string("effect_checksum"),
)

private fun Cursor.cellCaptureDeletionGeneration() = CellCaptureDeletionGenerationEntity(
	logicalTrackingId = string("logical_tracking_id"),
	serviceRunId = string("service_run_id"),
	collectedDataEpoch = long("collected_data_epoch"),
	generation = long("generation"),
	updatedAtMs = long("updated_at_ms"),
)

private fun Cursor.importedCellRunDeletion() = ImportedCellDeletionGenerationEntity(
	runIdentity = string("run_identity"),
	entryIdentity = string("entry_identity"),
	deletionScopeDigest = string("deletion_scope_digest"),
	collectedDataEpoch = long("collected_data_epoch"),
	generation = long("generation"),
	deletedAtMs = long("deleted_at_ms"),
	effectChecksum = string("effect_checksum"),
)

private fun Cursor.importedCellEntry() = ImportedCellEntryRevisionEntity(
	identity = string("identity"),
	importRevision = long("import_revision"),
	supersedesImportRevision = nullableLong("supersedes_import_revision"),
	contentChecksum = string("content_checksum"),
	sourceFormat = string("source_format"),
	sourceSchemaVersion = int("source_schema_version"),
	sessionMode = string("session_mode"),
	startTimeMs = long("start_time_ms"),
	endTimeMs = long("end_time_ms"),
	subscriptionGrouping = string("subscription_grouping"),
	collectedDataEpoch = long("collected_data_epoch"),
	importJobId = string("import_job_id"),
	importEntryKey = string("import_entry_key"),
	importSourceName = string("import_source_name"),
	receivedAtMs = long("received_at_ms"),
)

private fun Cursor.importedCellRun() = ImportedCellRunEntity(
	entryIdentity = string("entry_identity"),
	entryImportRevision = long("entry_import_revision"),
	identity = string("identity"),
	deletionScopeDigest = string("deletion_scope_digest"),
	contentChecksum = string("content_checksum"),
	startTimeMs = long("start_time_ms"),
	endTimeMs = long("end_time_ms"),
	captureCoverage = string("capture_coverage"),
	availability = string("availability"),
	acquisitionCompleteness = string("acquisition_completeness"),
	retentionLoss = boolean("retention_loss"),
	subscriptionGrouping = string("subscription_grouping"),
	collectedDataEpoch = long("collected_data_epoch"),
	scopeDeletionGeneration = long("scope_deletion_generation"),
)

private fun Cursor.importedCellDeletionReceipt() = ImportedCellEntryDeletionReceiptEntity(
	entryIdentity = string("entry_identity"),
	collectedDataEpoch = long("collected_data_epoch"),
	deletedImportRevision = long("deleted_import_revision"),
	deletedContentChecksum = string("deleted_content_checksum"),
	sessionMode = string("session_mode"),
	subscriptionGrouping = string("subscription_grouping"),
	startTimeMs = long("start_time_ms"),
	endTimeMs = long("end_time_ms"),
	receivedAtMs = long("received_at_ms"),
	retainedFromMs = nullableLong("retained_from_ms"),
	expectedRevisionCount = int("expected_revision_count"),
	expectedReceiptCount = int("expected_receipt_count"),
	expectedRunCount = int("expected_run_count"),
	expectedObservationCount = int("expected_observation_count"),
	expectedProtectedIdentityCount = int("expected_protected_identity_count"),
	protectedIdentitySetChecksum = string("protected_identity_set_checksum"),
	deletedAtMs = long("deleted_at_ms"),
	effectChecksum = string("effect_checksum"),
)

private fun Cursor.importedCellDeletedIdentity() = ImportedCellDeletedIdentityEntity(
	protectedIdentity = string("protected_identity"),
	entryIdentity = string("entry_identity"),
	identityKind = string("identity_kind"),
	runIdentity = nullableString("run_identity"),
	aggregateOwnerIdentity = nullableString("aggregate_owner_identity"),
	contentChecksum = nullableString("content_checksum"),
	includedInLatest = boolean("included_in_latest"),
	observationOrdinal = nullableInt("observation_ordinal"),
	deletionScopeDigest = nullableString("deletion_scope_digest"),
	runStartTimeMs = nullableLong("run_start_time_ms"),
	runEndTimeMs = nullableLong("run_end_time_ms"),
	captureCoverage = nullableString("capture_coverage"),
	availability = nullableString("availability"),
	acquisitionCompleteness = nullableString("acquisition_completeness"),
	retentionLoss = nullableBoolean("retention_loss"),
	subscriptionGrouping = nullableString("subscription_grouping"),
	effectChecksum = string("effect_checksum"),
)

private fun Cursor.cellCapturedDeletionReceipt() = CellCapturedEntryDeletionReceiptEntity(
	logicalTrackingId = string("logical_tracking_id"),
	entryIdentity = string("entry_identity"),
	collectedDataEpoch = long("collected_data_epoch"),
	expectedRunCount = int("expected_run_count"),
	startTimeMs = long("start_time_ms"),
	endTimeMs = long("end_time_ms"),
	runFootprintSetChecksum = string("run_footprint_set_checksum"),
	retainedFromMs = nullableLong("retained_from_ms"),
	deletedAtMs = long("deleted_at_ms"),
	effectChecksum = string("effect_checksum"),
)

private fun Cursor.cellCapturedDeletedRun() = CellCapturedDeletedRunEntity(
	logicalTrackingId = string("logical_tracking_id"),
	serviceRunId = string("service_run_id"),
	sessionSegmentId = long("session_segment_id"),
	scopeIdentityDigest = string("scope_identity_digest"),
	startTimeMs = long("start_time_ms"),
	endTimeMs = long("end_time_ms"),
	collectedDataEpoch = long("collected_data_epoch"),
	generation = long("generation"),
	deletedAtMs = long("deleted_at_ms"),
	effectChecksum = string("effect_checksum"),
)

private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))

private fun Cursor.nullableString(name: String): String? =
	getColumnIndexOrThrow(name).let { index -> if (isNull(index)) null else getString(index) }

private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))

private fun Cursor.nullableLong(name: String): Long? =
	getColumnIndexOrThrow(name).let { index -> if (isNull(index)) null else getLong(index) }

private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))

private fun Cursor.nullableInt(name: String): Int? =
	getColumnIndexOrThrow(name).let { index -> if (isNull(index)) null else getInt(index) }

private fun Cursor.boolean(name: String): Boolean = int(name) != 0

private fun Cursor.nullableBoolean(name: String): Boolean? =
	getColumnIndexOrThrow(name).let { index -> if (isNull(index)) null else getInt(index) != 0 }
