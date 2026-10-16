package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.legacy.LEGACY_IMPORT_JOB_ID
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupStore
import com.adsamcik.tracker.shared.base.database.steps.imported.preserveStepsFullClearFences

/**
 * Deletes collected rows while atomically recording the durable lifecycle
 * transition that authorized the deletion. The singleton state row remains
 * after the clear so an in-flight writer carrying an older epoch cannot
 * repopulate the new database generation.
 */
suspend fun AppDatabase.Companion.deleteAllCollectedData(
	context: Context,
	collectedDataEpoch: Long,
	retainedFromMs: Long?,
	updatedAtMs: Long,
): CollectedDataDeletionOperationEntity = deleteAllCollectedData(
	context = context,
	operationId = "legacy-$collectedDataEpoch-$updatedAtMs",
	collectedDataEpoch = collectedDataEpoch,
	retainedFromMs = retainedFromMs,
	updatedAtMs = updatedAtMs,
)

/**
 * Opens the app database to perform the exact durable full-data deletion operation, preserving
 * required replay authority under the new epoch.
 */
suspend fun AppDatabase.Companion.deleteAllCollectedData(
	context: Context,
	operationId: String,
	collectedDataEpoch: Long,
	retainedFromMs: Long?,
	updatedAtMs: Long,
): CollectedDataDeletionOperationEntity {
	val database = database(context)
	val backupStore = DatabaseMigrationBackupStore(context)
	backupStore.markDeletionPending()
	val operation = deleteAllCollectedData(
		database,
		operationId,
		collectedDataEpoch,
		retainedFromMs,
		updatedAtMs,
	)
	backupStore.deleteAll()
	return operation
}

internal suspend fun AppDatabase.Companion.deleteAllCollectedData(
	database: AppDatabase,
	operationId: String,
	collectedDataEpoch: Long,
	retainedFromMs: Long?,
	updatedAtMs: Long,
): CollectedDataDeletionOperationEntity {
	database.withTransaction {
		database.cleanupImportedPortableStepsFullClearStagingInCurrentTransaction()
	}
	return database.withTransaction {
		database.collectedDataDeletionOperationDao().get(operationId)?.let { existing ->
			check(existing.targetCollectedDataEpoch == collectedDataEpoch)
			check(existing.retainedFromMs == retainedFromMs)
			check(existing.deletedAtMs == updatedAtMs)
			return@withTransaction existing
		}
		database.sourceEvidenceStateDao().ensure()
		val oldState = requireNotNull(database.sourceEvidenceStateDao().get())
		database.preserveAmbientStepsNativeReplayFootprintsForFullClearInCurrentTransaction(
			oldCollectedDataEpoch = oldState.collectedDataEpoch,
			newCollectedDataEpoch = collectedDataEpoch,
			protectedAtMs = updatedAtMs,
		)
		deleteAllCollectedDataInCurrentTransaction(
			database = database,
			operationId = operationId,
			oldState = oldState,
			newCollectedDataEpoch = collectedDataEpoch,
			retainedFromMs = retainedFromMs,
			updatedAtMs = updatedAtMs,
		)
	}
}

/** Runs the exact collected-data clear against a caller-owned Room test database. */
suspend fun AppDatabase.Companion.deleteAllCollectedDataForTesting(
	database: AppDatabase,
	operationId: String,
	collectedDataEpoch: Long,
	retainedFromMs: Long?,
	updatedAtMs: Long,
): CollectedDataDeletionOperationEntity = deleteAllCollectedData(
	database = database,
	operationId = operationId,
	collectedDataEpoch = collectedDataEpoch,
	retainedFromMs = retainedFromMs,
	updatedAtMs = updatedAtMs,
)

/**
 * Reads the durable deletion operation by identity from the app database, or returns null when
 * absent.
 */
suspend fun AppDatabase.Companion.readCollectedDataDeletionOperation(
	context: Context,
	operationId: String,
): CollectedDataDeletionOperationEntity? {
	val database = database(context)
	val operation = database.collectedDataDeletionOperationDao().get(operationId)
	if (operation != null) {
		DatabaseMigrationBackupStore(context).deleteAll()
	}
	return operation
}

