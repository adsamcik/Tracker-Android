package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureIdentityFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureSourceEraseEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureSourceEraseWitnessEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureWindowEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import java.time.ZoneId

data class ImportedPressureFullClearPreservationResult(
	val liveEntryCount: Int,
	val retainedEntryCount: Int,
	val deletionEntryCount: Int,
	val identityFenceCount: Int,
)

/**
 * Preserves all imported Pressure semantic identities before either full-clear overload advances
 * the collected-data epoch or removes source payload/provenance.
 *
 * The caller must own the database transaction. Call this before `recordFullDeletion` (or the raw
 * epoch UPDATE), then delete live/retained payload, preserve entry/run tombstones and typed identity
 * fences, delete source-erase witnesses, and delete the source-erase receipt last.
 */
fun preserveImportedPressureFullClearAuthority(
	sqlite: SupportSQLiteDatabase,
	expectedCollectedDataEpoch: Long,
	fencedAtMs: Long,
): ImportedPressureFullClearPreservationResult {
	require(expectedCollectedDataEpoch >= 0L && fencedAtMs >= 0L)
	val evidenceEpoch = sqlite.query(
		"SELECT collected_data_epoch FROM source_evidence_state WHERE id = 1",
	).use { cursor ->
		check(cursor.moveToFirst()) { "Imported Pressure full clear requires evidence authority" }
		cursor.getLong(0)
	}
	check(evidenceEpoch == expectedCollectedDataEpoch) {
		"Imported Pressure full clear must authenticate the old epoch before advancing it"
	}
	val globalLiveFootprint = readGlobalLineageFootprint(sqlite)
	globalLiveFootprint.requireGlobalWithinBounds()
	val globalRetainedFootprint = readGlobalRetainedFootprint(sqlite)
	globalRetainedFootprint.requireGlobalWithinBounds()
	val privacyBytes = readGlobalPrivacyAuthorityBytes(sqlite)
	check(
		Math.addExact(
			Math.addExact(
				globalLiveFootprint.totalTextBytes,
				globalRetainedFootprint.totalTextBytes,
			),
			privacyBytes,
		) <= MAX_MAINTENANCE_TEXT_BYTES,
	)

	var liveCount = 0
	var retainedCount = 0
	var deletedCount = 0
	var totalTextBytes = 0L
	var ownerRowId = 0L
	while (true) {
		val nextOwnerRowId = sqlite.query(
			"SELECT MIN(rowid) FROM imported_pressure_entry_revision GROUP BY identity " +
				"HAVING MIN(rowid) > ? ORDER BY MIN(rowid) LIMIT 1",
			arrayOf(ownerRowId),
		).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null } ?: break
		check(nextOwnerRowId > ownerRowId)
		val footprint = readLineageFootprint(sqlite, nextOwnerRowId)
		footprint.requireWithinBounds()
		val identity = sqlite.query(
			"SELECT identity FROM imported_pressure_entry_revision WHERE rowid = ?",
			arrayOf(nextOwnerRowId),
		).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
		ownerRowId = nextOwnerRowId
		val lineage = loadAndAuthenticateLiveLineage(
			sqlite,
			identity,
			expectedCollectedDataEpoch,
			footprint,
		)
		totalTextBytes = checkedMaintenanceBytes(totalTextBytes, lineage.textBytes)
		insertOrAuthenticateIdentityFences(
			sqlite,
			lineage.identityFences(
				expectedCollectedDataEpoch,
				fencedAtMs,
				ImportedPressureIdentityFenceEntity.REASON_FULL_CLEAR,
			),
		)
		liveCount = Math.addExact(liveCount, 1)
		check(liveCount <= MAX_SOURCE_ENTRIES)
	}

	ownerRowId = 0L
	while (true) {
		val nextOwnerRowId = sqlite.query(
			"SELECT rowid FROM imported_pressure_retention_receipt WHERE rowid > ? " +
				"ORDER BY rowid LIMIT 1",
			arrayOf(ownerRowId),
		).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null } ?: break
		check(nextOwnerRowId > ownerRowId)
		val footprint = readRetainedFootprint(sqlite, nextOwnerRowId)
		footprint.requireWithinBounds()
		val identity = sqlite.query(
			"SELECT entry_identity FROM imported_pressure_retention_receipt WHERE rowid = ?",
			arrayOf(nextOwnerRowId),
		).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
		ownerRowId = nextOwnerRowId
		val retained = loadAndAuthenticateRetainedLineage(
			sqlite,
			identity,
			expectedCollectedDataEpoch,
			footprint,
		)
		totalTextBytes = checkedMaintenanceBytes(totalTextBytes, retained.textBytes)
		retainedCount = Math.addExact(retainedCount, 1)
		check(retainedCount <= MAX_SOURCE_ENTRIES)
	}

	var cursorIdentity: String? = null
	while (true) {
		val identity = sqlite.query(
			"SELECT entry_identity FROM imported_pressure_entry_deletion " +
				"WHERE (? IS NULL OR entry_identity > ?) ORDER BY entry_identity LIMIT 1",
			arrayOf(cursorIdentity, cursorIdentity),
		).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: break
		check(cursorIdentity == null || identity > requireNotNull(cursorIdentity))
		cursorIdentity = identity
		loadAndAuthenticateEntryDeletion(sqlite, identity)
		deletedCount = Math.addExact(deletedCount, 1)
		check(deletedCount <= MAX_DELETION_ENTRIES)
	}
	authenticateSourceEraseReceipt(sqlite, expectedCollectedDataEpoch)
	var fenceCount = 0
	cursorIdentity = null
	while (true) {
		val identity = sqlite.query(
			"SELECT protected_identity FROM imported_pressure_identity_fence " +
				"WHERE (? IS NULL OR protected_identity > ?) " +
				"ORDER BY protected_identity LIMIT 1",
			arrayOf(cursorIdentity, cursorIdentity),
		).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: break
		check(cursorIdentity == null || identity > requireNotNull(cursorIdentity))
		cursorIdentity = identity
		loadIdentityFence(sqlite, identity)
		fenceCount = Math.addExact(fenceCount, 1)
		check(fenceCount <= MAX_IDENTITY_FENCES)
	}
	var runDeletionCount = 0
	cursorIdentity = null
	while (true) {
		val identity = sqlite.query(
			"SELECT run_identity FROM imported_pressure_deletion_generation " +
				"WHERE (? IS NULL OR run_identity > ?) ORDER BY run_identity LIMIT 1",
			arrayOf(cursorIdentity, cursorIdentity),
		).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: break
		check(cursorIdentity == null || identity > requireNotNull(cursorIdentity))
		cursorIdentity = identity
		checkNotNull(loadRunDeletion(sqlite, identity))
		runDeletionCount = Math.addExact(runDeletionCount, 1)
		check(runDeletionCount <= MAX_RUN_DELETIONS)
	}
	return ImportedPressureFullClearPreservationResult(
		liveEntryCount = liveCount,
		retainedEntryCount = retainedCount,
		deletionEntryCount = deletedCount,
		identityFenceCount = fenceCount,
	)
}

