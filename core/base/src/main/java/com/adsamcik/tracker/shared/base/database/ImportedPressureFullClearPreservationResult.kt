package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SimpleSQLiteQuery
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

/**
 * Counts of live, retained, deleted and identity-fence Pressure entries preserved as full-clear
 * replay authority.
 */
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
): ImportedPressureFullClearPreservationResult =
	PressureFullClearPreservation.preserve(sqlite, expectedCollectedDataEpoch, fencedAtMs)

private object PressureFullClearPreservation {
	/** Preserves replay protection in the caller's full-clear transaction after bounded preflight. */
	fun preserve(
		sqlite: SupportSQLiteDatabase,
		expectedCollectedDataEpoch: Long,
		fencedAtMs: Long,
	): ImportedPressureFullClearPreservationResult {
		require(expectedCollectedDataEpoch >= 0L && fencedAtMs >= 0L)
		requireCurrentEpoch(sqlite, expectedCollectedDataEpoch)
		val preflight = preflight(sqlite)
		val live = preserveLiveLineages(sqlite, expectedCollectedDataEpoch, fencedAtMs)
		val retainedCount = authenticateRetainedLineages(sqlite, expectedCollectedDataEpoch, live.textBytes)
		requireMaintenanceByteBound(
			preflight.live, preflight.retained, readGlobalPrivacyAuthorityBytes(sqlite), preflight.localFences,
		)
		val permanent = authenticatePermanentOwners(sqlite)
		authenticateLocalFenceOwners(sqlite, preflight.localFences.rowCount)
		PressureFullClearSourceErase.authenticate(sqlite, expectedCollectedDataEpoch)
		return ImportedPressureFullClearPreservationResult(
			liveEntryCount = live.count,
			retainedEntryCount = retainedCount,
			deletionEntryCount = Math.toIntExact(permanent.entryDeletionCount),
			identityFenceCount = Math.toIntExact(permanent.identityFenceCount),
		)
	}

	private fun requireCurrentEpoch(sqlite: SupportSQLiteDatabase, expectedCollectedDataEpoch: Long) {
		val evidenceEpoch = sqlite.query(
			"SELECT collected_data_epoch FROM source_evidence_state WHERE id = 1",
		).use { cursor ->
			check(cursor.moveToFirst()) { "Imported Pressure full clear requires evidence authority" }
			cursor.getLong(0)
		}
		check(evidenceEpoch == expectedCollectedDataEpoch) {
			"Imported Pressure full clear must authenticate the old epoch before advancing it"
		}
	}

	private fun preflight(sqlite: SupportSQLiteDatabase): FullClearPreflight {
		val globalLiveFootprint = readGlobalLineageFootprint(sqlite)
		globalLiveFootprint.requireGlobalWithinBounds()
		val globalRetainedFootprint = readGlobalRetainedFootprint(sqlite)
		globalRetainedFootprint.requireGlobalWithinBounds()
		val privacyBytes = readGlobalPrivacyAuthorityBytes(sqlite)
		val localFenceFootprint = readGlobalLocalFenceFootprint(sqlite)
		check(localFenceFootprint.rowCount in 0L..MAX_LOCAL_FENCES.toLong())
		check(localFenceFootprint.textBytes >= 0L)
		requireMaintenanceByteBound(globalLiveFootprint, globalRetainedFootprint, privacyBytes, localFenceFootprint)
		return FullClearPreflight(globalLiveFootprint, globalRetainedFootprint, localFenceFootprint)
	}

	private fun requireMaintenanceByteBound(
		globalLiveFootprint: FullClearLineageFootprint,
		globalRetainedFootprint: FullClearRetainedFootprint,
		privacyBytes: Long,
		localFenceFootprint: FullClearLocalFenceFootprint,
	) {
		check(
			Math.addExact(
				Math.addExact(
					Math.addExact(
						globalLiveFootprint.totalTextBytes,
						globalRetainedFootprint.totalTextBytes,
					),
					privacyBytes,
				),
				localFenceFootprint.textBytes,
			) <= MAX_MAINTENANCE_TEXT_BYTES,
		)
	}

