package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records metadata about data exports for audit and debugging.
 *
 * Each row represents one export operation, capturing format, scope,
 * file details, and success/failure status. Useful for showing export
 * history to the user and diagnosing export issues.
 */
@Entity(
	tableName = "export_log",
	indices = [
		Index(value = ["completed_at"]),
		Index(value = ["started_at"]),
	]
)
data class ExportLogEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/** Export format (GPX, KML, JSON, DATABASE). */
	@ColumnInfo(name = "format")
	val format: String,

	/** Export scope (LAST_SESSION, ROLLING_WINDOW, ALL, DATE_RANGE). */
	@ColumnInfo(name = "scope")
	val scope: String,

	/** Output file name (without path, for display). */
	@ColumnInfo(name = "file_name")
	val fileName: String,

	/** Output file size in bytes. */
	@ColumnInfo(name = "file_size_bytes")
	val fileSizeBytes: Long,

	/** Number of data records included in the export. */
	@ColumnInfo(name = "record_count")
	val recordCount: Int,

	/** Timestamp when the export started (epoch millis). */
	@ColumnInfo(name = "started_at")
	val startedAt: Long,

	/** Timestamp when the export completed (epoch millis). */
	@ColumnInfo(name = "completed_at")
	val completedAt: Long,

	/** Export result status (SUCCESS, FAILED, PARTIAL). */
	@ColumnInfo(name = "status")
	val status: String,

	/** Error message if status is FAILED or PARTIAL (null on success). */
	@ColumnInfo(name = "error_message")
	val errorMessage: String? = null,

	@ColumnInfo(name = "created_at")
	val createdAt: Long,
)