private fun loadAndAuthenticateLiveLineage(
	sqlite: SupportSQLiteDatabase,
	identity: String,
	expectedEpoch: Long,
	footprint: FullClearLineageFootprint,
): FullClearLineage {
	val headers = sqlite.query(
		"SELECT identity, import_revision, supersedes_import_revision, content_checksum, " +
			"source_format, source_schema_version, start_time_ms, end_time_ms, " +
			"collected_data_epoch, import_job_id, import_entry_key, import_source_name, " +
			"received_at_ms FROM imported_pressure_entry_revision WHERE identity = ? " +
			"ORDER BY import_revision LIMIT ?",
		arrayOf(identity, MAX_REVISIONS + 1),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					ImportedPressureEntryRevisionEntity(
						identity = cursor.getString(0),
						importRevision = cursor.getLong(1),
						supersedesImportRevision =
							if (cursor.isNull(2)) null else cursor.getLong(2),
						contentChecksum = cursor.getString(3),
						sourceFormat = cursor.getString(4),
						sourceSchemaVersion = cursor.getInt(5),
						startTimeMs = cursor.getLong(6),
						endTimeMs = cursor.getLong(7),
						collectedDataEpoch = cursor.getLong(8),
						importJobId = cursor.getString(9),
						importEntryKey = cursor.getString(10),
						importSourceName = cursor.getString(11),
						receivedAtMs = cursor.getLong(12),
					),
				)
			}
		}
	}
	check(headers.size.toLong() == footprint.headerCount && headers.isNotEmpty())
	headers.forEachIndexed { index, header ->
		val revision = index.toLong() + 1L
		check(header.identity == identity && header.importRevision == revision)
		check(header.supersedesImportRevision == if (revision == 1L) null else revision - 1L)
		check(header.collectedDataEpoch == expectedEpoch)
	}
	check(headers.zipWithNext().all { (previous, next) ->
		next.receivedAtMs >= previous.receivedAtMs
	})
	val receipts = sqlite.query(
		"SELECT import_job_id, import_entry_key, import_source_name, received_at_ms, " +
			"entry_identity, entry_import_revision, entry_content_checksum, collected_data_epoch " +
			"FROM imported_pressure_receipt WHERE entry_identity = ? " +
			"ORDER BY entry_import_revision, import_job_id, import_entry_key LIMIT ?",
		arrayOf(identity, MAX_RECEIPTS + 1),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					ImportedPressureReceiptEntity(
						importJobId = cursor.getString(0),
						importEntryKey = cursor.getString(1),
						importSourceName = cursor.getString(2),
						receivedAtMs = cursor.getLong(3),
						entryIdentity = cursor.getString(4),
						entryImportRevision = cursor.getLong(5),
						entryContentChecksum = cursor.getString(6),
						collectedDataEpoch = cursor.getLong(7),
					),
				)
			}
		}
	}
	check(receipts.size.toLong() == footprint.receiptCount)
	check(receipts.map { it.importJobId to it.importEntryKey }.distinct().size == receipts.size)
	headers.forEach { header ->
		check(receipts.any {
			it.importJobId == header.importJobId &&
				it.importEntryKey == header.importEntryKey &&
				it.importSourceName == header.importSourceName &&
				it.receivedAtMs == header.receivedAtMs &&
				it.entryIdentity == header.identity &&
				it.entryImportRevision == header.importRevision &&
				it.entryContentChecksum == header.contentChecksum &&
				it.collectedDataEpoch == header.collectedDataEpoch
		})
	}
	val runs = loadRuns(sqlite, identity, footprint.runCount)
	val windows = loadWindows(sqlite, identity, footprint.windowCount)
	val lineage = ImportedPressureLineageAuthenticator.authenticate(
		identity = identity,
		expectedCollectedDataEpoch = expectedEpoch,
		headers = headers,
		receipts = receipts,
		runs = runs,
		windows = windows,
	)
	val owners = linkedMapOf<String, FullClearIdentityOwner>()
	fun bind(value: String, owner: FullClearIdentityOwner) {
		val previous = owners.putIfAbsent(value, owner)
		check(previous == null || previous == owner)
	}
	bind(identity, FullClearIdentityOwner(ImportedPressureIdentityFenceEntity.ENTRY, identity, null))
	lineage.revisions.forEach { revision ->
		revision.entry.runs.forEach { run ->
			bind(
				run.identity.value,
				FullClearIdentityOwner(ImportedPressureIdentityFenceEntity.RUN, identity, run.identity.value),
			)
			run.windows.forEach { window ->
				bind(
					window.identity.value,
					FullClearIdentityOwner(ImportedPressureIdentityFenceEntity.WINDOW, identity, run.identity.value),
				)
			}
		}
	}
	return FullClearLineage(owners, footprint.totalTextBytes)
}

private fun loadRuns(
	sqlite: SupportSQLiteDatabase,
	identity: String,
	expectedCount: Long,
): List<ImportedPressureRunEntity> = sqlite.query(
	"SELECT entry_identity, entry_import_revision, identity, start_time_ms, end_time_ms, " +
		"captured_for_whole_run, availability, coverage, retention_loss, collected_data_epoch, " +
		"scope_deletion_generation FROM imported_pressure_run WHERE entry_identity = ? " +
		"ORDER BY entry_import_revision, start_time_ms, identity LIMIT ?",
	arrayOf(identity, MAX_RUN_ROWS + 1),
).use { cursor ->
	buildList {
		while (cursor.moveToNext()) {
			add(
				ImportedPressureRunEntity(
					entryIdentity = cursor.getString(0),
					entryImportRevision = cursor.getLong(1),
					identity = cursor.getString(2),
					startTimeMs = cursor.getLong(3),
					endTimeMs = cursor.getLong(4),
					capturedForWholeRun = cursor.getInt(5) != 0,
					availability = cursor.getString(6),
					coverage = cursor.getString(7),
					retentionLoss = cursor.getInt(8) != 0,
					collectedDataEpoch = cursor.getLong(9),
					scopeDeletionGeneration = cursor.getLong(10),
				),
			)
		}
	}.also { check(it.size.toLong() == expectedCount) }
}

