package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
	tableName = "import_job_receipt",
	indices = [Index(value = ["status"])],
	primaryKeys = ["job_id"],
)
data class ImportJobReceiptEntity(
	@ColumnInfo(name = "job_id")
	val jobId: String,
	@ColumnInfo(name = "source_name")
	val sourceName: String,
	@ColumnInfo(name = "source_size_bytes")
	val sourceSizeBytes: Long,
	@ColumnInfo(name = "status")
	val status: String,
	@ColumnInfo(name = "started_at")
	val startedAt: Long,
	@ColumnInfo(name = "completed_at")
	val completedAt: Long? = null,
	@ColumnInfo(name = "updated_at")
	val updatedAt: Long,
) {
	companion object {
		const val STATUS_IN_PROGRESS = "IN_PROGRESS"
		const val STATUS_COMPLETE = "COMPLETE"
	}
}

@Entity(
	tableName = "import_entry_receipt",
	foreignKeys = [
		ForeignKey(
			entity = ImportJobReceiptEntity::class,
			parentColumns = ["job_id"],
			childColumns = ["job_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index(value = ["job_id", "status"])],
	primaryKeys = ["job_id", "entry_key"],
)
data class ImportEntryReceiptEntity(
	@ColumnInfo(name = "job_id")
	val jobId: String,
	@ColumnInfo(name = "entry_key")
	val entryKey: String,
	@ColumnInfo(name = "entry_name")
	val entryName: String,
	@ColumnInfo(name = "status")
	val status: String,
	@ColumnInfo(name = "success_count")
	val successCount: Int,
	@ColumnInfo(name = "skipped_count")
	val skippedCount: Int,
	@ColumnInfo(name = "failed_count")
	val failedCount: Int,
	@ColumnInfo(name = "error_message")
	val errorMessage: String? = null,
	@ColumnInfo(name = "updated_at")
	val updatedAt: Long,
) {
	companion object {
		const val STATUS_SUCCESS = "SUCCESS"
		const val STATUS_FAILURE = "FAILURE"
	}
}
