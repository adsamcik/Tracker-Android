package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Original contents of one radio file. The payload stays in the existing source revision tables. */
@Entity(tableName = "imported_radio_file_membership",
	primaryKeys = ["source_kind", "import_job_id", "import_entry_key", "member_kind", "member_identity"],
	indices = [Index(value = ["source_kind", "entry_identity", "entry_import_revision"],
		name = "idx_imported_radio_file_entry")])
data class ImportedRadioFileMembership(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "import_job_id") val importJobId: String,
	@ColumnInfo(name = "import_entry_key") val importEntryKey: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "file_entry_checksum") val fileEntryChecksum: String,
	@ColumnInfo(name = "member_kind") val memberKind: String,
	@ColumnInfo(name = "member_identity") val memberIdentity: String,
	@ColumnInfo(name = "member_revision") val memberRevision: Long,
	@ColumnInfo(name = "member_checksum") val memberChecksum: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
) {
	init {
		require(sourceKind == SourceDestinationOwnerEntity.SOURCE_WIFI ||
            sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL)
		require(importJobId.isNotBlank() && importEntryKey.isNotBlank() && entryIdentity.isNotBlank())
		require(entryImportRevision > 0L && memberRevision > 0L && collectedDataEpoch >= 0L)
		require(memberKind in setOf("ENTRY", "OBSERVATION", "GAP"))
		require(memberIdentity.isNotBlank() && fileEntryChecksum.isNotBlank() && memberChecksum.isNotBlank())
	}
}