@Suppress("LongMethod")
private fun loadWindows(
	sqlite: SupportSQLiteDatabase,
	identity: String,
	expectedCount: Long,
): List<ImportedPressureWindowEntity> = sqlite.query(
	"SELECT entry_identity, entry_import_revision, run_identity, identity, content_checksum, " +
		"interval_start_time_ms, interval_end_time_ms, wall_time_uncertainty_ms, " +
		"observed_duration_nanos, sample_count, expected_sample_count, mean_hectopascals, " +
		"sum_squared_deviations, minimum_hectopascals, maximum_hectopascals, first_hectopascals, " +
		"latest_hectopascals, slope_hectopascals_per_second, r_squared, sensor_accuracy, " +
		"effective_sample_period_micros, effective_maximum_report_latency_micros, " +
		"target_window_duration_nanos, maximum_inter_sample_gap_nanos, closure_kind, qualification, " +
		"source_quality_flags, source_quality_confidence, stored_zone_id " +
		"FROM imported_pressure_window WHERE entry_identity = ? " +
		"ORDER BY entry_import_revision, run_identity, interval_start_time_ms, identity LIMIT ?",
	arrayOf(identity, MAX_WINDOW_ROWS + 1),
).use { cursor ->
	buildList {
		while (cursor.moveToNext()) {
			add(
				ImportedPressureWindowEntity(
					entryIdentity = cursor.getString(0),
					entryImportRevision = cursor.getLong(1),
					runIdentity = cursor.getString(2),
					identity = cursor.getString(3),
					contentChecksum = cursor.getString(4),
					intervalStartTimeMs = cursor.getLong(5),
					intervalEndTimeMs = cursor.getLong(6),
					wallTimeUncertaintyMs = cursor.getLong(7),
					observedDurationNanos = cursor.getLong(8),
					sampleCount = cursor.getInt(9),
					expectedSampleCount = cursor.getInt(10),
					meanHectopascals = cursor.getDouble(11),
					sumSquaredDeviations = cursor.getDouble(12),
					minimumHectopascals = cursor.getFloat(13),
					maximumHectopascals = cursor.getFloat(14),
					firstHectopascals = cursor.getFloat(15),
					latestHectopascals = cursor.getFloat(16),
					slopeHectopascalsPerSecond =
						if (cursor.isNull(17)) null else cursor.getDouble(17),
					rSquared = if (cursor.isNull(18)) null else cursor.getDouble(18),
					sensorAccuracy = cursor.getString(19),
					effectiveSamplePeriodMicros = cursor.getInt(20),
					effectiveMaximumReportLatencyMicros = cursor.getInt(21),
					targetWindowDurationNanos = cursor.getLong(22),
					maximumInterSampleGapNanos = cursor.getLong(23),
					closureKind = cursor.getString(24),
					qualification = cursor.getString(25),
					sourceQualityFlags = cursor.getLong(26),
					sourceQualityConfidence =
						if (cursor.isNull(27)) null else cursor.getFloat(27),
					storedZoneId = cursor.getString(28).also { ZoneId.of(it) },
				),
			)
		}
	}.also { check(it.size.toLong() == expectedCount) }
}

private fun loadAndAuthenticateRetainedLineage(
	sqlite: SupportSQLiteDatabase,
	identity: String,
	expectedEpoch: Long,
	footprint: FullClearRetainedFootprint,
): FullClearRetainedLineage {
	val receipt = sqlite.query(
		"SELECT entry_identity, collected_data_epoch, source_evidence_revision, retained_from_ms, " +
			"retained_at_ms, latest_import_revision, latest_content_checksum, start_time_ms, " +
			"end_time_ms, received_at_ms, recency_start_time_ms, recency_end_time_ms, " +
			"recency_tie_identity, revision_count, import_receipt_count, run_row_count, " +
			"window_row_count, run_deletion_count, run_deletion_set_checksum, " +
			"protected_identity_count, protected_identity_set_checksum, identity_fence_set_checksum, " +
			"lineage_authority_checksum, effect_checksum FROM imported_pressure_retention_receipt " +
			"WHERE entry_identity = ?",
		arrayOf(identity),
	).use { cursor ->
		check(cursor.moveToFirst())
		ImportedPressureRetentionReceiptEntity(
			entryIdentity = cursor.getString(0),
			collectedDataEpoch = cursor.getLong(1),
			sourceEvidenceRevision = cursor.getLong(2),
			retainedFromMs = cursor.getLong(3),
			retainedAtMs = cursor.getLong(4),
			latestImportRevision = cursor.getLong(5),
			latestContentChecksum = cursor.getString(6),
			startTimeMs = cursor.getLong(7),
			endTimeMs = cursor.getLong(8),
			receivedAtMs = cursor.getLong(9),
			recencyStartTimeMs = cursor.getLong(10),
			recencyEndTimeMs = cursor.getLong(11),
			recencyTieIdentity = cursor.getString(12),
			revisionCount = cursor.getInt(13),
			importReceiptCount = cursor.getInt(14),
			runRowCount = cursor.getInt(15),
			windowRowCount = cursor.getInt(16),
			runDeletionCount = cursor.getInt(17),
			runDeletionSetChecksum = cursor.getString(18),
			protectedIdentityCount = cursor.getInt(19),
			protectedIdentitySetChecksum = cursor.getString(20),
			identityFenceSetChecksum = cursor.getString(21),
			lineageAuthorityChecksum = cursor.getString(22),
			effectChecksum = cursor.getString(23),
		)
	}
	check(receipt.collectedDataEpoch == expectedEpoch)
	check(footprint.markerCount == receipt.protectedIdentityCount.toLong())
	val markers = sqlite.query(
		"SELECT protected_identity, entry_identity, identity_kind " +
			"FROM imported_pressure_retained_identity WHERE entry_identity = ? " +
			"ORDER BY identity_kind, protected_identity LIMIT ?",
		arrayOf(identity, receipt.protectedIdentityCount + 1),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					ImportedPressureRetainedIdentityEntity(
						cursor.getString(0),
						cursor.getString(1),
						cursor.getString(2),
					),
				)
			}
		}
	}
	check(receipt.authenticates(markers))
	val fences = markers.map { marker ->
		loadIdentityFence(sqlite, marker.protectedIdentity)
	}
	check(ImportedPressureIdentityFenceEntity.checksumSet(fences) == receipt.identityFenceSetChecksum)
	val runDeletions = markers.filter {
		it.identityKind == ImportedPressureRetainedIdentityEntity.RUN_SCOPE
	}.mapNotNull { loadRunDeletion(sqlite, it.protectedIdentity) }
	check(runDeletions.size == receipt.runDeletionCount)
	check(
		ImportedPressureRetentionReceiptEntity.checksumRunDeletions(runDeletions) ==
			receipt.runDeletionSetChecksum,
	)
	return FullClearRetainedLineage(footprint.totalTextBytes)
}

