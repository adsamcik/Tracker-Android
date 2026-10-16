package com.adsamcik.tracker.shared.base.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TrackingOwnerValidationRoomCallback : RoomDatabase.Callback() {
	override fun onCreate(db: SupportSQLiteDatabase) {
		createTrackingOwnerValidationTriggers(db)
	}
}

internal fun createTrackingOwnerValidationTriggers(database: SupportSQLiteDatabase) {
	if (database.hasColumns("organization_annotation", setOf("activity_label_id", "revision")) &&
		database.hasColumns("activity", setOf("id"))
	) {
		createOrganizationAnnotationLabelTrigger(database)
	}

	if (database.hasColumns(
			"pending_signal",
			setOf(
				"steps_writer_owner",
				"steps_writer_owner_generation",
				"pressure_writer_owner",
				"pressure_writer_owner_generation",
			),
		)
	) {
		database.execSQL(validatePendingSignalWriterInsertSql)
		database.execSQL(validatePendingSignalWriterUpdateSql)
	}

	if (database.hasColumns(
			"imported_pressure_source_erase",
			setOf("legacy_write_fence_owner", "legacy_write_fence_generation"),
		)
	) {
		database.execSQL(validateImportedPressureEraseInsertSql)
		database.execSQL(validateImportedPressureEraseUpdateSql)
	}

	if (database.hasColumns(
			"pressure_fact_retention_authority",
			setOf("writer_projection_id", "writer_projection_version", "logical_fact_id"),
		) && database.hasColumns(
			"pressure_fact_retention_revision_authority",
			setOf(
				"writer_projection_id",
				"writer_projection_version",
				"logical_fact_id",
				"source_event_id",
				"source_admission_ordinal",
			),
		) && database.hasColumns(
			"pressure_fact_revision",
			setOf(
				"writer_projection_id",
				"writer_projection_version",
				"logical_fact_id",
				"source_event_id",
				"source_admission_ordinal",
			),
		)
	) {
		database.execSQL(rejectPressureFactResurrectionSql)
	}
}

private fun SupportSQLiteDatabase.hasColumns(
	table: String,
	required: Set<String>,
): Boolean {
	val actual = buildSet {
		query("PRAGMA table_info(`$table`)").use { cursor ->
			val name = cursor.getColumnIndexOrThrow("name")
			while (cursor.moveToNext()) add(cursor.getString(name))
		}
	}
	return actual.containsAll(required)
}

// Migration orchestration above retains its original guards and statement order.
private val validatePendingSignalWriterInsertSql =
	"""
	CREATE TRIGGER IF NOT EXISTS validate_pending_signal_writer_owners_insert
	BEFORE INSERT ON pending_signal
	WHEN NOT (
		(
			NEW.steps_writer_owner IS NULL AND
			NEW.steps_writer_owner_generation IS NULL
		) OR (
			NEW.steps_writer_owner IS NOT NULL AND
			NEW.steps_writer_owner IN ('LEGACY_STEP_INTERVAL', 'STEPS_SESSION_FACTS') AND
			NEW.steps_writer_owner_generation IS NOT NULL AND
			NEW.steps_writer_owner_generation > 0
		)
	) OR NOT (
		(
			NEW.pressure_writer_owner IS NULL AND
			NEW.pressure_writer_owner_generation IS NULL
		) OR (
			NEW.pressure_writer_owner IS NOT NULL AND
			NEW.pressure_writer_owner IN (
				'LEGACY_PRESSURE_SAMPLE',
				'PRESSURE_SESSION_FACTS'
			) AND
			NEW.pressure_writer_owner_generation IS NOT NULL AND
			NEW.pressure_writer_owner_generation > 0
		)
	)
	BEGIN
		SELECT RAISE(ABORT, 'invalid pending signal writer owner');
	END
	""".trimIndent()

