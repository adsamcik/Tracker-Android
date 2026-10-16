package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedRadioFileMembership
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity

/**
 * Original member kind, identity, revision and checksum recorded for an individual-reading radio
 * file.
 */
data class RadioSelectedFileMember(val kind: String, val identity: String, val revision: Long, val checksum: String)

/** Reads original membership, including older complete files, without borrowing later union revisions. */
suspend fun readRadioFileMembers(database: AppDatabase, source: Int, jobId: String, entryKey: String,
	entryId: String, epoch: Long, limit: Int): List<RadioSelectedFileMember> {
	val rows = database.importedRadioFileMembershipDao().members(source, jobId, entryKey, limit + 1)
	if (rows.isNotEmpty()) {
		require(rows.size <= limit && rows.all { it.entryIdentity == entryId && it.collectedDataEpoch == epoch })
		require(rows.count { it.memberKind == "ENTRY" } == 1)
		return rows.filter { it.memberKind != "ENTRY" }.map {
			RadioSelectedFileMember(it.memberKind, it.memberIdentity, it.memberRevision, it.memberChecksum) }
	}
	return when (source) {
		SourceDestinationOwnerEntity.SOURCE_WIFI ->
			readLegacyWifiFileMembers(database, jobId, entryKey, entryId, epoch, limit)
		SourceDestinationOwnerEntity.SOURCE_CELL ->
			readLegacyCellFileMembers(database, jobId, entryKey, entryId, epoch, limit)
		else -> error("Not a radio source")
	}
}

private suspend fun readLegacyWifiFileMembers(
	database: AppDatabase,
	jobId: String,
	entryKey: String,
	entryId: String,
	epoch: Long,
	limit: Int,
): List<RadioSelectedFileMember> {
	val dao = database.importedWifiDao()
	val receipt = requireNotNull(dao.receipt(jobId, entryKey))
	require(receipt.entryIdentity == entryId && receipt.collectedDataEpoch == epoch)
	val observations =
                dao.allObservationsForAdmission(entryId).filter {
                    it.entryImportRevision == receipt.entryImportRevision
                }
	require(observations.size <= limit)
	return (observations.map { RadioSelectedFileMember("OBSERVATION", it.identity, it.semanticRevision,
                it.contentChecksum) } +
		dao.allRunsForAdmission(entryId).filter { it.entryImportRevision == receipt.entryImportRevision &&
			(it.retentionLoss || it.hasUnresolvedProviderRange) }.map { RadioSelectedFileMember("GAP",
                        it.identity,
                        1L, it.contentChecksum) })
		.also { require(it.size <= limit) }
}

private suspend fun readLegacyCellFileMembers(
	database: AppDatabase,
	jobId: String,
	entryKey: String,
	entryId: String,
	epoch: Long,
	limit: Int,
): List<RadioSelectedFileMember> {
	val dao = database.importedCellDao()
	val receipt = requireNotNull(dao.receipt(jobId, entryKey))
	require(receipt.entryIdentity == entryId && receipt.collectedDataEpoch == epoch)
	val observations =
                dao.allObservationsForAdmission(entryId).filter {
                    it.entryImportRevision == receipt.entryImportRevision
                }
	require(observations.size <= limit)
	return (observations.map { RadioSelectedFileMember("OBSERVATION", it.identity, it.semanticRevision,
                it.contentChecksum) } +
		dao.allRunsForAdmission(entryId).filter { it.entryImportRevision == receipt.entryImportRevision &&
                    it.retentionLoss }
			.map { RadioSelectedFileMember("GAP", it.identity, 1L,
                        it.contentChecksum) }).also { require(it.size <= limit) }
}

/**
 * Creates exact entry and member associations for one original radio import job/revision/checksum
 * and data epoch.
 */
fun radioFileMembership(source: Int, jobId: String, entryKey: String, entryId: String, fileChecksum: String,
	importRevision: Long, epoch: Long, members: List<RadioSelectedFileMember>): List<ImportedRadioFileMembership> =
	(listOf(RadioSelectedFileMember("ENTRY", entryId, 1L, fileChecksum)) + members).map { member ->
		ImportedRadioFileMembership(source, jobId, entryKey, entryId, importRevision, fileChecksum,
			member.kind, member.identity, member.revision, member.checksum, epoch)
	}.sortedWith(compareBy(ImportedRadioFileMembership::memberKind, ImportedRadioFileMembership::memberIdentity))

/** Copy only already retained details whose same reading was kept in the new union revision. */
fun copyImportedRadioSelectionChildren(database: AppDatabase, source: Int, entryId: String,
	previousRevision: Long, nextRevision: Long) {
	val prefix = when (source) {
		SourceDestinationOwnerEntity.SOURCE_WIFI -> "wifi"
		SourceDestinationOwnerEntity.SOURCE_CELL -> "cell"
		else -> error("Not a radio source")
	}
	val details = if (prefix == "wifi") {
		"bssid, ssid, frequency_mhz, signal_dbm"
	} else {
		"tower_identity, radio_type, registered, signal_dbm"
	}
	database.openHelper.writableDatabase.execSQL(
		"INSERT OR IGNORE INTO imported_${prefix}_child (entry_identity, entry_import_revision, run_identity, " +
			("observation_identity, item_index, provider_timestamp_nanos, observed_wall_time_ms, " +
                "wall_time_uncertainty_ms, $details, content_checksum) ") +
			"SELECT child.entry_identity, ?, child.run_identity, child.observation_identity, child.item_index, " +
			"child.provider_timestamp_nanos, child.observed_wall_time_ms, child.wall_time_uncertainty_ms, " +
			details.split(", ").joinToString(", ") { "child.$it" } + ", child.content_checksum " +
			"FROM imported_${prefix}_child child JOIN imported_${prefix}_observation previous ON " +
			("previous.entry_identity = child.entry_identity AND previous.entry_import_revision = " +
                "child.entry_import_revision ") +
			"AND previous.run_identity = child.run_identity AND previous.identity = child.observation_identity " +
			"JOIN imported_${prefix}_observation next ON next.entry_identity = previous.entry_identity " +
			("AND next.entry_import_revision = ? AND next.run_identity = previous.run_identity AND " +
                "next.identity = previous.identity ") +
			("AND next.semantic_revision = previous.semantic_revision WHERE child.entry_identity = ? AND " +
                "child.entry_import_revision = ?"),
		arrayOf<Any>(nextRevision, nextRevision, entryId, previousRevision))
}