	private fun preserveLiveLineages(
		sqlite: SupportSQLiteDatabase,
		expectedCollectedDataEpoch: Long,
		fencedAtMs: Long,
	): FullClearPreservedLineages {
		var liveCount = 0
		var totalTextBytes = 0L
		var ownerRowId = 0L
		while (true) {
			val nextOwnerRowId = sqlite.query(
				"SELECT MIN(rowid) FROM imported_pressure_entry_revision GROUP BY identity " +
					"HAVING MIN(rowid) > ? ORDER BY MIN(rowid) LIMIT 1",
				arrayOf(ownerRowId),
			).use { cursor -> if (cursor.moveToFirst()) {
				cursor.getLong(0)
			} else {
				null
			} } ?: break
			check(nextOwnerRowId > ownerRowId)
			val footprint = readLineageFootprint(sqlite, nextOwnerRowId)
			footprint.requireWithinBounds()
			val identity = sqlite.query(
				"SELECT identity FROM imported_pressure_entry_revision WHERE rowid = ?",
				arrayOf(nextOwnerRowId),
			).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
			ownerRowId = nextOwnerRowId
			val lineage = PressureFullClearLineages.live(
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

		return FullClearPreservedLineages(liveCount, totalTextBytes)
	}

	private fun authenticateRetainedLineages(
		sqlite: SupportSQLiteDatabase,
		expectedCollectedDataEpoch: Long,
		liveTextBytes: Long,
	): Int {
		var retainedCount = 0
		var totalTextBytes = liveTextBytes
		var ownerRowId = 0L
		while (true) {
			val nextOwnerRowId = sqlite.query(
				"SELECT rowid FROM imported_pressure_retention_receipt WHERE rowid > ? " +
					"ORDER BY rowid LIMIT 1",
				arrayOf(ownerRowId),
			).use { cursor -> if (cursor.moveToFirst()) {
				cursor.getLong(0)
			} else {
				null
			} } ?: break
			check(nextOwnerRowId > ownerRowId)
			val footprint = readRetainedFootprint(sqlite, nextOwnerRowId)
			footprint.requireWithinBounds()
			val identity = sqlite.query(
				"SELECT entry_identity FROM imported_pressure_retention_receipt WHERE rowid = ?",
				arrayOf(nextOwnerRowId),
			).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
			ownerRowId = nextOwnerRowId
			val retained = PressureFullClearLineages.retained(
				sqlite,
				identity,
				expectedCollectedDataEpoch,
				footprint,
			)
			totalTextBytes = checkedMaintenanceBytes(totalTextBytes, retained.textBytes)
			retainedCount = Math.addExact(retainedCount, 1)
			check(retainedCount <= MAX_SOURCE_ENTRIES)
		}

		return retainedCount
	}

	private fun authenticatePermanentOwners(sqlite: SupportSQLiteDatabase): FullClearPermanentCounts {
		val counts = authenticateFenceOnlyOwners(sqlite, authenticateDeletionOwners(sqlite))
		val global = sqlite.query(
			"SELECT " +
				"(SELECT COUNT(*) FROM imported_pressure_entry_deletion), " +
				"(SELECT COUNT(*) FROM imported_pressure_identity_fence), " +
				"(SELECT COUNT(*) FROM imported_pressure_deletion_generation)",
		).use { cursor ->
			check(cursor.moveToFirst())
			FullClearPermanentCounts(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
		}
		check(global.entryDeletionCount <= MAX_DELETION_ENTRIES)
		check(global.identityFenceCount <= MAX_IDENTITY_FENCES)
		check(global.runDeletionCount <= MAX_RUN_DELETIONS)
		check(counts == global)
		return counts
	}

	private fun authenticateDeletionOwners(sqlite: SupportSQLiteDatabase): FullClearPermanentCounts {
		var counts = FullClearPermanentCounts()
		var ownerRowId = 0L
		while (true) {
			val nextOwnerRowId = sqlite.query(
				"SELECT rowid FROM imported_pressure_entry_deletion WHERE rowid > ? " +
					"ORDER BY rowid LIMIT 1",
				arrayOf(ownerRowId),
			).use { cursor -> if (cursor.moveToFirst()) {
				cursor.getLong(0)
			} else {
				null
			} } ?: break
			check(nextOwnerRowId > ownerRowId)
			val footprint = readPermanentOwnerFootprint(
				sqlite,
				nextOwnerRowId,
				entryDeletionOwner = true,
			)
			footprint.requireWithinBounds(requireEntryDeletion = true)
			val identity = sqlite.query(
				"SELECT entry_identity FROM imported_pressure_entry_deletion WHERE rowid = ?",
				arrayOf(nextOwnerRowId),
			).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
			counts = counts.plus(
				loadAndAuthenticatePermanentOwner(
					sqlite,
					identity,
					footprint,
					requireEntryDeletion = true,
				),
			)
			ownerRowId = nextOwnerRowId
		}
		return counts
	}

	private fun authenticateFenceOnlyOwners(
		sqlite: SupportSQLiteDatabase,
		initialCounts: FullClearPermanentCounts,
	): FullClearPermanentCounts {
		var counts = initialCounts
		var ownerRowId = 0L
		while (true) {
			val nextOwnerRowId = sqlite.query(
				"SELECT fence.rowid FROM imported_pressure_identity_fence AS fence " +
					"WHERE fence.rowid > ? AND fence.identity_kind = 'ENTRY' " +
					"AND fence.protected_identity = fence.entry_identity " +
					"AND fence.run_identity IS NULL AND NOT EXISTS (" +
					"SELECT 1 FROM imported_pressure_entry_deletion AS deletion " +
					"WHERE deletion.entry_identity = fence.entry_identity) " +
					"ORDER BY fence.rowid LIMIT 1",
				arrayOf(ownerRowId),
			).use { cursor -> if (cursor.moveToFirst()) {
				cursor.getLong(0)
			} else {
				null
			} } ?: break
			check(nextOwnerRowId > ownerRowId)
			val footprint = readPermanentOwnerFootprint(
				sqlite,
				nextOwnerRowId,
				entryDeletionOwner = false,
			)
			footprint.requireWithinBounds(requireEntryDeletion = false)
			val entryMarker = loadIdentityFenceByRowId(sqlite, nextOwnerRowId)
			counts = counts.plus(
				loadAndAuthenticatePermanentOwner(
					sqlite,
					entryMarker.entryIdentity,
					footprint,
					requireEntryDeletion = false,
				),
			)
			ownerRowId = nextOwnerRowId
		}
		return counts
	}
}

private data class FullClearPreflight(
	val live: FullClearLineageFootprint,
	val retained: FullClearRetainedFootprint,
	val localFences: FullClearLocalFenceFootprint,
)

private data class FullClearPreservedLineages(val count: Int, val textBytes: Long)

private fun readPermanentOwnerFootprint(
	sqlite: SupportSQLiteDatabase,
	ownerRowId: Long,
	entryDeletionOwner: Boolean,
): FullClearPermanentOwnerFootprint {
	val ownerSelect = if (entryDeletionOwner) {
		"SELECT entry_identity FROM imported_pressure_entry_deletion WHERE rowid = ?"
	} else {
		"SELECT entry_identity FROM imported_pressure_identity_fence WHERE rowid = ?"
	}
	return sqlite.query(
		"WITH owner(entry_identity) AS ($ownerSelect) SELECT " +
			"(SELECT COUNT(*) FROM imported_pressure_entry_deletion " +
			"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS entry_deletion_count, " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
			"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_entry_deletion " +
			"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS entry_deletion_text_bytes, " +
			"(SELECT COUNT(*) FROM imported_pressure_identity_fence " +
			"WHERE entry_identity = (SELECT entry_identity FROM owner) " +
			"AND identity_kind = 'ENTRY' AND protected_identity = entry_identity " +
			"AND run_identity IS NULL) AS entry_marker_count, " +
			"(SELECT COUNT(*) FROM imported_pressure_identity_fence " +
			"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS identity_fence_count, " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(protected_identity AS BLOB)) + " +
			"LENGTH(CAST(identity_kind AS BLOB)) + LENGTH(CAST(entry_identity AS BLOB)) + " +
			"COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) + " +
			"LENGTH(CAST(fence_reason AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_identity_fence " +
			"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS identity_fence_text_bytes, " +
			"(SELECT COUNT(*) FROM imported_pressure_deletion_generation " +
			"WHERE run_identity IN (SELECT protected_identity " +
			"FROM imported_pressure_identity_fence " +
			"WHERE entry_identity = (SELECT entry_identity FROM owner) " +
			"AND identity_kind = 'RUN')) AS run_deletion_count, " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(run_identity AS BLOB)) + " +
			"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_deletion_generation " +
			"WHERE run_identity IN (SELECT protected_identity " +
			"FROM imported_pressure_identity_fence " +
			"WHERE entry_identity = (SELECT entry_identity FROM owner) " +
			"AND identity_kind = 'RUN')) AS run_deletion_text_bytes",
		arrayOf(ownerRowId),
	).use { cursor ->
		check(cursor.moveToFirst())
		FullClearPermanentOwnerFootprint(
			entryDeletionCount = cursor.getLong(cursor.getColumnIndexOrThrow("entry_deletion_count")),
			entryDeletionTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("entry_deletion_text_bytes")),
			entryMarkerCount = cursor.getLong(cursor.getColumnIndexOrThrow("entry_marker_count")),
			identityFenceCount = cursor.getLong(cursor.getColumnIndexOrThrow("identity_fence_count")),
			identityFenceTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("identity_fence_text_bytes")),
			runDeletionCount = cursor.getLong(cursor.getColumnIndexOrThrow("run_deletion_count")),
			runDeletionTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("run_deletion_text_bytes")),
		)
	}
}

private fun loadAndAuthenticatePermanentOwner(
	sqlite: SupportSQLiteDatabase,
	identity: String,
	footprint: FullClearPermanentOwnerFootprint,
	requireEntryDeletion: Boolean,
): FullClearPermanentCounts {
	val fenceLimit = Math.toIntExact(footprint.identityFenceCount) + 1
	val fences = sqlite.query(
		"SELECT protected_identity, identity_kind, entry_identity, run_identity, " +
			"original_collected_data_epoch, fence_generation, fenced_at_ms, fence_reason, " +
			"effect_checksum FROM imported_pressure_identity_fence WHERE entry_identity = ? " +
			"ORDER BY identity_kind, protected_identity LIMIT ?",
		arrayOf<Any?>(identity, fenceLimit),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					ImportedPressureIdentityFenceEntity(
						protectedIdentity = cursor.getString(cursor.getColumnIndexOrThrow("protected_identity")),
						identityKind = cursor.getString(cursor.getColumnIndexOrThrow("identity_kind")),
						entryIdentity = cursor.getString(cursor.getColumnIndexOrThrow("entry_identity")),
						runIdentity = if (cursor.isNull(cursor.getColumnIndexOrThrow("run_identity"))) {
							null
						} else {
							cursor.getString(cursor.getColumnIndexOrThrow("run_identity"))
						},
						originalCollectedDataEpoch =
							cursor.getLong(cursor.getColumnIndexOrThrow("original_collected_data_epoch")),
						fenceGeneration = cursor.getLong(cursor.getColumnIndexOrThrow("fence_generation")),
						fencedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("fenced_at_ms")),
						fenceReason = cursor.getString(cursor.getColumnIndexOrThrow("fence_reason")),
						effectChecksum = cursor.getString(cursor.getColumnIndexOrThrow("effect_checksum")),
					),
				)
			}
		}
	}
	check(fences.size.toLong() == footprint.identityFenceCount)
	check(fences.all { it.entryIdentity == identity })
	check(fences.count {
		it.identityKind == ImportedPressureIdentityFenceEntity.ENTRY &&
			it.protectedIdentity == identity && it.runIdentity == null
	} == 1)
	val runDeletions = fences.filter {
		it.identityKind == ImportedPressureIdentityFenceEntity.RUN
	}.mapNotNull { loadRunDeletion(sqlite, it.protectedIdentity) }
	check(runDeletions.size.toLong() == footprint.runDeletionCount)
	check(runDeletions.map { it.runIdentity }.distinct().size == runDeletions.size)
	val deletion = loadEntryDeletion(sqlite, identity)
	check((deletion != null) == requireEntryDeletion)
	deletion?.let { authenticateEntryDeletion(it, fences, runDeletions) }
	return FullClearPermanentCounts(
		entryDeletionCount = if (deletion == null) {
			0L
		} else {
			1L
		},
		identityFenceCount = fences.size.toLong(),
		runDeletionCount = runDeletions.size.toLong(),
	)
}