internal suspend fun AppDatabase.Companion.deleteAllCollectedData(
	database: AppDatabase,
	collectedDataEpoch: Long,
	retainedFromMs: Long?,
	updatedAtMs: Long,
): CollectedDataDeletionOperationEntity = deleteAllCollectedData(
	database = database,
	operationId = "legacy-$collectedDataEpoch-$updatedAtMs",
	collectedDataEpoch = collectedDataEpoch,
	retainedFromMs = retainedFromMs,
	updatedAtMs = updatedAtMs,
)

internal suspend fun AppDatabase.Companion.deleteAllCollectedData(database: AppDatabase) {
	database.sourceEvidenceStateDao().ensure()
	val oldState = requireNotNull(database.sourceEvidenceStateDao().get())
	val updatedAtMs = System.currentTimeMillis()
	deleteAllCollectedData(
		database = database,
		operationId = "legacy-${oldState.collectedDataEpoch + 1L}-$updatedAtMs",
		collectedDataEpoch = Math.addExact(oldState.collectedDataEpoch, 1L),
		retainedFromMs = oldState.retainedFromMs,
		updatedAtMs = updatedAtMs,
	)
}

private suspend fun deleteAllCollectedDataInCurrentTransaction(
	database: AppDatabase,
	operationId: String,
	oldState: SourceEvidenceState,
	newCollectedDataEpoch: Long,
	retainedFromMs: Long?,
	updatedAtMs: Long,
): CollectedDataDeletionOperationEntity {
	require(operationId.isNotBlank())
	require(newCollectedDataEpoch > oldState.collectedDataEpoch)
	require(retainedFromMs == null || retainedFromMs >= 0L)
	require(updatedAtMs >= 0L)
	val nextRevision = Math.addExact(oldState.revision, 1L)
	val deletedSourceEventHighWaterOrdinal = maxOf(
		oldState.deletedSourceEventHighWaterOrdinal,
		sourceEventWalHighWater(database),
	)
	val nextRetainedFromMs = listOfNotNull(
		oldState.retainedFromMs,
		retainedFromMs,
	).maxOrNull()
	val sqlite = database.openHelper.writableDatabase
	val importedAmbientStepsDao = database.importedAmbientStepsDao()
	sqlite.preserveFullClearSourceFences(database, operationId, oldState,
		newCollectedDataEpoch, nextRetainedFromMs, updatedAtMs)
	clearAmbientSourcePayloads(database, importedAmbientStepsDao, oldState,
		newCollectedDataEpoch, nextRevision, updatedAtMs)
	oldState.publishFullDeletionState(
		database = database,
		newCollectedDataEpoch = newCollectedDataEpoch,
		newRevision = nextRevision,
		retainedFromMs = nextRetainedFromMs,
		deletedSourceEventHighWaterOrdinal = deletedSourceEventHighWaterOrdinal,
		updatedAtMs = updatedAtMs,
	)
	importedAmbientStepsDao.deleteFullClearPayloadInCurrentTransaction(
		newCollectedDataEpoch,
	)
	database.retentionWorkExecutionReceiptDao()
		.supersedeOpenExecutions(updatedAtMs)
	deleteCollectedRows(database)
	return CollectedDataDeletionOperationEntity(
		operationId = operationId,
		targetCollectedDataEpoch = newCollectedDataEpoch,
		retainedFromMs = nextRetainedFromMs,
		deletedAtMs = updatedAtMs,
		phase = CollectedDataDeletionOperationEntity.PHASE_DATABASE_CLEARED,
		updatedAtMs = updatedAtMs,
	).also { operation ->
		database.collectedDataDeletionOperationDao().deleteAllExcept(operationId)
		database.collectedDataDeletionOperationDao().insert(operation)
	}
}

private fun SourceEvidenceState.publishFullDeletionState(
	database: AppDatabase,
	newCollectedDataEpoch: Long,
	newRevision: Long,
	retainedFromMs: Long?,
	deletedSourceEventHighWaterOrdinal: Long,
	updatedAtMs: Long,
) {
	val oldState = this
	database.openHelper.writableDatabase.compileStatement(
		"UPDATE source_evidence_state SET revision = ?, collected_data_epoch = ?, " +
		"retained_from_ms = ?, restored_data_epochs = '', deleted_source_event_high_water_ordinal = ?, " +
			"updated_at_ms = ? WHERE id = ? AND revision = ? " +
			"AND collected_data_epoch = ?",
	).use { statement ->
		SimpleSQLiteQuery.bind(
			statement,
			arrayOf<Any?>(
				newRevision,
				newCollectedDataEpoch,
				retainedFromMs,
				deletedSourceEventHighWaterOrdinal,
				updatedAtMs,
				oldState.id.toLong(),
				oldState.revision,
				oldState.collectedDataEpoch,
			),
		)
		check(statement.executeUpdateDelete() == 1) {
			"Unable to publish full collected-data deletion"
		}
	}
}