private fun loadAndAuthenticateEntryDeletion(
	sqlite: SupportSQLiteDatabase,
	identity: String,
): ImportedPressureEntryDeletionEntity {
	val deletion = sqlite.query(
		"SELECT entry_identity, collected_data_epoch, deleted_import_revision, deleted_at_ms, " +
			"run_deletion_count, run_deletion_set_checksum, identity_fence_count, " +
			"identity_fence_set_checksum, effect_checksum FROM imported_pressure_entry_deletion " +
			"WHERE entry_identity = ?",
		arrayOf(identity),
	).use { cursor ->
		check(cursor.moveToFirst())
		ImportedPressureEntryDeletionEntity(
			entryIdentity = cursor.getString(0),
			collectedDataEpoch = cursor.getLong(1),
			deletedImportRevision = cursor.getLong(2),
			deletedAtMs = cursor.getLong(3),
			runDeletionCount = cursor.getInt(4),
			runDeletionSetChecksum = cursor.getString(5),
			identityFenceCount = cursor.getInt(6),
			identityFenceSetChecksum = cursor.getString(7),
			effectChecksum = cursor.getString(8),
		)
	}
	val fences = sqlite.query(
		"SELECT protected_identity FROM imported_pressure_identity_fence " +
			"WHERE entry_identity = ? ORDER BY protected_identity LIMIT ?",
		arrayOf(identity, deletion.identityFenceCount + 1),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(loadIdentityFence(sqlite, cursor.getString(0)))
		}
	}
	check(fences.size == deletion.identityFenceCount)
	check(ImportedPressureIdentityFenceEntity.checksumSet(fences) == deletion.identityFenceSetChecksum)
	val runDeletions = fences.filter {
		it.identityKind == ImportedPressureIdentityFenceEntity.RUN
	}.mapNotNull { loadRunDeletion(sqlite, it.protectedIdentity) }
	check(runDeletions.size == deletion.runDeletionCount)
	check(
		ImportedPressureRetentionReceiptEntity.checksumRunDeletions(runDeletions) ==
			deletion.runDeletionSetChecksum,
	)
	return deletion
}