private fun authenticateLocalFenceOwners(
	sqlite: SupportSQLiteDatabase,
	expectedCount: Long,
) {
	var count = 0L
	var ownerRowId = 0L
	while (true) {
		val nextOwnerRowId = sqlite.query(
			"SELECT rowid FROM source_deletion_fence WHERE source_kind = " +
				"${SourceDestinationOwnerEntity.SOURCE_PRESSURE} " +
				"AND purpose = 'SESSION_CAPTURE' AND scope_kind = 'LOGICAL_SERVICE_RUN' " +
				"AND rowid > ? ORDER BY rowid LIMIT 1",
			arrayOf(ownerRowId),
		).use { cursor -> if (cursor.moveToFirst()) {
			cursor.getLong(0)
		} else {
			null
		} } ?: break
		check(nextOwnerRowId > ownerRowId)
		val footprint = sqlite.query(
			"SELECT COUNT(*), COALESCE(SUM(LENGTH(CAST(purpose AS BLOB)) + " +
				"LENGTH(CAST(scope_kind AS BLOB)) + " +
				"LENGTH(CAST(scope_identity_digest AS BLOB)) + " +
				"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
				"FROM source_deletion_fence WHERE rowid = ? AND source_kind = " +
				"${SourceDestinationOwnerEntity.SOURCE_PRESSURE} " +
				"AND purpose = 'SESSION_CAPTURE' AND scope_kind = 'LOGICAL_SERVICE_RUN'",
			arrayOf(nextOwnerRowId),
		).use { cursor ->
			check(cursor.moveToFirst())
			FullClearLocalFenceFootprint(cursor.getLong(0), cursor.getLong(1))
		}
		footprint.requireWithinBounds()
		loadSourceDeletionFenceByRowId(sqlite, nextOwnerRowId)
		count = Math.addExact(count, 1L)
		check(count <= MAX_LOCAL_FENCES)
		ownerRowId = nextOwnerRowId
	}
	check(count == expectedCount)
}

private fun loadSourceDeletionFenceByRowId(
	sqlite: SupportSQLiteDatabase,
	ownerRowId: Long,
): SourceDeletionFenceEntity = sqlite.query(
	"SELECT source_kind, purpose, scope_kind, scope_identity_digest, fence_generation, " +
		"collected_data_epoch, deleted_at_ms, effect_checksum FROM source_deletion_fence " +
		"WHERE rowid = ? AND source_kind = ${SourceDestinationOwnerEntity.SOURCE_PRESSURE} " +
		"AND purpose = 'SESSION_CAPTURE' AND scope_kind = 'LOGICAL_SERVICE_RUN'",
	arrayOf(ownerRowId),
).use { cursor ->
	check(cursor.moveToFirst())
	SourceDeletionFenceEntity(
		sourceKind = cursor.getInt(cursor.getColumnIndexOrThrow("source_kind")),
		purpose = cursor.getString(cursor.getColumnIndexOrThrow("purpose")),
		scopeKind = cursor.getString(cursor.getColumnIndexOrThrow("scope_kind")),
		scopeIdentityDigest = cursor.getString(cursor.getColumnIndexOrThrow("scope_identity_digest")),
		fenceGeneration = cursor.getLong(cursor.getColumnIndexOrThrow("fence_generation")),
		collectedDataEpoch = cursor.getLong(cursor.getColumnIndexOrThrow("collected_data_epoch")),
		deletedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at_ms")),
		effectChecksum = cursor.getString(cursor.getColumnIndexOrThrow("effect_checksum")),
	)
}

