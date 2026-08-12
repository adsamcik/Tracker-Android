package com.adsamcik.tracker.shared.base.database.legacy

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory

internal const val LEGACY_IMPORT_JOB_ID = "legacy-database-v26"

private fun canonicalOrLegacy(
	sourceColumns: Set<String>,
	canonicalColumn: String,
	legacyColumn: String,
	legacyExpression: String = "`$legacyColumn`",
): String = if (legacyColumn in sourceColumns) {
	"COALESCE(`$canonicalColumn`, $legacyExpression)"
} else {
	"`$canonicalColumn`"
}

/**
 * Copies the small, frozen set of relevant v26 tables into a newly created v27 schema.
 * This is called from Room's onCreate transaction, so target writes commit atomically.
 */
class LegacyV26Importer(
	context: Context,
	private val publicMigrations: Array<Migration>,
	normalizerOpenHelperFactory: SupportSQLiteOpenHelper.Factory =
		SQLiteXSupportSQLiteOpenHelperFactory(),
) {
	private val repository = LegacyDatabaseRepository(context)
	private val normalizer = LegacyV26DatabaseNormalizer(
		context,
		repository,
		publicMigrations,
		normalizerOpenHelperFactory,
	)

	fun importIfPresent(target: SupportSQLiteDatabase) {
		try {
			repository.withFileFamilyLock {
				// Target-local state is authoritative. This callback only runs while Room creates a
				// fresh target, so stale external preferences must never suppress a required import.
				if (repository.inspect() != null) {
					normalizer.prepare().use { prepared ->
						repository.markRunning(prepared.originalVersion)
						SQLiteDatabase.openDatabase(
							prepared.file.path,
							null,
							SQLiteDatabase.OPEN_READONLY,
						).use { source ->
							validateReleasedSchema(source)
							val imported = LinkedHashMap<String, Long>()
							for (spec in importedTables) {
								imported[spec.name] = copyTable(source, target, spec)
							}
							imported["location_observation"] =
								deriveLegacyLocationObservations(target)
							backfillCanonicalSourceIdentities(target)
							val skipped = skippedTables.associateWith { table ->
								countRows(source, table)
							}
							writeCompletionMarker(
								target = target,
								sourceVersion = prepared.originalVersion,
								sourceSizeBytes = prepared.file.length(),
							)
							repository.markCopied(
								LegacyImportReport(
									sourceVersion = prepared.originalVersion,
									importedRows = imported,
									skippedRows = skipped,
								),
							)
						}
					}
				}
			}
		} catch (error: Throwable) {
			repository.markFailed(error)
			throw error
		}
	}

	/** Preserves the replayable raw-evidence row formerly derived by migration 35 -> 36. */
	private fun deriveLegacyLocationObservations(target: SupportSQLiteDatabase): Long {
		val expectedRows = countRows(target, "location_sample")
		target.execSQL(
			"""
			INSERT INTO location_observation (
				fix_time_ms,
				fix_elapsed_realtime_nanos,
				received_at_ms,
				received_elapsed_realtime_nanos,
				delivery_age_ms,
				lat_e7,
				lon_e7,
				raw_alt_m,
				h_acc_m,
				v_acc_m,
				speed_mps,
				speed_accuracy_mps,
				provider,
				acquisition_mode,
				request_priority,
				permission_precision,
				batch_index,
				batch_size,
				is_mock,
				ingress_disposition,
				estimator_version,
				calibration_version,
				created_at
			)
			SELECT
				time_ms,
				elapsed_realtime_nanos,
				created_at,
				received_elapsed_realtime_nanos,
				delivery_age_ms,
				lat_e7,
				lon_e7,
				raw_gps_alt_m,
				h_acc_m,
				v_acc_m,
				speed_mps,
				speed_accuracy_mps,
				provider,
				acquisition_mode,
				request_priority,
				permission_precision,
				batch_index,
				batch_size,
				is_mock,
				'MIGRATED_ACCEPTED',
				estimator_version,
				calibration_version,
				created_at
			FROM location_sample
			ORDER BY id
			""".trimIndent(),
		)
		val actualRows = countRows(target, "location_observation")
		if (actualRows != expectedRows) {
			throw LegacyDatabaseException(
				"Expected $expectedRows legacy location observations but created $actualRows",
			)
		}
		return actualRows
	}

	/** Reproduces the canonical provenance identities assigned by the former folded migration. */
	private fun backfillCanonicalSourceIdentities(target: SupportSQLiteDatabase) {
		listOf(
			"location_sample",
			"step_interval",
			"activity_snapshot",
			"pressure_sample",
		).forEach { table ->
			target.execSQL(
				"UPDATE `$table` SET source_signal_id = 'legacy:$table:' || id " +
					"WHERE source_signal_id IS NULL",
			)
		}
		target.execSQL(
			"UPDATE location_observation SET source_signal_id = " +
				"'legacy:location_observation:' || id WHERE source_signal_id IS NULL",
		)
		target.execSQL(
			"UPDATE location_observation SET source_event_id = " +
				"'legacy:location_observation_event:' || id WHERE source_event_id IS NULL",
		)
		target.execSQL(
			"UPDATE location_observation SET callback_id = " +
				"'legacy:location_observation_callback:' || id WHERE callback_id IS NULL",
		)
		target.execSQL(
			"UPDATE location_observation SET clock_domain_id = 'legacy:unknown' " +
				"WHERE clock_domain_id IS NULL",
		)
		target.execSQL(
			"UPDATE cell_sample SET source_signal_id = 'legacy:cell_sample:' || id, " +
				"source_item_index = 0 WHERE source_signal_id IS NULL",
		)
		target.execSQL(
			"UPDATE wifi_observation SET source_signal_id = 'legacy:wifi_observation:' || id, " +
				"source_item_index = 0 WHERE source_signal_id IS NULL",
		)
	}

	private fun validateReleasedSchema(source: SQLiteDatabase) {
		if (source.version !in RELEASED_DATABASE_VERSION..LATEST_DIRECT_IMPORT_VERSION) {
			throw LegacyDatabaseException(
				"The importer requires schema v$RELEASED_DATABASE_VERSION..v$LATEST_DIRECT_IMPORT_VERSION",
			)
		}
		val actualTables = source.rawQuery(
			"SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
			null,
		).use { cursor ->
			buildSet {
				while (cursor.moveToNext()) add(cursor.getString(0))
			}
		}
		val missing = releasedTables - actualTables
		if (missing.isNotEmpty()) {
			throw LegacyDatabaseException("Legacy schema v26 is missing tables: ${missing.sorted()}")
		}
		for (spec in importedTables) {
			val actualColumns = sourceColumns(source, spec.name)
			val missingColumns = spec.columns.toSet() - actualColumns
			if (missingColumns.isNotEmpty()) {
				throw LegacyDatabaseException(
					"Legacy table ${spec.name} is missing columns: ${missingColumns.sorted()}",
				)
			}
		}
	}

	private fun copyTable(
		source: SQLiteDatabase,
		target: SupportSQLiteDatabase,
		spec: TableCopySpec,
	): Long {
		val sourceColumns = sourceColumns(source, spec.name)
		val quotedColumns = spec.columns.joinToString(",") { "`$it`" }
		val selectedColumns = spec.columns.joinToString(",") { column ->
			spec.sourceExpressions[column]?.let { expression ->
				"${expression(sourceColumns)} AS `$column`"
			}
				?: "`$column`"
		}
		val placeholders = List(spec.columns.size) { "?" }.joinToString(",")
		val statement = target.compileStatement(
			"INSERT INTO `${spec.name}` ($quotedColumns) VALUES ($placeholders)",
		)
		var copied = 0L
		source.rawQuery(
			"SELECT $selectedColumns FROM `${spec.name}` ORDER BY `${spec.orderColumn}`",
			null,
		).use { cursor ->
			while (cursor.moveToNext()) {
				statement.clearBindings()
				bindRow(statement, cursor)
				statement.executeInsert()
				copied += 1L
			}
		}
		if (copied != countRows(source, spec.name)) {
			throw LegacyDatabaseException("Legacy table ${spec.name} changed during import")
		}
		return copied
	}

	private fun sourceColumns(source: SQLiteDatabase, table: String): Set<String> =
		source.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
			buildSet {
				val nameIndex = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) add(cursor.getString(nameIndex))
			}
		}

	private fun bindRow(statement: SupportSQLiteStatement, cursor: Cursor) {
		for (index in 0 until cursor.columnCount) {
			val parameter = index + 1
			when (cursor.getType(index)) {
				Cursor.FIELD_TYPE_NULL -> statement.bindNull(parameter)
				Cursor.FIELD_TYPE_INTEGER -> statement.bindLong(parameter, cursor.getLong(index))
				Cursor.FIELD_TYPE_FLOAT -> statement.bindDouble(parameter, cursor.getDouble(index))
				Cursor.FIELD_TYPE_STRING -> statement.bindString(parameter, cursor.getString(index))
				Cursor.FIELD_TYPE_BLOB -> statement.bindBlob(parameter, cursor.getBlob(index))
				else -> throw LegacyDatabaseException("Unsupported SQLite value type")
			}
		}
	}

	private fun countRows(source: SQLiteDatabase, table: String): Long = source.rawQuery(
		"SELECT COUNT(*) FROM `$table`",
		null,
	).use { cursor ->
		if (!cursor.moveToFirst()) throw LegacyDatabaseException("Could not count legacy table $table")
		cursor.getLong(0)
	}

	private fun countRows(target: SupportSQLiteDatabase, table: String): Long = target.query(
		"SELECT COUNT(*) FROM `$table`",
	).use { cursor ->
		if (!cursor.moveToFirst()) throw LegacyDatabaseException("Could not count target table $table")
		cursor.getLong(0)
	}

	private fun writeCompletionMarker(
		target: SupportSQLiteDatabase,
		sourceVersion: Int,
		sourceSizeBytes: Long,
	) {
		val now = System.currentTimeMillis()
		target.execSQL(
			"""
			INSERT INTO import_job_receipt(
				job_id, source_name, source_size_bytes, status,
				started_at, completed_at, updated_at
			) VALUES (?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			arrayOf<Any?>(
				LEGACY_IMPORT_JOB_ID,
				"$LEGACY_DATABASE_NAME:v$sourceVersion",
				sourceSizeBytes,
				"COMPLETE",
				now,
				now,
				now,
			),
		)
	}

	private data class TableCopySpec(
		val name: String,
		val columns: List<String>,
		val orderColumn: String = "id",
		val sourceExpressions: Map<String, (Set<String>) -> String> = emptyMap(),
	)

	private companion object {
		const val RELEASED_DATABASE_VERSION = 26
		const val LATEST_DIRECT_IMPORT_VERSION = 34

		val importedTables = listOf(
			TableCopySpec("activity", listOf("id", "name", "iconName")),
			TableCopySpec("location_sample", listOf(
				"id", "time_ms", "elapsed_realtime_nanos", "lat_e7", "lon_e7", "alt_m",
				"raw_gps_alt_m", "h_acc_m", "v_acc_m", "speed_mps", "speed_accuracy_mps",
				"provider", "quality", "motion_state", "policy", "bucket_id", "created_at",
			), sourceExpressions = mapOf(
				"lat_e7" to { columns ->
					canonicalOrLegacy(
						columns,
						"lat_e7",
						"legacy_lat",
						"CAST(ROUND(`legacy_lat` * 10000000) AS INTEGER)",
					)
				},
				"lon_e7" to { columns ->
					canonicalOrLegacy(
						columns,
						"lon_e7",
						"legacy_lon",
						"CAST(ROUND(`legacy_lon` * 10000000) AS INTEGER)",
					)
				},
				"alt_m" to { columns ->
					canonicalOrLegacy(columns, "alt_m", "legacy_alt_m")
				},
			)),
			TableCopySpec("step_interval", listOf(
				"id", "start_time_ms", "end_time_ms", "step_count", "sensor_value_start",
				"sensor_value_end", "sensor_reset", "created_at",
			)),
			TableCopySpec("activity_snapshot", listOf(
				"id", "time_ms", "activity_type", "confidence", "is_transition", "created_at",
			)),
			TableCopySpec("cell_sample", listOf(
				"id", "time_ms", "cell_id", "lac", "mcc", "mnc", "network_type",
				"signal_strength", "lat_e7", "lon_e7", "provenance", "created_at",
			), sourceExpressions = mapOf(
				"lat_e7" to { columns ->
					canonicalOrLegacy(
						columns,
						"lat_e7",
						"legacy_lat",
						"CAST(ROUND(`legacy_lat` * 10000000) AS INTEGER)",
					)
				},
				"lon_e7" to { columns ->
					canonicalOrLegacy(
						columns,
						"lon_e7",
						"legacy_lon",
						"CAST(ROUND(`legacy_lon` * 10000000) AS INTEGER)",
					)
				},
			)),
			TableCopySpec("wifi_observation", listOf(
				"id", "time_ms", "bssid", "ssid", "capabilities", "frequency", "level",
				"lat_e7", "lon_e7", "provenance", "created_at",
			), sourceExpressions = mapOf(
				"lat_e7" to { columns ->
					canonicalOrLegacy(
						columns,
						"lat_e7",
						"legacy_lat",
						"CAST(ROUND(`legacy_lat` * 10000000) AS INTEGER)",
					)
				},
				"lon_e7" to { columns ->
					canonicalOrLegacy(
						columns,
						"lon_e7",
						"legacy_lon",
						"CAST(ROUND(`legacy_lon` * 10000000) AS INTEGER)",
					)
				},
			)),
			TableCopySpec("session_segment", listOf(
				"id", "start_time_ms", "end_time_ms", "distance_m", "steps", "primary_activity",
				"activity_confidence", "sample_count", "source", "inference_version", "created_at",
				"has_distance_anomaly",
			), sourceExpressions = mapOf(
				"primary_activity" to { columns ->
					canonicalOrLegacy(columns, "primary_activity", "legacy_activity_id")
				},
			)),
			TableCopySpec("daily_summary", listOf(
				"date_epoch_day", "total_distance_m", "total_steps", "total_duration_ms",
				"trip_count", "active_tracking_ms", "last_updated_ms", "created_at",
			), orderColumn = "date_epoch_day"),
			TableCopySpec("exploration_cell", listOf(
				"id", "cell_token", "level", "quality", "first_discovered_at", "last_visited_at",
				"visit_count", "season_bitmask", "center_lat_e7", "center_lon_e7", "created_at",
			)),
			TableCopySpec("exploration_streak", listOf(
				"type", "current_count", "best_count", "last_increment_day", "updated_at",
			), orderColumn = "type"),
			TableCopySpec("pressure_sample", listOf(
				"id", "time_ms", "elapsed_realtime_nanos", "pressure_hpa", "altitude_m",
				"bucket_id", "created_at",
			)),
			TableCopySpec("ski_run_segment", listOf(
				"id", "session_id", "run_index", "segment_type", "start_time_ms", "end_time_ms",
				"vertical_m", "distance_m", "max_speed_mps", "avg_speed_mps", "lift_type", "created_at",
			)),
		)

		val skippedTables = listOf(
			"network_operator",
			"tracker_run",
			"live_stats",
			"frequent_place",
			"inferred_trip",
			"trip_leg",
			"achievement_progress",
			"personal_record",
			"route_cache",
			"export_log",
			"storage_size_snapshot",
			"domain_event",
			"domain_event_cursor",
			"pending_signal",
		)

		val releasedTables = (importedTables.map(TableCopySpec::name) + skippedTables).toSet()
	}
}

fun hasCompletedLegacyImport(database: SupportSQLiteDatabase): Boolean = database.query(
	"SELECT 1 FROM import_job_receipt WHERE job_id = ? AND status = 'COMPLETE' LIMIT 1",
	arrayOf(LEGACY_IMPORT_JOB_ID),
).use(Cursor::moveToFirst)