private fun authenticateSourceEraseReceipt(
	sqlite: SupportSQLiteDatabase,
	expectedEpoch: Long,
) {
	val markerCount = sqlite.query(
		"SELECT COUNT(*) FROM imported_pressure_source_erase",
	).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
	check(markerCount in 0L..1L)
	if (markerCount == 0L) {
		check(sqlite.query("SELECT COUNT(*) FROM imported_pressure_source_erase_witness").use {
			check(it.moveToFirst())
			it.getLong(0)
		} == 0L)
		return
	}
	val marker = sqlite.query(
		"SELECT id, collected_data_epoch, source_evidence_revision, erased_at_ms, " +
			"provider_registration_generation, legacy_write_fence_generation, " +
			"local_fact_revision_count, local_wal_event_count, legacy_sample_count, " +
			"legacy_sample_set_checksum, imported_entry_count, " +
			"imported_revision_count, imported_run_count, imported_window_count, " +
			"fenced_local_run_count, local_scope_set_checksum, entry_deletion_count, " +
			"entry_deletion_set_checksum, run_deletion_count, run_deletion_set_checksum, " +
			"identity_fence_count, identity_fence_set_checksum, effect_checksum " +
			"FROM imported_pressure_source_erase WHERE id = 1",
	).use { cursor ->
		check(cursor.moveToFirst())
		ImportedPressureSourceEraseEntity(
			id = cursor.getInt(0),
			collectedDataEpoch = cursor.getLong(1),
			sourceEvidenceRevision = cursor.getLong(2),
			erasedAtMs = cursor.getLong(3),
			providerRegistrationGeneration =
				if (cursor.isNull(4)) null else cursor.getLong(4),
			legacyWriteFenceGeneration = cursor.getLong(5),
			localFactRevisionCount = cursor.getInt(6),
			localWalEventCount = cursor.getInt(7),
			legacySampleCount = cursor.getInt(8),
			legacySampleSetChecksum = cursor.getString(9),
			importedEntryCount = cursor.getInt(10),
			importedRevisionCount = cursor.getInt(11),
			importedRunCount = cursor.getInt(12),
			importedWindowCount = cursor.getInt(13),
			fencedLocalRunCount = cursor.getInt(14),
			localScopeSetChecksum = cursor.getString(15),
			entryDeletionCount = cursor.getInt(16),
			entryDeletionSetChecksum = cursor.getString(17),
			runDeletionCount = cursor.getInt(18),
			runDeletionSetChecksum = cursor.getString(19),
			identityFenceCount = cursor.getInt(20),
			identityFenceSetChecksum = cursor.getString(21),
			effectChecksum = cursor.getString(22),
		)
	}
	check(marker.collectedDataEpoch == expectedEpoch)
	val witnessCount = sqlite.query(
		"SELECT COUNT(*) FROM imported_pressure_source_erase_witness",
	).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
	check(witnessCount == (
		marker.fencedLocalRunCount + marker.legacySampleCount + marker.entryDeletionCount +
			marker.runDeletionCount + marker.identityFenceCount
		).toLong())
	val witnesses = sqlite.query(
		"SELECT source_erase_id, witness_kind, witness_identity, authority_checksum, effect_checksum " +
			"FROM imported_pressure_source_erase_witness ORDER BY witness_kind, witness_identity",
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					ImportedPressureSourceEraseWitnessEntity(
						sourceEraseId = cursor.getInt(0),
						witnessKind = cursor.getString(1),
						witnessIdentity = cursor.getString(2),
						authorityChecksum = cursor.getString(3),
						effectChecksum = cursor.getString(4),
					),
				)
			}
		}
	}
	check(
		ImportedPressureSourceEraseEntity.checksumLegacySamples(
			witnesses.filter {
				it.witnessKind == ImportedPressureSourceEraseWitnessEntity.LEGACY_SAMPLE
			},
		) == marker.legacySampleSetChecksum,
	)
	check(witnesses.count {
		it.witnessKind == ImportedPressureSourceEraseWitnessEntity.LOCAL_SCOPE
	} == marker.fencedLocalRunCount)
	check(witnesses.count {
		it.witnessKind == ImportedPressureSourceEraseWitnessEntity.LEGACY_SAMPLE
	} == marker.legacySampleCount)
	check(witnesses.count {
		it.witnessKind == ImportedPressureSourceEraseWitnessEntity.ENTRY_DELETION
	} == marker.entryDeletionCount)
	check(witnesses.count {
		it.witnessKind == ImportedPressureSourceEraseWitnessEntity.RUN_DELETION
	} == marker.runDeletionCount)
	check(witnesses.count {
		it.witnessKind == ImportedPressureSourceEraseWitnessEntity.IDENTITY_FENCE
	} == marker.identityFenceCount)
	val localFences = mutableListOf<SourceDeletionFenceEntity>()
	val entryDeletions = mutableListOf<ImportedPressureEntryDeletionEntity>()
	val runDeletions = mutableListOf<ImportedPressureDeletionGenerationEntity>()
	val identityFences = mutableListOf<ImportedPressureIdentityFenceEntity>()
	witnesses.forEach { witness ->
			val actual = when (witness.witnessKind) {
				ImportedPressureSourceEraseWitnessEntity.LOCAL_SCOPE -> sqlite.query(
					"SELECT source_kind, purpose, scope_kind, scope_identity_digest, " +
						"fence_generation, collected_data_epoch, deleted_at_ms, effect_checksum " +
						"FROM source_deletion_fence WHERE source_kind = " +
						"${SourceDestinationOwnerEntity.SOURCE_PRESSURE} " +
						"AND purpose = 'SESSION_CAPTURE' AND scope_kind = 'LOGICAL_SERVICE_RUN' " +
						"AND scope_identity_digest = ?",
					arrayOf(witness.witnessIdentity),
				).use { value ->
					check(value.moveToFirst())
					SourceDeletionFenceEntity(
						sourceKind = value.getInt(0),
						purpose = value.getString(1),
						scopeKind = value.getString(2),
						scopeIdentityDigest = value.getString(3),
						fenceGeneration = value.getLong(4),
						collectedDataEpoch = value.getLong(5),
						deletedAtMs = value.getLong(6),
						effectChecksum = value.getString(7),
					).also(localFences::add).effectChecksum
				}
				ImportedPressureSourceEraseWitnessEntity.LEGACY_SAMPLE ->
					witness.authorityChecksum
				ImportedPressureSourceEraseWitnessEntity.ENTRY_DELETION ->
					loadAndAuthenticateEntryDeletion(
						sqlite,
						witness.witnessIdentity,
					).also(entryDeletions::add).effectChecksum
				ImportedPressureSourceEraseWitnessEntity.RUN_DELETION ->
					requireNotNull(
						loadRunDeletion(sqlite, witness.witnessIdentity),
					).also(runDeletions::add).effectChecksum
				ImportedPressureSourceEraseWitnessEntity.IDENTITY_FENCE ->
					loadIdentityFence(
						sqlite,
						witness.witnessIdentity,
					).also(identityFences::add).effectChecksum
				else -> error("Unknown imported Pressure erase witness")
			}
			check(actual == witness.authorityChecksum)
	}
	check(
		ImportedPressureSourceEraseEntity.checksumLocalFences(localFences) ==
			marker.localScopeSetChecksum,
	)
	check(
		ImportedPressureSourceEraseEntity.checksumEntryDeletions(entryDeletions) ==
			marker.entryDeletionSetChecksum,
	)
	check(
		ImportedPressureRetentionReceiptEntity.checksumRunDeletions(runDeletions) ==
			marker.runDeletionSetChecksum,
	)
	check(
		ImportedPressureIdentityFenceEntity.checksumSet(identityFences) ==
			marker.identityFenceSetChecksum,
	)
}

private fun insertOrAuthenticateIdentityFences(
	sqlite: SupportSQLiteDatabase,
	fences: List<ImportedPressureIdentityFenceEntity>,
) {
	val insert = sqlite.compileStatement(
		"INSERT OR IGNORE INTO imported_pressure_identity_fence " +
			"(protected_identity, identity_kind, entry_identity, run_identity, " +
			"original_collected_data_epoch, fence_generation, fenced_at_ms, fence_reason, " +
			"effect_checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
	)
	insert.use { statement ->
		fences.forEach { fence ->
			statement.clearBindings()
			statement.bindString(1, fence.protectedIdentity)
			statement.bindString(2, fence.identityKind)
			statement.bindString(3, fence.entryIdentity)
			if (fence.runIdentity == null) statement.bindNull(4) else
				statement.bindString(4, fence.runIdentity)
			statement.bindLong(5, fence.originalCollectedDataEpoch)
			statement.bindLong(6, fence.fenceGeneration)
			statement.bindLong(7, fence.fencedAtMs)
			statement.bindString(8, fence.fenceReason)
			statement.bindString(9, fence.effectChecksum)
			statement.executeInsert()
			val actual = loadIdentityFence(sqlite, fence.protectedIdentity)
			check(actual.hasSameOwner(
				fence.identityKind,
				fence.entryIdentity,
				fence.runIdentity,
			))
		}
	}
}

private fun loadIdentityFence(
	sqlite: SupportSQLiteDatabase,
	identity: String,
): ImportedPressureIdentityFenceEntity = sqlite.query(
	"SELECT protected_identity, identity_kind, entry_identity, run_identity, " +
		"original_collected_data_epoch, fence_generation, fenced_at_ms, fence_reason, effect_checksum " +
		"FROM imported_pressure_identity_fence WHERE protected_identity = ?",
	arrayOf(identity),
).use { cursor ->
	check(cursor.moveToFirst())
	ImportedPressureIdentityFenceEntity(
		protectedIdentity = cursor.getString(0),
		identityKind = cursor.getString(1),
		entryIdentity = cursor.getString(2),
		runIdentity = if (cursor.isNull(3)) null else cursor.getString(3),
		originalCollectedDataEpoch = cursor.getLong(4),
		fenceGeneration = cursor.getLong(5),
		fencedAtMs = cursor.getLong(6),
		fenceReason = cursor.getString(7),
		effectChecksum = cursor.getString(8),
	)
}