private object PressureFullClearLineages {
	/** Authenticates complete saved revisions before deriving permanent entry, run and window identities. */
	fun live(
		sqlite: SupportSQLiteDatabase,
		identity: String,
		expectedEpoch: Long,
		footprint: FullClearLineageFootprint,
	): FullClearLineage {
		val headers = loadHeaders(sqlite, identity)
		requireLiveHeaders(sqlite, identity, expectedEpoch, headers, footprint.headerCount)
		val receipts = loadReceipts(sqlite, identity)
		requireLiveReceipts(headers, receipts, footprint.receiptCount)
		val runs = loadRuns(sqlite, identity, footprint.runCount)
		val windows = loadWindows(sqlite, identity, footprint.windowCount)
		val lineage = ImportedPressureLineageAuthenticator.authenticate(
			identity = identity,
			expectedCollectedDataEpoch = expectedEpoch,
			retainedState = sqlite.retainedEvidenceState(expectedEpoch),
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

	/** Verifies retained receipts, protected identities and deletion checksums after payload retention. */
	fun retained(
		sqlite: SupportSQLiteDatabase,
		identity: String,
		expectedEpoch: Long,
		footprint: FullClearRetainedFootprint,
	): FullClearRetainedLineage {
		val receipt = loadRetentionReceipt(sqlite, identity)
		check(sqlite.acceptsRetainedEpoch(receipt.collectedDataEpoch, expectedEpoch))
		check(footprint.markerCount == receipt.protectedIdentityCount.toLong())
		val markers = sqlite.query(
			"SELECT protected_identity, entry_identity, identity_kind " +
				"FROM imported_pressure_retained_identity WHERE entry_identity = ? " +
				"ORDER BY identity_kind, protected_identity LIMIT ?",
			arrayOf<Any?>(identity, receipt.protectedIdentityCount + 1),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						ImportedPressureRetainedIdentityEntity(
							cursor.getString(cursor.getColumnIndexOrThrow("protected_identity")),
							cursor.getString(cursor.getColumnIndexOrThrow("entry_identity")),
							cursor.getString(cursor.getColumnIndexOrThrow("identity_kind")),
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

	private fun loadHeaders(
		sqlite: SupportSQLiteDatabase,
		identity: String,
	): List<ImportedPressureEntryRevisionEntity> = sqlite.query(
			"SELECT identity, import_revision, supersedes_import_revision, content_checksum, " +
				"source_format, source_schema_version, start_time_ms, end_time_ms, " +
				"collected_data_epoch, import_job_id, import_entry_key, import_source_name, " +
				"received_at_ms FROM imported_pressure_entry_revision WHERE identity = ? " +
				"ORDER BY import_revision LIMIT ?",
			arrayOf<Any?>(identity, MAX_REVISIONS + 1),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						ImportedPressureEntryRevisionEntity(
							identity = cursor.getString(cursor.getColumnIndexOrThrow("identity")),
							importRevision = cursor.getLong(cursor.getColumnIndexOrThrow("import_revision")),
							supersedesImportRevision =
								if (cursor.isNull(cursor.getColumnIndexOrThrow("supersedes_import_revision"))) {
									null
								} else {
									cursor.getLong(cursor.getColumnIndexOrThrow("supersedes_import_revision"))
								},
							contentChecksum = cursor.getString(cursor.getColumnIndexOrThrow("content_checksum")),
							sourceFormat = cursor.getString(cursor.getColumnIndexOrThrow("source_format")),
							sourceSchemaVersion = cursor.getInt(cursor.getColumnIndexOrThrow("source_schema_version")),
							startTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("start_time_ms")),
							endTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("end_time_ms")),
							collectedDataEpoch = cursor.getLong(cursor.getColumnIndexOrThrow("collected_data_epoch")),
							importJobId = cursor.getString(cursor.getColumnIndexOrThrow("import_job_id")),
							importEntryKey = cursor.getString(cursor.getColumnIndexOrThrow("import_entry_key")),
							importSourceName = cursor.getString(cursor.getColumnIndexOrThrow("import_source_name")),
							receivedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("received_at_ms")),
						),
					)
				}
			}
		}

	private fun requireLiveHeaders(
		sqlite: SupportSQLiteDatabase,
		identity: String,
		expectedEpoch: Long,
		headers: List<ImportedPressureEntryRevisionEntity>,
		expectedCount: Long,
	) {
		check(headers.size.toLong() == expectedCount && headers.isNotEmpty())
		headers.forEachIndexed { index, header ->
			val revision = index.toLong() + 1L
			check(header.identity == identity && header.importRevision == revision)
			check(header.supersedesImportRevision == if (revision == 1L) {
				null
			} else {
				revision - 1L
			})
			// The full-clear entry point requires the current operation epoch. These are saved rows.
			check(sqlite.acceptsRetainedEpoch(header.collectedDataEpoch, expectedEpoch))
		}
		check(headers.zipWithNext().all { (previous, next) ->
			next.receivedAtMs >= previous.receivedAtMs
		})
	}

	private fun loadReceipts(
		sqlite: SupportSQLiteDatabase,
		identity: String,
	): List<ImportedPressureReceiptEntity> = sqlite.query(
			"SELECT import_job_id, import_entry_key, import_source_name, received_at_ms, " +
				"entry_identity, entry_import_revision, entry_content_checksum, collected_data_epoch " +
				"FROM imported_pressure_receipt WHERE entry_identity = ? " +
				"ORDER BY entry_import_revision, import_job_id, import_entry_key LIMIT ?",
			arrayOf<Any?>(identity, MAX_RECEIPTS + 1),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						ImportedPressureReceiptEntity(
							importJobId = cursor.getString(cursor.getColumnIndexOrThrow("import_job_id")),
							importEntryKey = cursor.getString(cursor.getColumnIndexOrThrow("import_entry_key")),
							importSourceName = cursor.getString(cursor.getColumnIndexOrThrow("import_source_name")),
							receivedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("received_at_ms")),
							entryIdentity = cursor.getString(cursor.getColumnIndexOrThrow("entry_identity")),
							entryImportRevision = cursor.getLong(cursor.getColumnIndexOrThrow("entry_import_revision")),
							entryContentChecksum =
								cursor.getString(cursor.getColumnIndexOrThrow("entry_content_checksum")),
							collectedDataEpoch = cursor.getLong(cursor.getColumnIndexOrThrow("collected_data_epoch")),
						),
					)
				}
			}
		}

	private fun requireLiveReceipts(
		headers: List<ImportedPressureEntryRevisionEntity>,
		receipts: List<ImportedPressureReceiptEntity>,
		expectedCount: Long,
	) {
		check(receipts.size.toLong() == expectedCount)
		check(receipts.map { it.importJobId to it.importEntryKey }.distinct().size == receipts.size)
		headers.forEach { header ->
			check(receipts.any {
				it.matchesHeader(header)
			})
		}
	}

	private fun ImportedPressureReceiptEntity.matchesHeader(header: ImportedPressureEntryRevisionEntity): Boolean =
		importJobId == header.importJobId &&
			importEntryKey == header.importEntryKey &&
			importSourceName == header.importSourceName &&
			receivedAtMs == header.receivedAtMs &&
			entryIdentity == header.identity &&
			entryImportRevision == header.importRevision &&
			entryContentChecksum == header.contentChecksum &&
			collectedDataEpoch == header.collectedDataEpoch

	private fun loadRuns(
		sqlite: SupportSQLiteDatabase,
		identity: String,
		expectedCount: Long,
	): List<ImportedPressureRunEntity> = sqlite.query(
		"SELECT entry_identity, entry_import_revision, identity, start_time_ms, end_time_ms, " +
			"captured_for_whole_run, availability, coverage, retention_loss, collected_data_epoch, " +
			"scope_deletion_generation FROM imported_pressure_run WHERE entry_identity = ? " +
			"ORDER BY entry_import_revision, start_time_ms, identity LIMIT ?",
		arrayOf<Any?>(identity, MAX_RUN_ROWS + 1),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					ImportedPressureRunEntity(
						entryIdentity = cursor.getString(cursor.getColumnIndexOrThrow("entry_identity")),
						entryImportRevision = cursor.getLong(cursor.getColumnIndexOrThrow("entry_import_revision")),
						identity = cursor.getString(cursor.getColumnIndexOrThrow("identity")),
						startTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("start_time_ms")),
						endTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("end_time_ms")),
						capturedForWholeRun =
							cursor.getInt(cursor.getColumnIndexOrThrow("captured_for_whole_run")) != 0,
						availability = cursor.getString(cursor.getColumnIndexOrThrow("availability")),
						coverage = cursor.getString(cursor.getColumnIndexOrThrow("coverage")),
						retentionLoss = cursor.getInt(cursor.getColumnIndexOrThrow("retention_loss")) != 0,
						collectedDataEpoch = cursor.getLong(cursor.getColumnIndexOrThrow("collected_data_epoch")),
						scopeDeletionGeneration =
							cursor.getLong(cursor.getColumnIndexOrThrow("scope_deletion_generation")),
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
		arrayOf<Any?>(identity, MAX_WINDOW_ROWS + 1),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					ImportedPressureWindowEntity(
						entryIdentity = cursor.getString(cursor.getColumnIndexOrThrow("entry_identity")),
						entryImportRevision = cursor.getLong(cursor.getColumnIndexOrThrow("entry_import_revision")),
						runIdentity = cursor.getString(cursor.getColumnIndexOrThrow("run_identity")),
						identity = cursor.getString(cursor.getColumnIndexOrThrow("identity")),
						contentChecksum = cursor.getString(cursor.getColumnIndexOrThrow("content_checksum")),
						intervalStartTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("interval_start_time_ms")),
						intervalEndTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("interval_end_time_ms")),
						wallTimeUncertaintyMs =
							cursor.getLong(cursor.getColumnIndexOrThrow("wall_time_uncertainty_ms")),
						observedDurationNanos = cursor.getLong(cursor.getColumnIndexOrThrow("observed_duration_nanos")),
						sampleCount = cursor.getInt(cursor.getColumnIndexOrThrow("sample_count")),
						expectedSampleCount = cursor.getInt(cursor.getColumnIndexOrThrow("expected_sample_count")),
						meanHectopascals = cursor.getDouble(cursor.getColumnIndexOrThrow("mean_hectopascals")),
						sumSquaredDeviations = cursor.getDouble(cursor.getColumnIndexOrThrow("sum_squared_deviations")),
						minimumHectopascals = cursor.getFloat(cursor.getColumnIndexOrThrow("minimum_hectopascals")),
						maximumHectopascals = cursor.getFloat(cursor.getColumnIndexOrThrow("maximum_hectopascals")),
						firstHectopascals = cursor.getFloat(cursor.getColumnIndexOrThrow("first_hectopascals")),
						latestHectopascals = cursor.getFloat(cursor.getColumnIndexOrThrow("latest_hectopascals")),
						slopeHectopascalsPerSecond =
							if (cursor.isNull(cursor.getColumnIndexOrThrow("slope_hectopascals_per_second"))) {
								null
							} else {
								cursor.getDouble(cursor.getColumnIndexOrThrow("slope_hectopascals_per_second"))
							},
						rSquared = if (cursor.isNull(cursor.getColumnIndexOrThrow("r_squared"))) {
							null
						} else {
							cursor.getDouble(cursor.getColumnIndexOrThrow("r_squared"))
						},
						sensorAccuracy = cursor.getString(cursor.getColumnIndexOrThrow("sensor_accuracy")),
						effectiveSamplePeriodMicros =
							cursor.getInt(cursor.getColumnIndexOrThrow("effective_sample_period_micros")),
						effectiveMaximumReportLatencyMicros =
							cursor.getInt(cursor.getColumnIndexOrThrow("effective_maximum_report_latency_micros")),
						targetWindowDurationNanos =
							cursor.getLong(cursor.getColumnIndexOrThrow("target_window_duration_nanos")),
						maximumInterSampleGapNanos =
							cursor.getLong(cursor.getColumnIndexOrThrow("maximum_inter_sample_gap_nanos")),
						closureKind = cursor.getString(cursor.getColumnIndexOrThrow("closure_kind")),
						qualification = cursor.getString(cursor.getColumnIndexOrThrow("qualification")),
						sourceQualityFlags = cursor.getLong(cursor.getColumnIndexOrThrow("source_quality_flags")),
						sourceQualityConfidence =
							if (cursor.isNull(cursor.getColumnIndexOrThrow("source_quality_confidence"))) {
								null
							} else {
								cursor.getFloat(cursor.getColumnIndexOrThrow("source_quality_confidence"))
							},
						storedZoneId =
							cursor.getString(cursor.getColumnIndexOrThrow("stored_zone_id")).also { ZoneId.of(it) },
					),
				)
			}
		}.also { check(it.size.toLong() == expectedCount) }
	}

	private fun loadRetentionReceipt(
		sqlite: SupportSQLiteDatabase,
		identity: String,
	): ImportedPressureRetentionReceiptEntity = sqlite.query(
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
				entryIdentity = cursor.getString(cursor.getColumnIndexOrThrow("entry_identity")),
				collectedDataEpoch = cursor.getLong(cursor.getColumnIndexOrThrow("collected_data_epoch")),
				sourceEvidenceRevision = cursor.getLong(cursor.getColumnIndexOrThrow("source_evidence_revision")),
				retainedFromMs = cursor.getLong(cursor.getColumnIndexOrThrow("retained_from_ms")),
				retainedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("retained_at_ms")),
				latestImportRevision = cursor.getLong(cursor.getColumnIndexOrThrow("latest_import_revision")),
				latestContentChecksum = cursor.getString(cursor.getColumnIndexOrThrow("latest_content_checksum")),
				startTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("start_time_ms")),
				endTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("end_time_ms")),
				receivedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("received_at_ms")),
				recencyStartTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("recency_start_time_ms")),
				recencyEndTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("recency_end_time_ms")),
				recencyTieIdentity = cursor.getString(cursor.getColumnIndexOrThrow("recency_tie_identity")),
				revisionCount = cursor.getInt(cursor.getColumnIndexOrThrow("revision_count")),
				importReceiptCount = cursor.getInt(cursor.getColumnIndexOrThrow("import_receipt_count")),
				runRowCount = cursor.getInt(cursor.getColumnIndexOrThrow("run_row_count")),
				windowRowCount = cursor.getInt(cursor.getColumnIndexOrThrow("window_row_count")),
				runDeletionCount = cursor.getInt(cursor.getColumnIndexOrThrow("run_deletion_count")),
				runDeletionSetChecksum = cursor.getString(cursor.getColumnIndexOrThrow("run_deletion_set_checksum")),
				protectedIdentityCount = cursor.getInt(cursor.getColumnIndexOrThrow("protected_identity_count")),
				protectedIdentitySetChecksum =
					cursor.getString(cursor.getColumnIndexOrThrow("protected_identity_set_checksum")),
				identityFenceSetChecksum =
					cursor.getString(cursor.getColumnIndexOrThrow("identity_fence_set_checksum")),
				lineageAuthorityChecksum = cursor.getString(cursor.getColumnIndexOrThrow("lineage_authority_checksum")),
				effectChecksum = cursor.getString(cursor.getColumnIndexOrThrow("effect_checksum")),
			)
		}
}

