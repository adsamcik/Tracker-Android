package com.adsamcik.tracker.shared.base.database

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedPressureLifecycleSchemaTest {
	private lateinit var helper: SupportSQLiteOpenHelper

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(null)
				.callback(object : SupportSQLiteOpenHelper.Callback(1) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						createLegacySourceEraseTable(db)
						insertLegacySourceErase(db)
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = error("This isolated fixture has no version upgrade")
				})
				.build(),
		)
	}

	@After
	fun tearDown() {
		helper.close()
	}

	@Test
	fun `lifecycle owner column preserves v1 rows and rejects unknown owners`() {
		val database = helper.writableDatabase

		createImportedPressureMaintenanceTables(database)

		database.query(
			"SELECT legacy_write_fence_owner, legacy_write_fence_generation, effect_checksum " +
				"FROM imported_pressure_source_erase WHERE id = 1",
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.isNull(0) shouldBe true
			cursor.getLong(1) shouldBe 2L
			cursor.getString(2) shouldBe LEGACY_EFFECT
		}
		val createSql = database.query(
			"SELECT sql FROM sqlite_master WHERE type = 'table' " +
				"AND name = 'imported_pressure_source_erase'",
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0)
		}
		createSql shouldContain "LEGACY_PRESSURE_SAMPLE"
		createSql shouldContain "CONTAINED_PRESSURE_SESSION_FACTS"

		shouldThrow<SQLiteConstraintException> {
			database.execSQL(
				"UPDATE imported_pressure_source_erase " +
					"SET legacy_write_fence_owner = 'UNKNOWN_PRESSURE_OWNER' WHERE id = 1",
			)
		}
		database.execSQL(
			"UPDATE imported_pressure_source_erase " +
				"SET legacy_write_fence_owner = 'LEGACY_PRESSURE_SAMPLE' WHERE id = 1",
		)
	}

	private fun createLegacySourceEraseTable(database: SupportSQLiteDatabase) {
		database.execSQL(
			"""
			CREATE TABLE imported_pressure_source_erase (
				id INTEGER NOT NULL PRIMARY KEY,
				collected_data_epoch INTEGER NOT NULL,
				source_evidence_revision INTEGER NOT NULL,
				erased_at_ms INTEGER NOT NULL,
				provider_registration_generation INTEGER,
				legacy_write_fence_generation INTEGER NOT NULL,
				local_fact_revision_count INTEGER NOT NULL,
				local_wal_event_count INTEGER NOT NULL,
				legacy_sample_count INTEGER NOT NULL,
				legacy_sample_set_checksum TEXT NOT NULL,
				imported_entry_count INTEGER NOT NULL,
				imported_revision_count INTEGER NOT NULL,
				imported_run_count INTEGER NOT NULL,
				imported_window_count INTEGER NOT NULL,
				fenced_local_run_count INTEGER NOT NULL,
				local_scope_set_checksum TEXT NOT NULL,
				entry_deletion_count INTEGER NOT NULL,
				entry_deletion_set_checksum TEXT NOT NULL,
				run_deletion_count INTEGER NOT NULL,
				run_deletion_set_checksum TEXT NOT NULL,
				identity_fence_count INTEGER NOT NULL,
				identity_fence_set_checksum TEXT NOT NULL,
				effect_checksum TEXT NOT NULL
			)
			""".trimIndent(),
		)
	}

	private fun insertLegacySourceErase(database: SupportSQLiteDatabase) {
		database.execSQL(
			"""
			INSERT INTO imported_pressure_source_erase VALUES (
				1, 7, 5, 3000, NULL, 2, 0, 0, 0, 'legacy-samples',
				0, 0, 0, 0, 0, 'local-scopes', 0, 'entry-deletions',
				0, 'run-deletions', 0, 'identity-fences', ?
			)
			""".trimIndent(),
			arrayOf(LEGACY_EFFECT),
		)
	}

	private companion object {
		const val LEGACY_EFFECT = "legacy-v1-effect-checksum"
	}
}