private fun loadRunDeletion(
	sqlite: SupportSQLiteDatabase,
	runIdentity: String,
): ImportedPressureDeletionGenerationEntity? = sqlite.query(
	"SELECT run_identity, collected_data_epoch, generation, deleted_at_ms, effect_checksum " +
		"FROM imported_pressure_deletion_generation WHERE run_identity = ?",
	arrayOf(runIdentity),
).use { cursor ->
	if (!cursor.moveToFirst()) null else ImportedPressureDeletionGenerationEntity(
		runIdentity = cursor.getString(0),
		collectedDataEpoch = cursor.getLong(1),
		generation = cursor.getLong(2),
		deletedAtMs = cursor.getLong(3),
		effectChecksum = cursor.getString(4),
	)
}

private fun readLineageFootprint(
	sqlite: SupportSQLiteDatabase,
	ownerRowId: Long,
): FullClearLineageFootprint = sqlite.query(
	"WITH owner(identity) AS (" +
		"SELECT identity FROM imported_pressure_entry_revision WHERE rowid = ?" +
		") SELECT " +
		"(SELECT COUNT(*) FROM imported_pressure_entry_revision " +
		"WHERE identity = (SELECT identity FROM owner)), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(identity AS BLOB)) + " +
		"LENGTH(CAST(content_checksum AS BLOB)) + LENGTH(CAST(source_format AS BLOB)) + " +
		"LENGTH(CAST(import_job_id AS BLOB)) + LENGTH(CAST(import_entry_key AS BLOB)) + " +
		"LENGTH(CAST(import_source_name AS BLOB))), 0) " +
		"FROM imported_pressure_entry_revision WHERE identity = (SELECT identity FROM owner)), " +
		"(SELECT COUNT(*) FROM imported_pressure_receipt " +
		"WHERE entry_identity = (SELECT identity FROM owner)), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(import_job_id AS BLOB)) + " +
		"LENGTH(CAST(import_entry_key AS BLOB)) + LENGTH(CAST(import_source_name AS BLOB)) + " +
		"LENGTH(CAST(entry_identity AS BLOB)) + LENGTH(CAST(entry_content_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_receipt WHERE entry_identity = (SELECT identity FROM owner)), " +
		"(SELECT COUNT(*) FROM imported_pressure_run " +
		"WHERE entry_identity = (SELECT identity FROM owner)), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(identity AS BLOB)) + LENGTH(CAST(availability AS BLOB)) + " +
		"LENGTH(CAST(coverage AS BLOB))), 0) FROM imported_pressure_run " +
		"WHERE entry_identity = (SELECT identity FROM owner)), " +
		"(SELECT COUNT(*) FROM imported_pressure_window " +
		"WHERE entry_identity = (SELECT identity FROM owner)), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(run_identity AS BLOB)) + LENGTH(CAST(identity AS BLOB)) + " +
		"LENGTH(CAST(content_checksum AS BLOB)) + LENGTH(CAST(sensor_accuracy AS BLOB)) + " +
		"LENGTH(CAST(closure_kind AS BLOB)) + LENGTH(CAST(qualification AS BLOB)) + " +
		"LENGTH(CAST(stored_zone_id AS BLOB))), 0) " +
		"FROM imported_pressure_window WHERE entry_identity = (SELECT identity FROM owner)), " +
		"(SELECT COUNT(*) FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT identity FROM owner)), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(protected_identity AS BLOB)) + " +
		"LENGTH(CAST(identity_kind AS BLOB)) + LENGTH(CAST(entry_identity AS BLOB)) + " +
		"COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) + " +
		"LENGTH(CAST(fence_reason AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT identity FROM owner)), " +
		"(SELECT COUNT(*) FROM imported_pressure_entry_deletion " +
		"WHERE entry_identity = (SELECT identity FROM owner)), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_entry_deletion " +
		"WHERE entry_identity = (SELECT identity FROM owner)), " +
		"(SELECT COUNT(*) FROM imported_pressure_deletion_generation " +
		"WHERE run_identity IN (SELECT identity FROM imported_pressure_run " +
		"WHERE entry_identity = (SELECT identity FROM owner))), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(run_identity AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_deletion_generation " +
		"WHERE run_identity IN (SELECT identity FROM imported_pressure_run " +
		"WHERE entry_identity = (SELECT identity FROM owner)))",
	arrayOf(ownerRowId),
).use { cursor ->
	check(cursor.moveToFirst())
	FullClearLineageFootprint(
		headerCount = cursor.getLong(0),
		headerTextBytes = cursor.getLong(1),
		receiptCount = cursor.getLong(2),
		receiptTextBytes = cursor.getLong(3),
		runCount = cursor.getLong(4),
		runTextBytes = cursor.getLong(5),
		windowCount = cursor.getLong(6),
		windowTextBytes = cursor.getLong(7),
		identityFenceCount = cursor.getLong(8),
		identityFenceTextBytes = cursor.getLong(9),
		entryDeletionCount = cursor.getLong(10),
		entryDeletionTextBytes = cursor.getLong(11),
		runDeletionCount = cursor.getLong(12),
		runDeletionTextBytes = cursor.getLong(13),
	)
}

