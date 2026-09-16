package com.adsamcik.tracker.shared.base.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TrackingOwnerValidationRoomCallback : RoomDatabase.Callback() {
	override fun onCreate(db: SupportSQLiteDatabase) {
		createTrackingOwnerValidationTriggers(db)
	}
}

internal fun createTrackingOwnerValidationTriggers(database: SupportSQLiteDatabase) {
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
		database.execSQL(
			"""
			CREATE TRIGGER IF NOT EXISTS validate_pending_signal_writer_owners_insert
			BEFORE INSERT ON pending_signal
			WHEN NOT (
				(
					NEW.steps_writer_owner IS NULL AND
					NEW.steps_writer_owner_generation IS NULL
				) OR (
					NEW.steps_writer_owner IN ('LEGACY_STEP_INTERVAL', 'STEPS_SESSION_FACTS') AND
					NEW.steps_writer_owner_generation IS NOT NULL AND
					NEW.steps_writer_owner_generation > 0
				)
			) OR NOT (
				(
					NEW.pressure_writer_owner IS NULL AND
					NEW.pressure_writer_owner_generation IS NULL
				) OR (
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
			""".trimIndent(),
		)
		database.execSQL(
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
					NEW.steps_writer_owner IN ('LEGACY_STEP_INTERVAL', 'STEPS_SESSION_FACTS') AND
					NEW.steps_writer_owner_generation IS NOT NULL AND
					NEW.steps_writer_owner_generation > 0
				)
			) OR NOT (
				(
					NEW.pressure_writer_owner IS NULL AND
					NEW.pressure_writer_owner_generation IS NULL
				) OR (
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
			""".trimIndent(),
		)
	}

	if (database.hasColumns(
			"imported_pressure_source_erase",
			setOf("legacy_write_fence_owner", "legacy_write_fence_generation"),
		)
	) {
		database.execSQL(
			"""
			CREATE TRIGGER IF NOT EXISTS validate_imported_pressure_source_erase_owner_insert
			BEFORE INSERT ON imported_pressure_source_erase
			WHEN NOT (
				(
					NEW.legacy_write_fence_owner IS NULL AND
					NEW.legacy_write_fence_generation >= 0
				) OR (
					NEW.legacy_write_fence_owner = 'LEGACY_PRESSURE_SAMPLE' AND
					NEW.legacy_write_fence_generation >= 2
				) OR (
					NEW.legacy_write_fence_owner = 'CONTAINED_PRESSURE_SESSION_FACTS' AND
					NEW.legacy_write_fence_generation >= 3 AND
					NEW.legacy_write_fence_generation % 2 = 1
				)
			)
			BEGIN
				SELECT RAISE(ABORT, 'invalid imported Pressure source erase owner');
			END
			""".trimIndent(),
		)
		database.execSQL(
			"""
			CREATE TRIGGER IF NOT EXISTS validate_imported_pressure_source_erase_owner_update
			BEFORE UPDATE OF legacy_write_fence_owner, legacy_write_fence_generation
			ON imported_pressure_source_erase
			WHEN NOT (
				(
					NEW.legacy_write_fence_owner IS NULL AND
					NEW.legacy_write_fence_generation >= 0
				) OR (
					NEW.legacy_write_fence_owner = 'LEGACY_PRESSURE_SAMPLE' AND
					NEW.legacy_write_fence_generation >= 2
				) OR (
					NEW.legacy_write_fence_owner = 'CONTAINED_PRESSURE_SESSION_FACTS' AND
					NEW.legacy_write_fence_generation >= 3 AND
					NEW.legacy_write_fence_generation % 2 = 1
				)
			)
			BEGIN
				SELECT RAISE(ABORT, 'invalid imported Pressure source erase owner');
			END
			""".trimIndent(),
		)
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