private fun sourceEventWalHighWater(database: AppDatabase): Long {
	val sqlite = database.openHelper.writableDatabase
	return sqlite.query(
		"SELECT MAX(" +
			"COALESCE((SELECT seq FROM sqlite_sequence " +
			"WHERE name = 'source_event_wal'), 0), " +
			"COALESCE((SELECT MAX(admission_ordinal) FROM source_event_wal), 0))",
	).use { cursor ->
		check(cursor.moveToFirst()) { "Unable to read source-event WAL high-water" }
		cursor.getLong(0)
	}
}

private suspend fun deleteCollectedRows(database: AppDatabase) {
	// Organization metadata is collected data; a stale editor is fenced by the epoch.
	database.openHelper.writableDatabase.execSQL("DELETE FROM organization_annotation")
	database.legacyPreservedDataDao().deleteAllRejectedSessions()
	database.legacyPreservedDataDao().deleteAllWifiCounts()
	database.legacyPreservedDataDao().deleteAllNetworkOperators()
	deleteCollectedSourcePipelineRows(database)

	deleteCollectedSourceObservationRows(database)

	// Historical reconstruction products
	database.trajectoryReconstructionDao().deleteAll()

	// Exploration tables
	database.explorationCellDao().deleteAll()
	database.explorationStreakDao().deleteAll()
	database.achievementProgressDao().deleteAll()

	// Export and import audit records. Keep the legacy-import marker so the
	// retained v26 vault is not imported again after a collected-data clear.
	database.exportLogDao().deleteAll()
	database.importReceiptDao().deleteAllEntriesExcept(LEGACY_IMPORT_JOB_ID)
	database.importReceiptDao().deleteAllJobsExcept(LEGACY_IMPORT_JOB_ID)
	database.openHelper.writableDatabase.execSQL("DELETE FROM domain_event_cursor")
	database.openHelper.writableDatabase.execSQL("DELETE FROM domain_event")

	// Ski detection tables
	database.pressureSampleDao().deleteAll()
	database.importedPressureDao().deleteSourceEraseWitnesses()
	database.importedPressureDao().deleteSourceErase()
	database.skiRunSegmentDao().deleteAll()

	// Pending signal WAL
	database.pendingSignalDao().deleteAll()
	database.quarantinedSignalDao().deleteAll()
	// Game progression and minigames
	database.xpLedgerDao().deleteAll()
	database.playerProfileDao().deleteAll()
	database.miniGameScoreDao().deleteAll()
}

private suspend fun deleteCollectedSourcePipelineRows(database: AppDatabase) {
	// Source-event pipeline. Delete dependent state before immutable evidence.
	database.locationProjectionDao().deleteAllObservations()
	database.openHelper.writableDatabase.execSQL("DELETE FROM source_capture_admission_barrier")
	database.openHelper.writableDatabase.execSQL("DELETE FROM source_run_retirement")
	database.sourceBrokerDao().deleteAllAuthorizations()
	database.sourceBrokerDao().deleteAllRegistrations()
	database.sourceBrokerDao().deleteAllDemands()
	database.sourceProjectionStateDao().deleteAllProductLanes()
	database.sourceProjectionStateDao().deleteAllJoinState()
	database.sourceProjectionStateDao().deleteAllOutbox()
	database.sourceProjectionStateDao().deleteAllFailures()
	database.sourceProjectionStateDao().deleteAllCheckpoints()
	database.sourceProjectionStateDao().deleteAllRegistrations()
	database.sourceProjectionStateDao().deleteAllLeases()
	database.legacyV27ProjectionDrainDao().deleteAllTargets()
	database.legacyV27ProjectionDrainDao().deleteDrain()
	database.sourceRegistrationStateDao().deleteAll()
	database.sourcePlanStateDao().deleteAllAppliedStates()
	database.sourceRuntimeStateDao().deleteAll()
	database.sourceSessionDao().deleteAllCompleteness()
	database.activityAutomaticStartActionDao().deleteAll()
	database.sourceSessionDao().deleteAllLifecycleActions()
	database.sourceSessionDao().deleteAllLifecycleIntents()
	database.sourceSessionDao().deleteAllManifestSources()
	database.sourceSessionDao().deleteAllManifests()
	database.sourceSessionDao().deleteAllServiceRuns()
	database.sourceSessionDao().deleteAllSessions()
	database.sourceCallerAuthorityDao().deleteAll()
	database.sourceEventWalDao().deleteAll()
	database.sourceEventRetentionProofDao().deleteAll()
	database.sourceEventRetentionScopeProofDao().deleteAll()
	database.capturedRadioFactRetentionProofDao().deleteAll()
}

