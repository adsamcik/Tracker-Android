package com.adsamcik.tracker.shared.base.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Validates the additive migration against Room's checked-in exact v27 schema fixture. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration27To28Test {
	@get:Rule
	val helper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		AppDatabase::class.java,
		emptyList(),
		FrameworkSQLiteOpenHelperFactory(),
	)

	@Test
	fun preservesV27EvidenceAndCreatesAnInertPolicyAuthority() {
			helper.createDatabase(TEST_DATABASE, 27).apply {
				execSQL(
					"INSERT INTO source_evidence_state " +
					"(id, revision, collected_data_epoch, retained_from_ms, updated_at_ms) " +
					"VALUES (1, 41, 7, 123456, 999)",
				)
				execSQL(
					"INSERT INTO logical_tracking_session " +
						"(logical_tracking_id, state, lifecycle_revision, desired_plan_revision, " +
						"rollout_revision, start_origin, clock_domain_id, started_at_ms, " +
						"started_elapsed_nanos, cutoff_at_ms, cutoff_elapsed_nanos, completed_at_ms, " +
						"final_admission_ordinal, failure_code) VALUES " +
						"('legacy-logical', 'RUNNING', 3, 7, 2, 'MANUAL_FOREGROUND', 'boot-legacy', " +
						"1000, 1000000, NULL, NULL, NULL, NULL, NULL)",
				)
				execSQL(
					"INSERT INTO source_service_run " +
						"(service_run_id, logical_tracking_id, state, desired_plan_revision, " +
						"rollout_revision, foreground_capability_flags, started_at_ms, " +
						"started_elapsed_nanos, completed_at_ms, completion_reason) VALUES " +
						"('legacy-run', 'legacy-logical', 'RUNNING', 7, 2, 5, 1000, 1000000, NULL, NULL)",
				)
				close()
		}

		helper.runMigrationsAndValidate(TEST_DATABASE, 28, true, MIGRATION_27_28).use { database ->
			database.query(
				"SELECT revision, collected_data_epoch, retained_from_ms " +
					"FROM source_evidence_state WHERE id = 1",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(41L, cursor.getLong(0))
				assertEquals(7L, cursor.getLong(1))
				assertEquals(123456L, cursor.getLong(2))
			}
			database.query(
				"SELECT bootstrap_state, current_policy_revision " +
					"FROM source_policy_authority WHERE id = 1",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("UNINITIALIZED", cursor.getString(0))
				assertEquals(0L, cursor.getLong(1))
			}
			database.query("SELECT COUNT(*) FROM source_policy").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(0L, cursor.getLong(0))
			}
			database.query("SELECT COUNT(*) FROM source_consent_epoch").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(0L, cursor.getLong(0))
			}
			database.query(
					"SELECT state, session_mode, current_manifest_revision, current_intent_revision, " +
						"lifecycle_lease_generation, lifecycle_boot_id, automation_epoch " +
						"FROM logical_tracking_session WHERE logical_tracking_id = 'legacy-logical'",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("RUNNING", cursor.getString(0))
				assertEquals("LEGACY_UNKNOWN", cursor.getString(1))
				assertTrue(cursor.isNull(2))
				assertTrue(cursor.isNull(3))
				assertEquals(0L, cursor.getLong(4))
				assertTrue(cursor.isNull(5))
				assertTrue(cursor.isNull(6))
			}
			database.query(
					"SELECT boot_id, lease_generation, start_origin, " +
						"desired_foreground_capability_flags, applied_foreground_capability_flags, " +
						"runtime_acknowledgement, runtime_failure_code, run_revision " +
						"FROM source_service_run WHERE service_run_id = 'legacy-run'",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("LEGACY_UNKNOWN", cursor.getString(0))
				assertEquals(0L, cursor.getLong(1))
				assertEquals("LEGACY_UNKNOWN", cursor.getString(2))
				assertEquals(5L, cursor.getLong(3))
				assertEquals(5L, cursor.getLong(4))
				assertEquals("LEGACY_ACTIVE", cursor.getString(5))
				assertTrue(cursor.isNull(6))
				assertEquals(0L, cursor.getLong(7))
			}
			listOf(
				"session_manifest_version",
				"session_manifest_source",
				"session_lifecycle_intent_version",
				"lifecycle_desired_action",
				"source_demand",
				"provider_registration_generation",
				"source_authorization",
			).forEach { table ->
				database.query("SELECT COUNT(*) FROM $table").use { cursor ->
					assertTrue(cursor.moveToFirst())
					assertEquals(0L, cursor.getLong(0))
				}
			}
			database.query(
				"SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
					"AND name = 'provider_registration_eligibility'",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(0L, cursor.getLong(0))
			}
			database.query("PRAGMA table_info(source_coordinator_lease)").use { cursor ->
				val nameIndex = cursor.getColumnIndexOrThrow("name")
				val names = buildSet {
					while (cursor.moveToNext()) add(cursor.getString(nameIndex))
				}
				assertTrue("boot_id" in names)
				assertTrue("generation" in names)
				assertTrue("acquired_elapsed_realtime_nanos" in names)
				assertTrue("expires_elapsed_realtime_nanos" in names)
			}
			database.query("PRAGMA table_info(acquisition_plan_revision)").use { cursor ->
				val nameIndex = cursor.getColumnIndexOrThrow("name")
				var foundPolicyRevision = false
				while (cursor.moveToNext()) {
					foundPolicyRevision = foundPolicyRevision ||
						cursor.getString(nameIndex) == "source_policy_revision"
				}
				assertTrue(foundPolicyRevision)
			}
			database.query("PRAGMA table_info(source_event_wal)").use { cursor ->
				val nameIndex = cursor.getColumnIndexOrThrow("name")
				val names = buildSet {
					while (cursor.moveToNext()) add(cursor.getString(nameIndex))
				}
				assertTrue("source_policy_revision" in names)
				assertTrue("capture_consent_epoch" in names)
				assertTrue("session_manifest_revision" in names)
				assertTrue("lifecycle_lease_generation" in names)
				assertTrue("physical_configuration_fingerprint" in names)
				assertTrue("authorization_revision" in names)
				assertTrue("authorization_purpose_eligibility_mask" in names)
				assertTrue("authorization_fingerprint" in names)
				assertTrue("integrity_identity" in names)
			}
		}
	}

	private companion object {
		const val TEST_DATABASE = "migration-27-28"
	}
}