private fun loadAndAuthenticateEntryDeletion(
	sqlite: SupportSQLiteDatabase,
	identity: String,
): ImportedPressureEntryDeletionEntity {
	val deletion = checkNotNull(loadEntryDeletion(sqlite, identity))
	val fences = sqlite.query(
		"SELECT protected_identity FROM imported_pressure_identity_fence " +
			"WHERE entry_identity = ? ORDER BY protected_identity LIMIT ?",
		arrayOf<Any?>(identity, deletion.identityFenceCount + 1),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(loadIdentityFence(sqlite, cursor.getString(0)))
		}
	}
	val runDeletions = fences.filter {
		it.identityKind == ImportedPressureIdentityFenceEntity.RUN
	}.mapNotNull { loadRunDeletion(sqlite, it.protectedIdentity) }
	authenticateEntryDeletion(deletion, fences, runDeletions)
	return deletion
}

private fun loadEntryDeletion(
	sqlite: SupportSQLiteDatabase,
	identity: String,
): ImportedPressureEntryDeletionEntity? = sqlite.query(
		"SELECT entry_identity, collected_data_epoch, deleted_import_revision, deleted_at_ms, " +
			"run_deletion_count, run_deletion_set_checksum, identity_fence_count, " +
			"identity_fence_set_checksum, effect_checksum FROM imported_pressure_entry_deletion " +
			"WHERE entry_identity = ?",
		arrayOf(identity),
	).use { cursor ->
		if (!cursor.moveToFirst()) {
			null
		} else {
			ImportedPressureEntryDeletionEntity(
			entryIdentity = cursor.getString(cursor.getColumnIndexOrThrow("entry_identity")),
			collectedDataEpoch = cursor.getLong(cursor.getColumnIndexOrThrow("collected_data_epoch")),
			deletedImportRevision = cursor.getLong(cursor.getColumnIndexOrThrow("deleted_import_revision")),
			deletedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at_ms")),
			runDeletionCount = cursor.getInt(cursor.getColumnIndexOrThrow("run_deletion_count")),
			runDeletionSetChecksum = cursor.getString(cursor.getColumnIndexOrThrow("run_deletion_set_checksum")),
			identityFenceCount = cursor.getInt(cursor.getColumnIndexOrThrow("identity_fence_count")),
			identityFenceSetChecksum = cursor.getString(cursor.getColumnIndexOrThrow("identity_fence_set_checksum")),
			effectChecksum = cursor.getString(cursor.getColumnIndexOrThrow("effect_checksum")),
		)
		}
	}

private fun authenticateEntryDeletion(
	deletion: ImportedPressureEntryDeletionEntity,
	fences: List<ImportedPressureIdentityFenceEntity>,
	runDeletions: List<ImportedPressureDeletionGenerationEntity>,
) {
	check(fences.size == deletion.identityFenceCount)
	check(ImportedPressureIdentityFenceEntity.checksumSet(fences) == deletion.identityFenceSetChecksum)
	check(runDeletions.size == deletion.runDeletionCount)
	check(
		ImportedPressureRetentionReceiptEntity.checksumRunDeletions(runDeletions) ==
			deletion.runDeletionSetChecksum,
	)
}

private object PressureFullClearSourceErase {
	/** Verifies the existing erase receipt and its referenced authorities before full-clear removal. */
	fun authenticate(
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
		val marker = loadMarker(sqlite)
		check(sqlite.acceptsRetainedEpoch(marker.collectedDataEpoch, expectedEpoch))
		val witnessCount = sqlite.query(
			"SELECT COUNT(*) FROM imported_pressure_source_erase_witness",
		).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
		check(witnessCount == (
			marker.fencedLocalRunCount + marker.legacySampleCount + marker.entryDeletionCount +
				marker.runDeletionCount + marker.identityFenceCount
			).toLong())
		val witnesses = loadWitnesses(sqlite)
		requireWitnessKinds(marker, witnesses)
		authenticateWitnessAuthorities(sqlite, marker, witnesses)
	}