private fun readRetainedFootprint(
	sqlite: SupportSQLiteDatabase,
	ownerRowId: Long,
): FullClearRetainedFootprint = sqlite.query(
	"WITH owner(entry_identity) AS (" +
		"SELECT entry_identity FROM imported_pressure_retention_receipt WHERE rowid = ?" +
		") SELECT " +
		"(SELECT COUNT(*) FROM imported_pressure_retention_receipt " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(latest_content_checksum AS BLOB)) + " +
		"LENGTH(CAST(recency_tie_identity AS BLOB)) + " +
		"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(protected_identity_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(lineage_authority_checksum AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_retention_receipt " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)), " +
		"(SELECT COUNT(*) FROM imported_pressure_retained_identity " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(protected_identity AS BLOB)) + " +
		"LENGTH(CAST(entry_identity AS BLOB)) + LENGTH(CAST(identity_kind AS BLOB))), 0) " +
		"FROM imported_pressure_retained_identity " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)), " +
		"(SELECT COUNT(*) FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(protected_identity AS BLOB)) + " +
		"LENGTH(CAST(identity_kind AS BLOB)) + LENGTH(CAST(entry_identity AS BLOB)) + " +
		"COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) + " +
		"LENGTH(CAST(fence_reason AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)), " +
		"(SELECT COUNT(*) FROM imported_pressure_entry_deletion " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_entry_deletion " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)), " +
		"(SELECT COUNT(*) FROM imported_pressure_deletion_generation " +
		"WHERE run_identity IN (SELECT protected_identity FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner) AND identity_kind = 'RUN')), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(run_identity AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_deletion_generation " +
		"WHERE run_identity IN (SELECT protected_identity FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner) AND identity_kind = 'RUN'))",
	arrayOf(ownerRowId),
).use { cursor ->
	check(cursor.moveToFirst())
	FullClearRetainedFootprint(
		receiptCount = cursor.getLong(0),
		receiptTextBytes = cursor.getLong(1),
		markerCount = cursor.getLong(2),
		markerTextBytes = cursor.getLong(3),
		identityFenceCount = cursor.getLong(4),
		identityFenceTextBytes = cursor.getLong(5),
		entryDeletionCount = cursor.getLong(6),
		entryDeletionTextBytes = cursor.getLong(7),
		runDeletionCount = cursor.getLong(8),
		runDeletionTextBytes = cursor.getLong(9),
	)
}

private fun readGlobalLineageFootprint(
	sqlite: SupportSQLiteDatabase,
): FullClearLineageFootprint = sqlite.query(
	"SELECT " +
		"(SELECT COUNT(*) FROM imported_pressure_entry_revision), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(identity AS BLOB)) + " +
		"LENGTH(CAST(content_checksum AS BLOB)) + LENGTH(CAST(source_format AS BLOB)) + " +
		"LENGTH(CAST(import_job_id AS BLOB)) + LENGTH(CAST(import_entry_key AS BLOB)) + " +
		"LENGTH(CAST(import_source_name AS BLOB))), 0) FROM imported_pressure_entry_revision), " +
		"(SELECT COUNT(*) FROM imported_pressure_receipt), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(import_job_id AS BLOB)) + " +
		"LENGTH(CAST(import_entry_key AS BLOB)) + LENGTH(CAST(import_source_name AS BLOB)) + " +
		"LENGTH(CAST(entry_identity AS BLOB)) + LENGTH(CAST(entry_content_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_receipt), " +
		"(SELECT COUNT(*) FROM imported_pressure_run), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(identity AS BLOB)) + LENGTH(CAST(availability AS BLOB)) + " +
		"LENGTH(CAST(coverage AS BLOB))), 0) FROM imported_pressure_run), " +
		"(SELECT COUNT(*) FROM imported_pressure_window), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(run_identity AS BLOB)) + LENGTH(CAST(identity AS BLOB)) + " +
		"LENGTH(CAST(content_checksum AS BLOB)) + LENGTH(CAST(sensor_accuracy AS BLOB)) + " +
		"LENGTH(CAST(closure_kind AS BLOB)) + LENGTH(CAST(qualification AS BLOB)) + " +
		"LENGTH(CAST(stored_zone_id AS BLOB))), 0) FROM imported_pressure_window)",
).use { cursor ->
	check(cursor.moveToFirst())
	FullClearLineageFootprint(
		cursor.getLong(0),
		cursor.getLong(1),
		cursor.getLong(2),
		cursor.getLong(3),
		cursor.getLong(4),
		cursor.getLong(5),
		cursor.getLong(6),
		cursor.getLong(7),
	)
}

private fun readGlobalRetainedFootprint(
	sqlite: SupportSQLiteDatabase,
): FullClearRetainedFootprint = sqlite.query(
	"SELECT " +
		"(SELECT COUNT(*) FROM imported_pressure_retention_receipt), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(latest_content_checksum AS BLOB)) + " +
		"LENGTH(CAST(recency_tie_identity AS BLOB)) + " +
		"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(protected_identity_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(lineage_authority_checksum AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) FROM imported_pressure_retention_receipt), " +
		"(SELECT COUNT(*) FROM imported_pressure_retained_identity), " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(protected_identity AS BLOB)) + " +
		"LENGTH(CAST(entry_identity AS BLOB)) + LENGTH(CAST(identity_kind AS BLOB))), 0) " +
		"FROM imported_pressure_retained_identity)",
).use { cursor ->
	check(cursor.moveToFirst())
	FullClearRetainedFootprint(
		cursor.getLong(0),
		cursor.getLong(1),
		cursor.getLong(2),
		cursor.getLong(3),
	)
}