private suspend fun deleteCollectedSourceObservationRows(database: AppDatabase) {
	// Sessionless architecture tables
	database.locationFileIdentityDao().deleteAllArchives()
	database.locationSampleDao().deleteAll()
	database.importedRadioFileMembershipDao().clear()
	database.locationObservationDao().deleteAll()
	database.locationObservationDecisionDao().deleteAll()
	// Payload-free source fences deliberately survive repeated full clears. Imported
	// Ambient Steps payload was authenticated and removed before this common cascade; its
	// day fences, protected identities, and source fence remain as deletion authority.
	database.stepFactRevisionDao().deleteAll()
	database.ambientStepsFactRevisionDao().deleteAllRetentionAuthorities()
	database.ambientStepsFactRevisionDao().deleteAll()
	database.ambientStepsImportStateDao().deleteAllAuthorityTransitions()
	database.ambientStepsImportStateDao().deleteAllGaps()
	database.ambientStepsImportStateDao().deleteAllCursors()
	database.stepsGoalEffectDao().deleteAll()
	database.stepsGoalRepairDayDao().deleteAll()
	database.importedStepsDao().deleteAll()
	database.cleanupImportedPortableStepsFullClearStagingInCurrentTransaction()
	database.importedPressureDao().deleteAllReceipts()
	database.importedPressureDao().deleteAllEntries()
	database.importedPressureDao().deleteAllRetainedIdentities()
	database.importedPressureDao().deleteAllRetentionReceipts()
	database.importedActivityDao().deleteAllReceipts()
	database.importedActivityDao().deleteAllEntries()
	database.importedActivityDao().deleteAllRetainedIdentities()
	database.importedActivityDao().deleteAllRetentionReceipts()
	database.importedActivityDao().deleteAllEntryDeletionReceipts()
	database.importedActivityDao().deleteAllEntryDeletions()
	database.importedActivityDao().deleteAllDeletionGenerations()
	database.pressureFactRetentionAuthorityDao().deleteAll()
	database.pressureFactRevisionDao().deleteAll()
	database.activityCapturedFactDao().deleteAllEvidence()
	database.activityCapturedFactDao().deleteAllFragments()
	database.activityCapturedFactDao().deleteAllCursors()
	database.activityCapturedFactDao().deleteAllRevisions()
	database.nativePortableOwnershipDao().deleteAll()
	database.activityCapturedFactDao().deleteAllRegistrationPlanBindings()
	deleteCollectedRadioSourceRows(database)
	database.stepIntervalDao().deleteAll()
	database.activitySnapshotDao().deleteAll()
	database.radioObservationChildDao().deleteAllLegacyCell()
	database.radioObservationChildDao().deleteAllLegacyWifi()
	database.cellSampleDao().deleteAll()
	database.wifiObservationDao().deleteAll()
	database.trackerRunDao().deleteAll()
	database.trackerStateEventDao().deleteAll()
	database.sessionSegmentDao().deleteAll()
	database.dailySummaryDao().deleteAll()
	database.liveStatsDao().deleteAll()
}