private val validatePendingSignalWriterUpdateSql =
	"""
	CREATE TRIGGER IF NOT EXISTS validate_pending_signal_writer_owners_update
	BEFORE UPDATE OF
		steps_writer_owner,
		steps_writer_owner_generation,
		pressure_writer_owner,
		pressure_writer_owner_generation
	ON pending_signal
	WHEN NOT (
		(
			NEW.steps_writer_owner IS NULL AND
			NEW.steps_writer_owner_generation IS NULL
		) OR (
			NEW.steps_writer_owner IS NOT NULL AND
			NEW.steps_writer_owner IN ('LEGACY_STEP_INTERVAL', 'STEPS_SESSION_FACTS') AND
			NEW.steps_writer_owner_generation IS NOT NULL AND
			NEW.steps_writer_owner_generation > 0
		)
	) OR NOT (
		(
			NEW.pressure_writer_owner IS NULL AND
			NEW.pressure_writer_owner_generation IS NULL
		) OR (
			NEW.pressure_writer_owner IS NOT NULL AND
			NEW.pressure_writer_owner IN (
				'LEGACY_PRESSURE_SAMPLE',
				'PRESSURE_SESSION_FACTS'
			) AND
			NEW.pressure_writer_owner_generation IS NOT NULL AND
			NEW.pressure_writer_owner_generation > 0
		)
	)
	BEGIN
		SELECT RAISE(ABORT, 'invalid pending signal writer owner');
	END
	""".trimIndent()

private val validateImportedPressureEraseInsertSql =
	"""
	CREATE TRIGGER IF NOT EXISTS validate_imported_pressure_source_erase_owner_insert
	BEFORE INSERT ON imported_pressure_source_erase
	WHEN NOT (
		(
			NEW.legacy_write_fence_owner IS NULL AND
			NEW.legacy_write_fence_generation >= 0
		) OR (
			NEW.legacy_write_fence_owner IS NOT NULL AND
			NEW.legacy_write_fence_owner = 'LEGACY_PRESSURE_SAMPLE' AND
			NEW.legacy_write_fence_generation >= 2
		) OR (
			NEW.legacy_write_fence_owner IS NOT NULL AND
			NEW.legacy_write_fence_owner = 'CONTAINED_PRESSURE_SESSION_FACTS' AND
			NEW.legacy_write_fence_generation >= 3 AND
			NEW.legacy_write_fence_generation % 2 = 1
		)
	)
	BEGIN
		SELECT RAISE(ABORT, 'invalid imported Pressure source erase owner');
	END
	""".trimIndent()

private val validateImportedPressureEraseUpdateSql =
	"""
	CREATE TRIGGER IF NOT EXISTS validate_imported_pressure_source_erase_owner_update
	BEFORE UPDATE OF legacy_write_fence_owner, legacy_write_fence_generation
	ON imported_pressure_source_erase
	WHEN NOT (
		(
			NEW.legacy_write_fence_owner IS NULL AND
			NEW.legacy_write_fence_generation >= 0
		) OR (
			NEW.legacy_write_fence_owner IS NOT NULL AND
			NEW.legacy_write_fence_owner = 'LEGACY_PRESSURE_SAMPLE' AND
			NEW.legacy_write_fence_generation >= 2
		) OR (
			NEW.legacy_write_fence_owner IS NOT NULL AND
			NEW.legacy_write_fence_owner = 'CONTAINED_PRESSURE_SESSION_FACTS' AND
			NEW.legacy_write_fence_generation >= 3 AND
			NEW.legacy_write_fence_generation % 2 = 1
		)
	)
	BEGIN
		SELECT RAISE(ABORT, 'invalid imported Pressure source erase owner');
	END
	""".trimIndent()

private val rejectPressureFactResurrectionSql =
	"""
	CREATE TRIGGER IF NOT EXISTS reject_pressure_fact_retention_resurrection
	BEFORE INSERT ON pressure_fact_revision
	WHEN EXISTS (
		SELECT 1
		FROM pressure_fact_retention_authority AS retained
		WHERE retained.writer_projection_id = NEW.writer_projection_id
		  AND retained.writer_projection_version = NEW.writer_projection_version
		  AND retained.logical_fact_id = NEW.logical_fact_id
	)
	OR EXISTS (
		SELECT 1
		FROM pressure_fact_retention_revision_authority AS retained_revision
		WHERE retained_revision.writer_projection_id = NEW.writer_projection_id
		  AND retained_revision.writer_projection_version = NEW.writer_projection_version
		  AND (
		    retained_revision.logical_fact_id = NEW.logical_fact_id
		    OR retained_revision.source_event_id = NEW.source_event_id
		    OR retained_revision.source_admission_ordinal = NEW.source_admission_ordinal
		  )
	)
	BEGIN
		SELECT RAISE(ABORT, 'Pressure retention authority rejects fact resurrection');
	END
	""".trimIndent()
