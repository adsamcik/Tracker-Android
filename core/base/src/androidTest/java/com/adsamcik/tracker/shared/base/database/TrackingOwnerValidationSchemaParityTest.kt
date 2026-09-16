package com.adsamcik.tracker.shared.base.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackingOwnerValidationSchemaParityTest {
	@get:Rule
	val helper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		AppDatabase::class.java,
		emptyList(),
		FrameworkSQLiteOpenHelperFactory(),
	)

	private val context: Context
		get() = InstrumentationRegistry.getInstrumentation().targetContext
	private var freshDatabase: AppDatabase? = null

	@Before
	fun before() {
		context.deleteDatabase(FRESH_DATABASE)
		context.deleteDatabase(MIGRATED_DATABASE)
	}

	@After
	fun after() {
		freshDatabase?.close()
		freshDatabase = null
		context.deleteDatabase(FRESH_DATABASE)
		context.deleteDatabase(MIGRATED_DATABASE)
	}

	@Test
	fun freshRoomSchemaRejectsInvalidWriterOwnersThroughTriggers() {
		freshDatabase = Room.databaseBuilder(context, AppDatabase::class.java, FRESH_DATABASE)
			.openHelperFactory(FrameworkSQLiteOpenHelperFactory())
			.addCallback(TrackingOwnerValidationRoomCallback)
			.allowMainThreadQueries()
			.build()

		assertOwnerValidation(requireNotNull(freshDatabase).openHelper.writableDatabase)
	}

	@Test
	fun migratedSchemaRejectsInvalidWriterOwnersThroughTriggers() {
		helper.createDatabase(MIGRATED_DATABASE, 27).close()

		helper.runMigrationsAndValidate(
			MIGRATED_DATABASE,
			28,
			true,
			MIGRATION_27_28,
		).use(::assertOwnerValidation)
	}

	private fun assertOwnerValidation(database: SupportSQLiteDatabase) {
		listOf(
			"validate_pending_signal_writer_owners_insert",
			"validate_pending_signal_writer_owners_update",
			"validate_imported_pressure_source_erase_owner_insert",
			"validate_imported_pressure_source_erase_owner_update",
		).forEach { trigger ->
			database.query(
				"SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = ?",
				arrayOf(trigger),
			).use { cursor -> assertTrue(trigger, cursor.moveToFirst()) }
		}

		assertConstraintFailure {
			insertPending(
				database,
				"invalid-insert-steps-half",
				stepsOwner = "LEGACY_STEP_INTERVAL",
			)
		}
		assertConstraintFailure {
			insertPending(
				database,
				"invalid-insert-pressure-half",
				pressureGeneration = 1L,
			)
		}
		insertPending(database, PENDING_SIGNAL)
		listOf(
			"steps_writer_owner = 'LEGACY_STEP_INTERVAL'" to "Steps half-null owner",
			"steps_writer_owner_generation = 1" to "Steps half-null generation",
			"steps_writer_owner = 'LEGACY_STEP_INTERVAL', steps_writer_owner_generation = 0" to
				"Steps nonpositive generation",
			"steps_writer_owner = 'UNKNOWN_STEPS_OWNER', steps_writer_owner_generation = 1" to
				"unknown Steps owner",
			"pressure_writer_owner = 'LEGACY_PRESSURE_SAMPLE'" to "Pressure half-null owner",
			"pressure_writer_owner_generation = 1" to "Pressure half-null generation",
			"pressure_writer_owner = 'LEGACY_PRESSURE_SAMPLE', pressure_writer_owner_generation = 0" to
				"Pressure nonpositive generation",
			"pressure_writer_owner = 'UNKNOWN_PRESSURE_OWNER', pressure_writer_owner_generation = 1" to
				"unknown Pressure owner",
		).forEach { (assignment, description) ->
			assertConstraintFailure(description) {
				database.execSQL(
					"UPDATE pending_signal SET $assignment WHERE signal_id = '$PENDING_SIGNAL'",
				)
			}
		}
		database.execSQL(
			"UPDATE pending_signal SET steps_writer_owner = 'STEPS_SESSION_FACTS', " +
				"steps_writer_owner_generation = 2, " +
				"pressure_writer_owner = 'PRESSURE_SESSION_FACTS', " +
				"pressure_writer_owner_generation = 2 WHERE signal_id = '$PENDING_SIGNAL'",
		)
		database.execSQL(
			"UPDATE pending_signal SET steps_writer_owner = NULL, " +
				"steps_writer_owner_generation = NULL, pressure_writer_owner = NULL, " +
				"pressure_writer_owner_generation = NULL WHERE signal_id = '$PENDING_SIGNAL'",
		)

		assertConstraintFailure("unknown Pressure erase owner insert") {
			insertPressureSourceErase(database, "UNKNOWN_PRESSURE_OWNER", 2L)
		}
		insertPressureSourceErase(database, null, 2L)
		assertPressureEraseOwner(database, null, 2L)
		database.execSQL(
			"UPDATE imported_pressure_source_erase SET " +
				"legacy_write_fence_owner = 'LEGACY_PRESSURE_SAMPLE', " +
				"legacy_write_fence_generation = 2 WHERE id = 1",
		)
		assertPressureEraseOwner(database, "LEGACY_PRESSURE_SAMPLE", 2L)
		database.execSQL(
			"UPDATE imported_pressure_source_erase SET " +
				"legacy_write_fence_owner = 'CONTAINED_PRESSURE_SESSION_FACTS', " +
				"legacy_write_fence_generation = 3 WHERE id = 1",
		)
		assertPressureEraseOwner(database, "CONTAINED_PRESSURE_SESSION_FACTS", 3L)
		listOf(
			"legacy_write_fence_owner = 'UNKNOWN_PRESSURE_OWNER'" to "unknown erase owner",
			"legacy_write_fence_owner = 'LEGACY_PRESSURE_SAMPLE', " +
				"legacy_write_fence_generation = 1" to "legacy erase generation",
			"legacy_write_fence_owner = 'CONTAINED_PRESSURE_SESSION_FACTS', " +
				"legacy_write_fence_generation = 4" to "contained erase generation",
		).forEach { (assignment, description) ->
			assertConstraintFailure(description) {
				database.execSQL(
					"UPDATE imported_pressure_source_erase SET $assignment WHERE id = 1",
				)
			}
		}
		database.execSQL(
			"UPDATE imported_pressure_source_erase SET legacy_write_fence_owner = NULL, " +
				"legacy_write_fence_generation = 2 WHERE id = 1",
		)
		assertPressureEraseOwner(database, null, 2L)
	}

	private fun insertPending(
		database: SupportSQLiteDatabase,
		signalId: String,
		stepsOwner: String? = null,
		stepsGeneration: Long? = null,
		pressureOwner: String? = null,
		pressureGeneration: Long? = null,
	) {
		database.execSQL(
			"""
			INSERT INTO pending_signal (
				signal_id, session_id, signal_json, created_at,
				steps_writer_owner, steps_writer_owner_generation,
				pressure_writer_owner, pressure_writer_owner_generation
			) VALUES (?, 1, '{}', 1, ?, ?, ?, ?)
			""".trimIndent(),
			arrayOf(
				signalId,
				stepsOwner,
				stepsGeneration,
				pressureOwner,
				pressureGeneration,
			),
		)
	}

	private fun insertPressureSourceErase(
		database: SupportSQLiteDatabase,
		owner: String?,
		generation: Long,
	) {
		database.execSQL(
			"""
			INSERT INTO imported_pressure_source_erase (
				id, collected_data_epoch, source_evidence_revision, erased_at_ms,
				provider_registration_generation, legacy_write_fence_owner,
				legacy_write_fence_generation, local_fact_revision_count,
				local_wal_event_count, legacy_sample_count, legacy_sample_set_checksum,
				imported_entry_count, imported_revision_count, imported_run_count,
				imported_window_count, fenced_local_run_count, local_scope_set_checksum,
				entry_deletion_count, entry_deletion_set_checksum, run_deletion_count,
				run_deletion_set_checksum, identity_fence_count,
				identity_fence_set_checksum, effect_checksum
			) VALUES (
				1, 7, 5, 3000, NULL, ?, ?, 0, 0, 0, 'legacy-samples',
				0, 0, 0, 0, 0, 'local-scopes', 0, 'entry-deletions',
				0, 'run-deletions', 0, 'identity-fences', 'legacy-effect'
			)
			""".trimIndent(),
			arrayOf(owner, generation),
		)
	}

	private fun assertPressureEraseOwner(
		database: SupportSQLiteDatabase,
		expectedOwner: String?,
		expectedGeneration: Long,
	) {
		database.query(
			"SELECT legacy_write_fence_owner, legacy_write_fence_generation " +
				"FROM imported_pressure_source_erase WHERE id = 1",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			if (expectedOwner == null) {
				assertTrue(cursor.isNull(0))
			} else {
				assertTrue(cursor.getString(0) == expectedOwner)
			}
			assertTrue(cursor.getLong(1) == expectedGeneration)
			assertFalse(cursor.moveToNext())
		}
	}

	private fun assertConstraintFailure(
		description: String = "expected SQLite constraint failure",
		block: () -> Unit,
	) {
		val failure = runCatching(block).exceptionOrNull()
		assertTrue(
			description,
			generateSequence(failure) { it.cause }.any { it is SQLiteConstraintException },
		)
	}

	private companion object {
		const val FRESH_DATABASE = "tracking-owner-validation-fresh"
		const val MIGRATED_DATABASE = "tracking-owner-validation-migrated"
		const val PENDING_SIGNAL = "owner-validation"
	}
}