private fun readGlobalPrivacyAuthorityBytes(sqlite: SupportSQLiteDatabase): Long =
	sqlite.query(
		"SELECT " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(protected_identity AS BLOB)) + " +
			"LENGTH(CAST(identity_kind AS BLOB)) + LENGTH(CAST(entry_identity AS BLOB)) + " +
			"COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) + " +
			"LENGTH(CAST(fence_reason AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_identity_fence), " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(witness_kind AS BLOB)) + " +
			"LENGTH(CAST(witness_identity AS BLOB)) + LENGTH(CAST(authority_checksum AS BLOB)) + " +
			"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_source_erase_witness), " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
			"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_entry_deletion), " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(run_identity AS BLOB)) + " +
			"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_deletion_generation), " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(legacy_sample_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(local_scope_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(entry_deletion_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_source_erase)",
	).use { cursor ->
		check(cursor.moveToFirst())
		val identityBytes = cursor.getLong(0)
		val witnessBytes = cursor.getLong(1)
		val entryDeletionBytes = cursor.getLong(2)
		val runDeletionBytes = cursor.getLong(3)
		val sourceEraseBytes = cursor.getLong(4)
		check(listOf(
			identityBytes,
			witnessBytes,
			entryDeletionBytes,
			runDeletionBytes,
			sourceEraseBytes,
		).all { it >= 0L })
		Math.addExact(
			Math.addExact(
				Math.addExact(identityBytes, witnessBytes),
				Math.addExact(entryDeletionBytes, runDeletionBytes),
			),
			sourceEraseBytes,
		)
	}

private fun checkedMaintenanceBytes(current: Long, added: Long): Long {
	val next = Math.addExact(current, added)
	check(next <= MAX_MAINTENANCE_TEXT_BYTES)
	return next
}

private data class FullClearLineage(
	val owners: Map<String, FullClearIdentityOwner>,
	val textBytes: Long,
) {
	fun identityFences(
		epoch: Long,
		fencedAtMs: Long,
		reason: String,
	): List<ImportedPressureIdentityFenceEntity> = owners.map { (identity, owner) ->
		ImportedPressureIdentityFenceEntity.create(
			protectedIdentity = identity,
			identityKind = owner.kind,
			entryIdentity = owner.entryIdentity,
			runIdentity = owner.runIdentity,
			originalCollectedDataEpoch = epoch,
			fencedAtMs = fencedAtMs,
			fenceReason = reason,
		)
	}.sortedBy { it.protectedIdentity }
}

private data class FullClearIdentityOwner(
	val kind: String,
	val entryIdentity: String,
	val runIdentity: String?,
)

private data class FullClearRetainedLineage(val textBytes: Long)

private data class FullClearRetainedFootprint(
	val receiptCount: Long,
	val receiptTextBytes: Long,
	val markerCount: Long,
	val markerTextBytes: Long,
	val identityFenceCount: Long = 0L,
	val identityFenceTextBytes: Long = 0L,
	val entryDeletionCount: Long = 0L,
	val entryDeletionTextBytes: Long = 0L,
	val runDeletionCount: Long = 0L,
	val runDeletionTextBytes: Long = 0L,
) {
	val totalTextBytes: Long
		get() = Math.addExact(
			Math.addExact(receiptTextBytes, markerTextBytes),
			Math.addExact(
				Math.addExact(identityFenceTextBytes, entryDeletionTextBytes),
				runDeletionTextBytes,
			),
		)

	fun requireWithinBounds() {
		check(receiptCount == 1L)
		check(markerCount in 2L..MAX_IDENTITY_FENCES.toLong())
		check(identityFenceCount == markerCount)
		check(entryDeletionCount in 0L..1L)
		check(runDeletionCount <= identityFenceCount)
		check(listOf(
			receiptTextBytes,
			markerTextBytes,
			identityFenceTextBytes,
			entryDeletionTextBytes,
			runDeletionTextBytes,
		).all { it >= 0L })
		check(totalTextBytes <= MAX_LINEAGE_TEXT_BYTES)
	}

	fun requireGlobalWithinBounds() {
		check(receiptCount != 0L || markerCount == 0L)
		check(receiptCount in 0L..MAX_SOURCE_ENTRIES.toLong())
		check(markerCount in 0L..MAX_IDENTITY_FENCES.toLong())
		check(receiptTextBytes >= 0L && markerTextBytes >= 0L)
		check(totalTextBytes <= MAX_MAINTENANCE_TEXT_BYTES)
	}
}

private data class FullClearLineageFootprint(
	val headerCount: Long,
	val headerTextBytes: Long,
	val receiptCount: Long,
	val receiptTextBytes: Long,
	val runCount: Long,
	val runTextBytes: Long,
	val windowCount: Long,
	val windowTextBytes: Long,
	val identityFenceCount: Long = 0L,
	val identityFenceTextBytes: Long = 0L,
	val entryDeletionCount: Long = 0L,
	val entryDeletionTextBytes: Long = 0L,
	val runDeletionCount: Long = 0L,
	val runDeletionTextBytes: Long = 0L,
) {
	val totalTextBytes: Long
		get() = Math.addExact(
			Math.addExact(
				Math.addExact(headerTextBytes, receiptTextBytes),
				Math.addExact(runTextBytes, windowTextBytes),
			),
			Math.addExact(
				Math.addExact(identityFenceTextBytes, entryDeletionTextBytes),
				runDeletionTextBytes,
			),
		)

	fun requireWithinBounds() {
		check(headerCount in 1L..MAX_REVISIONS.toLong())
		check(receiptCount in headerCount..MAX_RECEIPTS.toLong())
		check(runCount in headerCount..MAX_RUN_ROWS.toLong())
		check(windowCount in 0L..MAX_WINDOW_ROWS.toLong())
		check(identityFenceCount in 0L..MAX_IDENTITY_FENCES.toLong())
		check(entryDeletionCount in 0L..1L)
		check(runDeletionCount in 0L..MAX_RUN_ROWS.toLong())
		check(listOf(
			headerTextBytes,
			receiptTextBytes,
			runTextBytes,
			windowTextBytes,
			identityFenceTextBytes,
			entryDeletionTextBytes,
			runDeletionTextBytes,
		).all { it >= 0L })
		check(totalTextBytes <= MAX_LINEAGE_TEXT_BYTES)
	}

	fun requireGlobalWithinBounds() {
		check(headerCount != 0L || receiptCount == 0L && runCount == 0L && windowCount == 0L)
		check(receiptCount >= headerCount && runCount >= headerCount)
		check(headerCount in 0L..MAX_GLOBAL_REVISIONS)
		check(receiptCount in 0L..MAX_GLOBAL_RECEIPTS)
		check(runCount in 0L..MAX_GLOBAL_RUN_ROWS)
		check(windowCount in 0L..MAX_GLOBAL_WINDOW_ROWS)
		check(listOf(
			headerTextBytes,
			receiptTextBytes,
			runTextBytes,
			windowTextBytes,
		).all { it >= 0L })
		check(totalTextBytes <= MAX_MAINTENANCE_TEXT_BYTES)
	}
}

private const val MAX_REVISIONS = 16
private const val MAX_RECEIPTS = 256
private const val MAX_RUNS_PER_REVISION = 64
private const val MAX_WINDOWS_PER_REVISION = 16_384
private const val MAX_RUN_ROWS = MAX_REVISIONS * MAX_RUNS_PER_REVISION
private const val MAX_WINDOW_ROWS = MAX_REVISIONS * MAX_WINDOWS_PER_REVISION
private const val MAX_SOURCE_ENTRIES = 65_536
private const val MAX_DELETION_ENTRIES = 65_536
private const val MAX_IDENTITY_FENCES = 524_288
private const val MAX_RUN_DELETIONS = 262_144
private const val MAX_GLOBAL_REVISIONS = 65_536L
private const val MAX_GLOBAL_RECEIPTS = 65_536L
private const val MAX_GLOBAL_RUN_ROWS = 262_144L
private const val MAX_GLOBAL_WINDOW_ROWS = 262_144L
private const val MAX_LINEAGE_TEXT_BYTES = 128L * 1_024L * 1_024L
private const val MAX_MAINTENANCE_TEXT_BYTES = 256L * 1_024L * 1_024L