	private fun loadMarker(sqlite: SupportSQLiteDatabase): ImportedPressureSourceEraseEntity = sqlite.query(
			"SELECT id, collected_data_epoch, source_evidence_revision, erased_at_ms, " +
				"provider_registration_generation, legacy_write_fence_owner, " +
				"legacy_write_fence_generation, " +
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
				id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
				collectedDataEpoch = cursor.getLong(cursor.getColumnIndexOrThrow("collected_data_epoch")),
				sourceEvidenceRevision = cursor.getLong(cursor.getColumnIndexOrThrow("source_evidence_revision")),
				erasedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("erased_at_ms")),
				providerRegistrationGeneration =
					if (cursor.isNull(cursor.getColumnIndexOrThrow("provider_registration_generation"))) {
						null
					} else {
						cursor.getLong(cursor.getColumnIndexOrThrow("provider_registration_generation"))
					},
				legacyWriteFenceOwner = if (cursor.isNull(cursor.getColumnIndexOrThrow("legacy_write_fence_owner"))) {
					null
				} else {
					cursor.getString(cursor.getColumnIndexOrThrow("legacy_write_fence_owner"))
				},
				legacyWriteFenceGeneration =
					cursor.getLong(cursor.getColumnIndexOrThrow("legacy_write_fence_generation")),
				localFactRevisionCount = cursor.getInt(cursor.getColumnIndexOrThrow("local_fact_revision_count")),
				localWalEventCount = cursor.getInt(cursor.getColumnIndexOrThrow("local_wal_event_count")),
				legacySampleCount = cursor.getInt(cursor.getColumnIndexOrThrow("legacy_sample_count")),
				legacySampleSetChecksum = cursor.getString(cursor.getColumnIndexOrThrow("legacy_sample_set_checksum")),
				importedEntryCount = cursor.getInt(cursor.getColumnIndexOrThrow("imported_entry_count")),
				importedRevisionCount = cursor.getInt(cursor.getColumnIndexOrThrow("imported_revision_count")),
				importedRunCount = cursor.getInt(cursor.getColumnIndexOrThrow("imported_run_count")),
				importedWindowCount = cursor.getInt(cursor.getColumnIndexOrThrow("imported_window_count")),
				fencedLocalRunCount = cursor.getInt(cursor.getColumnIndexOrThrow("fenced_local_run_count")),
				localScopeSetChecksum = cursor.getString(cursor.getColumnIndexOrThrow("local_scope_set_checksum")),
				entryDeletionCount = cursor.getInt(cursor.getColumnIndexOrThrow("entry_deletion_count")),
				entryDeletionSetChecksum =
					cursor.getString(cursor.getColumnIndexOrThrow("entry_deletion_set_checksum")),
				runDeletionCount = cursor.getInt(cursor.getColumnIndexOrThrow("run_deletion_count")),
				runDeletionSetChecksum = cursor.getString(cursor.getColumnIndexOrThrow("run_deletion_set_checksum")),
				identityFenceCount = cursor.getInt(cursor.getColumnIndexOrThrow("identity_fence_count")),
				identityFenceSetChecksum =
					cursor.getString(cursor.getColumnIndexOrThrow("identity_fence_set_checksum")),
				effectChecksum = cursor.getString(cursor.getColumnIndexOrThrow("effect_checksum")),
			)
		}

	private fun loadWitnesses(
		sqlite: SupportSQLiteDatabase,
	): List<ImportedPressureSourceEraseWitnessEntity> = sqlite.query(
			"SELECT source_erase_id, witness_kind, witness_identity, authority_checksum, effect_checksum " +
				"FROM imported_pressure_source_erase_witness ORDER BY witness_kind, witness_identity",
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						ImportedPressureSourceEraseWitnessEntity(
							sourceEraseId = cursor.getInt(cursor.getColumnIndexOrThrow("source_erase_id")),
							witnessKind = cursor.getString(cursor.getColumnIndexOrThrow("witness_kind")),
							witnessIdentity = cursor.getString(cursor.getColumnIndexOrThrow("witness_identity")),
							authorityChecksum = cursor.getString(cursor.getColumnIndexOrThrow("authority_checksum")),
							effectChecksum = cursor.getString(cursor.getColumnIndexOrThrow("effect_checksum")),
						),
					)
				}
			}
		}

	private fun requireWitnessKinds(
		marker: ImportedPressureSourceEraseEntity,
		witnesses: List<ImportedPressureSourceEraseWitnessEntity>,
	) {
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
	}

	private fun authenticateWitnessAuthorities(
		sqlite: SupportSQLiteDatabase,
		marker: ImportedPressureSourceEraseEntity,
		witnesses: List<ImportedPressureSourceEraseWitnessEntity>,
	) {
		val localFences = mutableListOf<SourceDeletionFenceEntity>()
		val entryDeletions = mutableListOf<ImportedPressureEntryDeletionEntity>()
		val runDeletions = mutableListOf<ImportedPressureDeletionGenerationEntity>()
		val identityFences = mutableListOf<ImportedPressureIdentityFenceEntity>()
		witnesses.forEach { witness ->
			val actual = when (witness.witnessKind) {
				ImportedPressureSourceEraseWitnessEntity.LOCAL_SCOPE -> {
					val fence = loadLocalFence(sqlite, witness.witnessIdentity)
					localFences += fence
					fence.effectChecksum
				}
				ImportedPressureSourceEraseWitnessEntity.LEGACY_SAMPLE -> witness.authorityChecksum
				ImportedPressureSourceEraseWitnessEntity.ENTRY_DELETION -> {
					val deletion = loadAndAuthenticateEntryDeletion(sqlite, witness.witnessIdentity)
					entryDeletions += deletion
					deletion.effectChecksum
				}
				ImportedPressureSourceEraseWitnessEntity.RUN_DELETION -> {
					val deletion = requireNotNull(loadRunDeletion(sqlite, witness.witnessIdentity))
					runDeletions += deletion
					deletion.effectChecksum
				}
				ImportedPressureSourceEraseWitnessEntity.IDENTITY_FENCE -> {
					val fence = loadIdentityFence(sqlite, witness.witnessIdentity)
					identityFences += fence
					fence.effectChecksum
				}
				else -> error("Unknown imported Pressure erase witness")
			}
			check(actual == witness.authorityChecksum)
		}
		requireWitnessSets(marker, localFences, entryDeletions, runDeletions, identityFences)
	}

	private fun loadLocalFence(
		sqlite: SupportSQLiteDatabase,
		identity: String,
	): SourceDeletionFenceEntity = sqlite.query(
			"SELECT source_kind, purpose, scope_kind, scope_identity_digest, " +
				"fence_generation, collected_data_epoch, deleted_at_ms, effect_checksum " +
				"FROM source_deletion_fence WHERE source_kind = " +
				"${SourceDestinationOwnerEntity.SOURCE_PRESSURE} " +
				"AND purpose = 'SESSION_CAPTURE' AND scope_kind = 'LOGICAL_SERVICE_RUN' " +
				"AND scope_identity_digest = ?",
			arrayOf(identity),
		).use { value ->
			check(value.moveToFirst())
			SourceDeletionFenceEntity(
				sourceKind = value.getInt(value.getColumnIndexOrThrow("source_kind")),
				purpose = value.getString(value.getColumnIndexOrThrow("purpose")),
				scopeKind = value.getString(value.getColumnIndexOrThrow("scope_kind")),
				scopeIdentityDigest = value.getString(value.getColumnIndexOrThrow("scope_identity_digest")),
				fenceGeneration = value.getLong(value.getColumnIndexOrThrow("fence_generation")),
				collectedDataEpoch = value.getLong(value.getColumnIndexOrThrow("collected_data_epoch")),
				deletedAtMs = value.getLong(value.getColumnIndexOrThrow("deleted_at_ms")),
				effectChecksum = value.getString(value.getColumnIndexOrThrow("effect_checksum")),
			)
		}

	private fun requireWitnessSets(
		marker: ImportedPressureSourceEraseEntity,
		localFences: List<SourceDeletionFenceEntity>,
		entryDeletions: List<ImportedPressureEntryDeletionEntity>,
		runDeletions: List<ImportedPressureDeletionGenerationEntity>,
		identityFences: List<ImportedPressureIdentityFenceEntity>,
	) {
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
			SimpleSQLiteQuery.bind(
				statement,
				arrayOf<Any?>(
					fence.protectedIdentity,
					fence.identityKind,
					fence.entryIdentity,
					fence.runIdentity,
					fence.originalCollectedDataEpoch,
					fence.fenceGeneration,
					fence.fencedAtMs,
					fence.fenceReason,
					fence.effectChecksum,
				),
			)
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
		protectedIdentity = cursor.getString(cursor.getColumnIndexOrThrow("protected_identity")),
		identityKind = cursor.getString(cursor.getColumnIndexOrThrow("identity_kind")),
		entryIdentity = cursor.getString(cursor.getColumnIndexOrThrow("entry_identity")),
		runIdentity = if (cursor.isNull(cursor.getColumnIndexOrThrow("run_identity"))) {
			null
		} else {
			cursor.getString(cursor.getColumnIndexOrThrow("run_identity"))
		},
		originalCollectedDataEpoch = cursor.getLong(cursor.getColumnIndexOrThrow("original_collected_data_epoch")),
		fenceGeneration = cursor.getLong(cursor.getColumnIndexOrThrow("fence_generation")),
		fencedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("fenced_at_ms")),
		fenceReason = cursor.getString(cursor.getColumnIndexOrThrow("fence_reason")),
		effectChecksum = cursor.getString(cursor.getColumnIndexOrThrow("effect_checksum")),
	)
}

private fun loadIdentityFenceByRowId(
	sqlite: SupportSQLiteDatabase,
	ownerRowId: Long,
): ImportedPressureIdentityFenceEntity = sqlite.query(
	"SELECT protected_identity, identity_kind, entry_identity, run_identity, " +
		"original_collected_data_epoch, fence_generation, fenced_at_ms, fence_reason, effect_checksum " +
		"FROM imported_pressure_identity_fence WHERE rowid = ?",
	arrayOf(ownerRowId),
).use { cursor ->
	check(cursor.moveToFirst())
	ImportedPressureIdentityFenceEntity(
		protectedIdentity = cursor.getString(cursor.getColumnIndexOrThrow("protected_identity")),
		identityKind = cursor.getString(cursor.getColumnIndexOrThrow("identity_kind")),
		entryIdentity = cursor.getString(cursor.getColumnIndexOrThrow("entry_identity")),
		runIdentity = if (cursor.isNull(cursor.getColumnIndexOrThrow("run_identity"))) {
			null
		} else {
			cursor.getString(cursor.getColumnIndexOrThrow("run_identity"))
		},
		originalCollectedDataEpoch = cursor.getLong(cursor.getColumnIndexOrThrow("original_collected_data_epoch")),
		fenceGeneration = cursor.getLong(cursor.getColumnIndexOrThrow("fence_generation")),
		fencedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("fenced_at_ms")),
		fenceReason = cursor.getString(cursor.getColumnIndexOrThrow("fence_reason")),
		effectChecksum = cursor.getString(cursor.getColumnIndexOrThrow("effect_checksum")),
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
	if (!cursor.moveToFirst()) {
		null
	} else {
		ImportedPressureDeletionGenerationEntity(
		runIdentity = cursor.getString(cursor.getColumnIndexOrThrow("run_identity")),
		collectedDataEpoch = cursor.getLong(cursor.getColumnIndexOrThrow("collected_data_epoch")),
		generation = cursor.getLong(cursor.getColumnIndexOrThrow("generation")),
		deletedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at_ms")),
		effectChecksum = cursor.getString(cursor.getColumnIndexOrThrow("effect_checksum")),
	)
	}
}