private suspend fun SupportSQLiteDatabase.preserveFullClearSourceFences(
	database: AppDatabase, operationId: String, oldState: SourceEvidenceState,
	newCollectedDataEpoch: Long, nextRetainedFromMs: Long?, updatedAtMs: Long,
) {
	val sqlite = this
	if (sqlite.hasLegacyV27StepsWalForCollectedDataFullClear()) {
		val legacyStepsFullClearFence = LegacyV27StepsWalFullClearFence.establish(
			database = sqlite,
			operationId = operationId,
			oldCollectedDataEpoch = oldState.collectedDataEpoch,
			newCollectedDataEpoch = newCollectedDataEpoch,
			deletedAtMs = updatedAtMs,
		)
		sqlite.authenticateLegacyV27StepsWalForCollectedDataFullClear(
			legacyStepsFullClearFence,
		)
	}
	// Reconcile imported owners while their retained source entries are still live.
	// Installing source-deletion fences first would make the reader report DELETION.
	database.preserveImportedPortableCountDomainFullClearFences(
		operationId = operationId,
		oldCollectedDataEpoch = oldState.collectedDataEpoch,
		newCollectedDataEpoch = newCollectedDataEpoch,
		fencedAtMs = updatedAtMs,
	)

	preserveImportedPressureFullClearAuthority(
		sqlite = sqlite,
		expectedCollectedDataEpoch = oldState.collectedDataEpoch,
		fencedAtMs = updatedAtMs,
	)
	preserveStepsFullClearFences(
		sqlite = sqlite,
		oldCollectedDataEpoch = oldState.collectedDataEpoch,
		newCollectedDataEpoch = newCollectedDataEpoch,
		deletedAtMs = updatedAtMs,
	)
	preserveWifiFullClearAuthority(
		sqlite = sqlite,
		oldCollectedDataEpoch = oldState.collectedDataEpoch,
		newCollectedDataEpoch = newCollectedDataEpoch,
		newRetainedFromMs = nextRetainedFromMs,
		clearedAtMs = updatedAtMs,
	)
	preserveCellFullClearAuthority(
		database = database,
		sqlite = sqlite,
		oldCollectedDataEpoch = oldState.collectedDataEpoch,
		newCollectedDataEpoch = newCollectedDataEpoch,
		newRetainedFromMs = nextRetainedFromMs,
		clearedAtMs = updatedAtMs,
	)
}

private suspend fun clearAmbientSourcePayloads(
	database: AppDatabase, importedAmbientStepsDao: ImportedAmbientStepsDao, oldState: SourceEvidenceState,
	newCollectedDataEpoch: Long, nextRevision: Long, updatedAtMs: Long,
) {
	database.radioObservationChildDao().deleteAllWifiBackground()
	database.radioObservationChildDao().deleteAllCellBackground()
	database.importedRadioChildDao().deleteAllWifiBackground()
	database.importedRadioChildDao().deleteAllCellBackground()
	check(
		database.clearAmbientWifiProductInCurrentFullClearTransaction(
			oldState.collectedDataEpoch,
			updatedAtMs,
		) is AmbientWifiDeletionResult.Deleted,
	) { "Ambient Wi-Fi full clear could not preserve replay authority" }
	check(
		database.clearAmbientCellProductInCurrentFullClearTransaction(
			oldState.collectedDataEpoch,
			updatedAtMs,
		) is AmbientCellDeletionResult.Deleted,
	) { "Ambient Cell full clear could not preserve replay authority" }
	importedAmbientStepsDao.prepareFullClearFencesInCurrentTransaction(
		oldCollectedDataEpoch = oldState.collectedDataEpoch,
		newCollectedDataEpoch = newCollectedDataEpoch,
		sourceEvidenceRevision = nextRevision,
		clearedAtMs = updatedAtMs,
		retainedState = oldState,
	)
	check(
		clearStepsCountDomainEvidenceInCurrentTransaction(
			database,
			StepsCountDomainFullClearMode.PRESERVE_TERMINAL,
		) is StepsCountDomainMaintenanceResult.Applied,
	) { "Steps count-domain full clear could not preserve terminal authority" }
}

private suspend fun deleteCollectedRadioSourceRows(database: AppDatabase) {
	database.cellCapturedFactDao().deleteAllCursors()
	database.radioObservationChildDao().deleteAllCellSession()
	database.cellCapturedFactDao().deleteAllRevisions()
	database.importedRadioChildDao().deleteAllCellSession()
	database.importedCellDao().deleteAllReceipts()
	database.importedCellDao().deleteAllEntries()
	database.wifiCapturedFactDao().deleteAllCursors()
	database.radioObservationChildDao().deleteAllWifiSession()
	// Wi-Fi coverage revisions reference aggregate owners through an immediate RESTRICT FK.
	database.wifiCapturedFactDao().deleteAllDependentRevisions()
	database.wifiCapturedFactDao().deleteAllRevisions()
	database.importedRadioChildDao().deleteAllWifiSession()
	database.importedWifiDao().deleteAllReceipts()
	database.importedWifiDao().deleteAllEntryRevisions()
}