private fun readLineageFootprint(
	sqlite: SupportSQLiteDatabase,
	ownerRowId: Long,
): FullClearLineageFootprint = sqlite.query(
	FULL_CLEAR_LIVE_LINEAGE_FOOTPRINT_SQL,
	arrayOf(ownerRowId),
).use { cursor ->
	check(cursor.moveToFirst())
	FullClearLineageFootprint(
		headerCount = cursor.getLong(cursor.getColumnIndexOrThrow("header_count")),
		headerTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("header_text_bytes")),
		receiptCount = cursor.getLong(cursor.getColumnIndexOrThrow("receipt_count")),
		receiptTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("receipt_text_bytes")),
		runCount = cursor.getLong(cursor.getColumnIndexOrThrow("run_count")),
		runTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("run_text_bytes")),
		windowCount = cursor.getLong(cursor.getColumnIndexOrThrow("window_count")),
		windowTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("window_text_bytes")),
		identityFenceCount = cursor.getLong(cursor.getColumnIndexOrThrow("identity_fence_count")),
		identityFenceTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("identity_fence_text_bytes")),
		entryDeletionCount = cursor.getLong(cursor.getColumnIndexOrThrow("entry_deletion_count")),
		entryDeletionTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("entry_deletion_text_bytes")),
		runDeletionCount = cursor.getLong(cursor.getColumnIndexOrThrow("run_deletion_count")),
		runDeletionTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("run_deletion_text_bytes")),
	)
}

private fun readRetainedFootprint(
	sqlite: SupportSQLiteDatabase,
	ownerRowId: Long,
): FullClearRetainedFootprint = sqlite.query(
	FULL_CLEAR_RETAINED_LINEAGE_FOOTPRINT_SQL,
	arrayOf(ownerRowId),
).use { cursor ->
	check(cursor.moveToFirst())
	FullClearRetainedFootprint(
		receiptCount = cursor.getLong(cursor.getColumnIndexOrThrow("receipt_count")),
		receiptTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("receipt_text_bytes")),
		markerCount = cursor.getLong(cursor.getColumnIndexOrThrow("marker_count")),
		markerTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("marker_text_bytes")),
		identityFenceCount = cursor.getLong(cursor.getColumnIndexOrThrow("identity_fence_count")),
		identityFenceTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("identity_fence_text_bytes")),
		entryDeletionCount = cursor.getLong(cursor.getColumnIndexOrThrow("entry_deletion_count")),
		entryDeletionTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("entry_deletion_text_bytes")),
		runDeletionCount = cursor.getLong(cursor.getColumnIndexOrThrow("run_deletion_count")),
		runDeletionTextBytes = cursor.getLong(cursor.getColumnIndexOrThrow("run_deletion_text_bytes")),
	)
}

private fun readGlobalLineageFootprint(
	sqlite: SupportSQLiteDatabase,
): FullClearLineageFootprint = sqlite.query(
	"SELECT " +
		"(SELECT COUNT(*) FROM imported_pressure_entry_revision) AS header_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(identity AS BLOB)) + " +
		"LENGTH(CAST(content_checksum AS BLOB)) + LENGTH(CAST(source_format AS BLOB)) + " +
		"LENGTH(CAST(import_job_id AS BLOB)) + LENGTH(CAST(import_entry_key AS BLOB)) + " +
		"LENGTH(CAST(import_source_name AS BLOB))), 0) FROM imported_pressure_entry_revision) AS header_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_receipt) AS receipt_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(import_job_id AS BLOB)) + " +
		"LENGTH(CAST(import_entry_key AS BLOB)) + LENGTH(CAST(import_source_name AS BLOB)) + " +
		"LENGTH(CAST(entry_identity AS BLOB)) + LENGTH(CAST(entry_content_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_receipt) AS receipt_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_run) AS run_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(identity AS BLOB)) + LENGTH(CAST(availability AS BLOB)) + " +
		"LENGTH(CAST(coverage AS BLOB))), 0) FROM imported_pressure_run) AS run_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_window) AS window_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(run_identity AS BLOB)) + LENGTH(CAST(identity AS BLOB)) + " +
		"LENGTH(CAST(content_checksum AS BLOB)) + LENGTH(CAST(sensor_accuracy AS BLOB)) + " +
		"LENGTH(CAST(closure_kind AS BLOB)) + LENGTH(CAST(qualification AS BLOB)) + " +
		"LENGTH(CAST(stored_zone_id AS BLOB))), 0) FROM imported_pressure_window) AS window_text_bytes",
).use { cursor ->
	check(cursor.moveToFirst())
	FullClearLineageFootprint(
		cursor.getLong(cursor.getColumnIndexOrThrow("header_count")),
		cursor.getLong(cursor.getColumnIndexOrThrow("header_text_bytes")),
		cursor.getLong(cursor.getColumnIndexOrThrow("receipt_count")),
		cursor.getLong(cursor.getColumnIndexOrThrow("receipt_text_bytes")),
		cursor.getLong(cursor.getColumnIndexOrThrow("run_count")),
		cursor.getLong(cursor.getColumnIndexOrThrow("run_text_bytes")),
		cursor.getLong(cursor.getColumnIndexOrThrow("window_count")),
		cursor.getLong(cursor.getColumnIndexOrThrow("window_text_bytes")),
	)
}

private fun readGlobalRetainedFootprint(
	sqlite: SupportSQLiteDatabase,
): FullClearRetainedFootprint = sqlite.query(
	"SELECT " +
		"(SELECT COUNT(*) FROM imported_pressure_retention_receipt) AS receipt_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(latest_content_checksum AS BLOB)) + " +
		"LENGTH(CAST(recency_tie_identity AS BLOB)) + " +
		"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(protected_identity_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(lineage_authority_checksum AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) FROM imported_pressure_retention_receipt) AS receipt_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_retained_identity) AS marker_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(protected_identity AS BLOB)) + " +
		"LENGTH(CAST(entry_identity AS BLOB)) + LENGTH(CAST(identity_kind AS BLOB))), 0) " +
		"FROM imported_pressure_retained_identity) AS marker_text_bytes",
).use { cursor ->
	check(cursor.moveToFirst())
	FullClearRetainedFootprint(
		cursor.getLong(cursor.getColumnIndexOrThrow("receipt_count")),
		cursor.getLong(cursor.getColumnIndexOrThrow("receipt_text_bytes")),
		cursor.getLong(cursor.getColumnIndexOrThrow("marker_count")),
		cursor.getLong(cursor.getColumnIndexOrThrow("marker_text_bytes")),
	)
}

private fun readGlobalPrivacyAuthorityBytes(sqlite: SupportSQLiteDatabase): Long =
	sqlite.query(
		"SELECT " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(protected_identity AS BLOB)) + " +
			"LENGTH(CAST(identity_kind AS BLOB)) + LENGTH(CAST(entry_identity AS BLOB)) + " +
			"COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) + " +
			"LENGTH(CAST(fence_reason AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_identity_fence) AS identity_bytes, " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(witness_kind AS BLOB)) + " +
			"LENGTH(CAST(witness_identity AS BLOB)) + LENGTH(CAST(authority_checksum AS BLOB)) + " +
			"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_source_erase_witness) AS witness_bytes, " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
			"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_entry_deletion) AS entry_deletion_bytes, " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(run_identity AS BLOB)) + " +
			"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_deletion_generation) AS run_deletion_bytes, " +
			"(SELECT COALESCE(SUM(LENGTH(CAST(legacy_sample_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(COALESCE(legacy_write_fence_owner, '') AS BLOB)) + " +
			"LENGTH(CAST(local_scope_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(entry_deletion_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
			"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
			"FROM imported_pressure_source_erase) AS source_erase_bytes",
	).use { cursor ->
		check(cursor.moveToFirst())
		val identityBytes = cursor.getLong(cursor.getColumnIndexOrThrow("identity_bytes"))
		val witnessBytes = cursor.getLong(cursor.getColumnIndexOrThrow("witness_bytes"))
		val entryDeletionBytes = cursor.getLong(cursor.getColumnIndexOrThrow("entry_deletion_bytes"))
		val runDeletionBytes = cursor.getLong(cursor.getColumnIndexOrThrow("run_deletion_bytes"))
		val sourceEraseBytes = cursor.getLong(cursor.getColumnIndexOrThrow("source_erase_bytes"))
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

private fun readGlobalLocalFenceFootprint(
	sqlite: SupportSQLiteDatabase,
): FullClearLocalFenceFootprint = sqlite.query(
	"SELECT COUNT(*), COALESCE(SUM(LENGTH(CAST(purpose AS BLOB)) + " +
		"LENGTH(CAST(scope_kind AS BLOB)) + LENGTH(CAST(scope_identity_digest AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) FROM source_deletion_fence " +
		"WHERE source_kind = ${SourceDestinationOwnerEntity.SOURCE_PRESSURE} " +
		"AND purpose = 'SESSION_CAPTURE' AND scope_kind = 'LOGICAL_SERVICE_RUN'",
).use { cursor ->
	check(cursor.moveToFirst())
	FullClearLocalFenceFootprint(cursor.getLong(0), cursor.getLong(1))
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

private data class FullClearPermanentCounts(
	val entryDeletionCount: Long = 0L,
	val identityFenceCount: Long = 0L,
	val runDeletionCount: Long = 0L,
) {
	fun plus(other: FullClearPermanentCounts) = FullClearPermanentCounts(
		entryDeletionCount = Math.addExact(entryDeletionCount, other.entryDeletionCount),
		identityFenceCount = Math.addExact(identityFenceCount, other.identityFenceCount),
		runDeletionCount = Math.addExact(runDeletionCount, other.runDeletionCount),
	)
}

private data class FullClearPermanentOwnerFootprint(
	val entryDeletionCount: Long,
	val entryDeletionTextBytes: Long,
	val entryMarkerCount: Long,
	val identityFenceCount: Long,
	val identityFenceTextBytes: Long,
	val runDeletionCount: Long,
	val runDeletionTextBytes: Long,
) {
	val totalTextBytes: Long
		get() = Math.addExact(
			entryDeletionTextBytes,
			Math.addExact(identityFenceTextBytes, runDeletionTextBytes),
		)

	fun requireWithinBounds(requireEntryDeletion: Boolean) {
		check(listOf(
			entryDeletionTextBytes,
			identityFenceTextBytes,
			runDeletionTextBytes,
		).all { it >= 0L })
		check(totalTextBytes <= MAX_LINEAGE_TEXT_BYTES)
		check(entryDeletionCount == if (requireEntryDeletion) {
			1L
		} else {
			0L
		})
		check(entryMarkerCount == 1L)
		check(identityFenceCount in 1L..MAX_IDENTITY_FENCES.toLong())
		check(runDeletionCount in 0L..MAX_RUN_DELETIONS.toLong())
	}
}

private data class FullClearLocalFenceFootprint(
	val rowCount: Long,
	val textBytes: Long,
) {
	fun requireWithinBounds() {
		check(rowCount == 1L)
		check(textBytes in 0L..MAX_LINEAGE_TEXT_BYTES)
	}
}

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

private const val FULL_CLEAR_LIVE_LINEAGE_FOOTPRINT_SQL =
	"WITH owner(identity) AS (" +
		"SELECT identity FROM imported_pressure_entry_revision WHERE rowid = ?" +
		") SELECT " +
		"(SELECT COUNT(*) FROM imported_pressure_entry_revision " +
		"WHERE identity = (SELECT identity FROM owner)) AS header_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(identity AS BLOB)) + " +
		"LENGTH(CAST(content_checksum AS BLOB)) + LENGTH(CAST(source_format AS BLOB)) + " +
		"LENGTH(CAST(import_job_id AS BLOB)) + LENGTH(CAST(import_entry_key AS BLOB)) + " +
		"LENGTH(CAST(import_source_name AS BLOB))), 0) " +
		"FROM imported_pressure_entry_revision WHERE identity = (SELECT identity FROM owner)) AS header_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_receipt " +
		"WHERE entry_identity = (SELECT identity FROM owner)) AS receipt_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(import_job_id AS BLOB)) + " +
		"LENGTH(CAST(import_entry_key AS BLOB)) + LENGTH(CAST(import_source_name AS BLOB)) + " +
		"LENGTH(CAST(entry_identity AS BLOB)) + LENGTH(CAST(entry_content_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_receipt WHERE entry_identity = (SELECT identity FROM owner)) AS receipt_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_run " +
		"WHERE entry_identity = (SELECT identity FROM owner)) AS run_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(identity AS BLOB)) + LENGTH(CAST(availability AS BLOB)) + " +
		"LENGTH(CAST(coverage AS BLOB))), 0) FROM imported_pressure_run " +
		"WHERE entry_identity = (SELECT identity FROM owner)) AS run_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_window " +
		"WHERE entry_identity = (SELECT identity FROM owner)) AS window_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(run_identity AS BLOB)) + LENGTH(CAST(identity AS BLOB)) + " +
		"LENGTH(CAST(content_checksum AS BLOB)) + LENGTH(CAST(sensor_accuracy AS BLOB)) + " +
		"LENGTH(CAST(closure_kind AS BLOB)) + LENGTH(CAST(qualification AS BLOB)) + " +
		"LENGTH(CAST(stored_zone_id AS BLOB))), 0) " +
		"FROM imported_pressure_window WHERE entry_identity = (SELECT identity FROM owner)) AS window_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT identity FROM owner)) AS identity_fence_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(protected_identity AS BLOB)) + " +
		"LENGTH(CAST(identity_kind AS BLOB)) + LENGTH(CAST(entry_identity AS BLOB)) + " +
		"COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) + " +
		"LENGTH(CAST(fence_reason AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT identity FROM owner)) AS identity_fence_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_entry_deletion " +
		"WHERE entry_identity = (SELECT identity FROM owner)) AS entry_deletion_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_entry_deletion " +
		"WHERE entry_identity = (SELECT identity FROM owner)) AS entry_deletion_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_deletion_generation " +
		"WHERE run_identity IN (SELECT identity FROM imported_pressure_run " +
		"WHERE entry_identity = (SELECT identity FROM owner))) AS run_deletion_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(run_identity AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_deletion_generation " +
		"WHERE run_identity IN (SELECT identity FROM imported_pressure_run " +
		"WHERE entry_identity = (SELECT identity FROM owner))) AS run_deletion_text_bytes"

private const val FULL_CLEAR_RETAINED_LINEAGE_FOOTPRINT_SQL =
	"WITH owner(entry_identity) AS (" +
		"SELECT entry_identity FROM imported_pressure_retention_receipt WHERE rowid = ?" +
		") SELECT " +
		"(SELECT COUNT(*) FROM imported_pressure_retention_receipt " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS receipt_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(latest_content_checksum AS BLOB)) + " +
		"LENGTH(CAST(recency_tie_identity AS BLOB)) + " +
		"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(protected_identity_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(lineage_authority_checksum AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_retention_receipt " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS receipt_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_retained_identity " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS marker_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(protected_identity AS BLOB)) + " +
		"LENGTH(CAST(entry_identity AS BLOB)) + LENGTH(CAST(identity_kind AS BLOB))), 0) " +
		"FROM imported_pressure_retained_identity " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS marker_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS identity_fence_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(protected_identity AS BLOB)) + " +
		"LENGTH(CAST(identity_kind AS BLOB)) + LENGTH(CAST(entry_identity AS BLOB)) + " +
		"COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) + " +
		"LENGTH(CAST(fence_reason AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS identity_fence_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_entry_deletion " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS entry_deletion_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(entry_identity AS BLOB)) + " +
		"LENGTH(CAST(run_deletion_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(identity_fence_set_checksum AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_entry_deletion " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner)) AS entry_deletion_text_bytes, " +
		"(SELECT COUNT(*) FROM imported_pressure_deletion_generation " +
		"WHERE run_identity IN (SELECT protected_identity FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner) AND identity_kind = 'RUN')) AS " +
		"run_deletion_count, " +
		"(SELECT COALESCE(SUM(LENGTH(CAST(run_identity AS BLOB)) + " +
		"LENGTH(CAST(effect_checksum AS BLOB))), 0) " +
		"FROM imported_pressure_deletion_generation " +
		"WHERE run_identity IN (SELECT protected_identity FROM imported_pressure_identity_fence " +
		"WHERE entry_identity = (SELECT entry_identity FROM owner) AND identity_kind = 'RUN')) AS " +
		"run_deletion_text_bytes"

private const val MAX_REVISIONS = 16
private const val MAX_RECEIPTS = 256
private const val MAX_RUNS_PER_REVISION = 64
private const val MAX_WINDOWS_PER_REVISION = 16_384
private const val MAX_RUN_ROWS = MAX_REVISIONS * MAX_RUNS_PER_REVISION
private const val MAX_WINDOW_ROWS = MAX_REVISIONS * MAX_WINDOWS_PER_REVISION
private const val MAX_SOURCE_ENTRIES = 65_536
private const val MAX_LOCAL_FENCES = 65_536
private const val MAX_DELETION_ENTRIES = 65_536
private const val MAX_IDENTITY_FENCES = 524_288
private const val MAX_RUN_DELETIONS = 262_144
private const val MAX_GLOBAL_REVISIONS = 65_536L
private const val MAX_GLOBAL_RECEIPTS = 65_536L
private const val MAX_GLOBAL_RUN_ROWS = 262_144L
private const val MAX_GLOBAL_WINDOW_ROWS = 262_144L
private const val MAX_LINEAGE_TEXT_BYTES = 128L * 1_024L * 1_024L
private const val MAX_MAINTENANCE_TEXT_BYTES = 256L * 1_024L * 1_024L
