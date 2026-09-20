package com.adsamcik.tracker.shared.base.database

import android.app.Application
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevelopmentV28DatabaseContainmentTest {
	private lateinit var context: Application

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		deleteFixture()
	}

	@After
	fun tearDown() {
		deleteFixture()
	}

	@Test
	fun `missing active database routes to fresh creation`() {
		preflight() shouldBe ActiveDatabasePreflightResult.Fresh
	}

	@Test
	fun `released v27 routes to the normal migration path`() {
		createFixture(version = LAST_RELEASED_ACTIVE_DATABASE_VERSION)

		preflight() shouldBe ActiveDatabasePreflightResult.ReleasedV27
	}

	@Test
	fun `fresh final v28 marker is accepted on every reopen`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.FinalV28
		preflight() shouldBe ActiveDatabasePreflightResult.FinalV28
	}

	@Test
	fun `development v28 without the final marker is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
		)
	}

	@Test
	fun `pre-retention final v28 marker is classified as stale`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			markerValue = "tracker-v28-final-20260917",
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
		)
	}

	@Test
	fun `pre-radio-receipt retention marker is classified as stale`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			markerValue = "tracker-v28-retention-final-20260917",
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
		)
	}

	@Test
	fun `pre Steps count-domain caller authority marker is classified as stale`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			markerValue = "tracker-v28-retention-caller-authority-20260919",
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
		)
	}

	@Test
	fun `pre disk staging v28 marker is classified as stale`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			markerValue = "tracker-v28-portable-ambient-graph-provenance-20260920",
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
		)
	}

	@Test
	fun `marked v28 missing an indispensable final table is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalColumn = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `pre-retention v28 missing retention sentinels is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeRetentionTables = false,
			includeFinalColumn = true,
			includeFinalIndex = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing an indispensable final column is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalIndex = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing the retained radio receipt floor is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			includeRadioReceiptColumn = false,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing an indispensable final index is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `pre caller authority v28 is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			includeCallerAuthority = false,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing a Steps count-domain table is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP TABLE steps_count_domain_schema_marker")
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing an exact Steps count-domain column is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL(
					"ALTER TABLE steps_count_domain_schema_marker " +
						"RENAME COLUMN terminal_unproven TO terminal_unknown",
				)
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing an exact Steps count-domain index is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP INDEX idx_steps_count_domain_owner_terminal_age")
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing portable Steps disk staging is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP TABLE imported_steps_full_clear_owner_stage")
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 with ascending imported Steps traversal index is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP INDEX idx_imported_steps_entry_cursor")
				database.execSQL(
					"CREATE INDEX idx_imported_steps_entry_cursor " +
						"ON imported_steps_entry(start_time_ms, identity)",
				)
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing an exact Steps count-domain trigger is contained`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP TRIGGER trg_steps_count_domain_owner_terminal")
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `unknown v28 schema is not mistaken for stale Tracker development data`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeBaseline = false,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.UNRECOGNIZED_DATABASE_SCHEMA,
		)
	}

	@Test
	fun `database path obstruction is retryable rather than schema corruption`() {
		databaseFile().mkdirs() shouldBe true

		preflight() shouldBe ActiveDatabasePreflightResult.Retryable(
			ActiveDatabaseRetryableReason.OPERATIONALLY_UNAVAILABLE,
		)
	}

	@Test
	fun `temporary open failures use the retryable operational state`() {
		SQLiteCantOpenDatabaseException("unable to open database file")
			.activeDatabaseRetryableReason() shouldBe
			ActiveDatabaseRetryableReason.OPERATIONALLY_UNAVAILABLE
	}

	@Test
	fun `corrupt database family is contained and preserved byte for byte`() {
		val before = createCorruptFileFamily()

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.UNREADABLE_DATABASE,
		)
		assertFileFamilyUnchanged(before)

		var delegateOpened = false
		val helper = DevelopmentV28ContainmentOpenHelperFactory(
			context = context,
			databaseName = DATABASE_NAME,
			delegate = FrameworkSQLiteOpenHelperFactory(),
		).create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(CURRENT_DATABASE_VERSION) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						delegateOpened = true
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = Unit
				})
				.build(),
		)

		val failure = shouldThrow<ActiveDatabaseOpenBlockedException> {
			helper.writableDatabase
		}

		failure.reason shouldBe ActiveDatabaseBlockReason.UNREADABLE_DATABASE
		delegateOpened shouldBe false
		assertFileFamilyUnchanged(before)
		helper.close()
	}

	@Test
	fun `blocked preflight never opens or changes the database file`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
		)
		val before = databaseFile().readBytes()
		var delegateOpened = false
		val helper = DevelopmentV28ContainmentOpenHelperFactory(
			context = context,
			databaseName = DATABASE_NAME,
			delegate = FrameworkSQLiteOpenHelperFactory(),
		).create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(CURRENT_DATABASE_VERSION) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						delegateOpened = true
					}

					override fun onOpen(db: SupportSQLiteDatabase) {
						delegateOpened = true
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = Unit
				})
				.build(),
		)

		val failure = shouldThrow<ActiveDatabaseOpenBlockedException> {
			helper.writableDatabase
		}

		failure.reason shouldBe ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28
		delegateOpened shouldBe false
		databaseFile().readBytes().contentEquals(before) shouldBe true
		helper.close()
	}

	@Test
	fun `released v27 migration failure is contained without replacing the source file`() {
		createFixture(version = LAST_RELEASED_ACTIVE_DATABASE_VERSION)
		val helper = DevelopmentV28ContainmentOpenHelperFactory(
			context = context,
			databaseName = DATABASE_NAME,
			delegate = FrameworkSQLiteOpenHelperFactory(),
		).create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(CURRENT_DATABASE_VERSION) {
					override fun onCreate(db: SupportSQLiteDatabase) = Unit

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) {
						throw IllegalStateException("simulated migration validation failure")
					}
				})
				.build(),
		)

		val failure = shouldThrow<ActiveDatabaseOpenBlockedException> {
			helper.writableDatabase
		}

		failure.reason shouldBe
			ActiveDatabaseBlockReason.RELEASED_V27_MIGRATION_VALIDATION_FAILED
		helper.close()
		preflight() shouldBe ActiveDatabasePreflightResult.ReleasedV27
	}

	@Test
	fun `database contention is retryable and a later preflight succeeds`() {
		createFixture(version = LAST_RELEASED_ACTIVE_DATABASE_VERSION)
		var delegateOpened = false
		val guardedHelper = DevelopmentV28ContainmentOpenHelperFactory(
			context = context,
			databaseName = DATABASE_NAME,
			delegate = FrameworkSQLiteOpenHelperFactory(),
		).create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(
					object : SupportSQLiteOpenHelper.Callback(
						LAST_RELEASED_ACTIVE_DATABASE_VERSION,
					) {
						override fun onCreate(db: SupportSQLiteDatabase) =
							error("Expected existing v27 fixture")

						override fun onUpgrade(
							db: SupportSQLiteDatabase,
							oldVersion: Int,
							newVersion: Int,
						) = error("Expected no migration")

						override fun onOpen(db: SupportSQLiteDatabase) {
							delegateOpened = true
						}
					},
				)
				.build(),
		)
		val lockingDatabase = openDatabasePreservingFiles(
			databaseFile(),
			SQLiteDatabase.OPEN_READWRITE,
		)
		lockingDatabase.execSQL("PRAGMA journal_mode=DELETE")
		lockingDatabase.execSQL("BEGIN EXCLUSIVE")
		try {
			preflight() shouldBe ActiveDatabasePreflightResult.Retryable(
				ActiveDatabaseRetryableReason.CONTENDED,
			)
			shouldThrow<ActiveDatabaseOpenRetryableException> {
				guardedHelper.readableDatabase
			}.reason shouldBe ActiveDatabaseRetryableReason.CONTENDED
			delegateOpened shouldBe false
		} finally {
			lockingDatabase.execSQL("ROLLBACK")
			lockingDatabase.close()
		}

		preflight() shouldBe ActiveDatabasePreflightResult.ReleasedV27
		guardedHelper.readableDatabase
		delegateOpened shouldBe true
		guardedHelper.close()
	}

	@Test
	fun `guarded delegate contention remains retryable`() {
		createFixture(
			version = CURRENT_DATABASE_VERSION,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
		)
		val helper = DevelopmentV28ContainmentOpenHelperFactory(
			delegate = FrameworkSQLiteOpenHelperFactory(),
			inspect = { ActiveDatabasePreflightResult.FinalV28 },
		).create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(CURRENT_DATABASE_VERSION) {
					override fun onCreate(db: SupportSQLiteDatabase) = Unit

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = Unit

					override fun onOpen(db: SupportSQLiteDatabase) {
						throw SQLiteDatabaseLockedException("database is locked")
					}
				})
				.build(),
		)

		val failure = shouldThrow<ActiveDatabaseOpenRetryableException> {
			helper.writableDatabase
		}

		failure.reason shouldBe ActiveDatabaseRetryableReason.CONTENDED
		helper.close()
	}

	private fun preflight(): ActiveDatabasePreflightResult =
		ActiveDatabasePreflight(databaseFile()).inspect()

	private fun createFixture(
		version: Int,
		includeBaseline: Boolean = true,
		includeMarker: Boolean = false,
		includeFinalTable: Boolean = false,
		includeRetentionTables: Boolean = includeFinalTable,
		includeFinalColumn: Boolean = false,
		includeFinalIndex: Boolean = false,
		includeRadioReceiptColumn: Boolean = true,
		includeCallerAuthority: Boolean = includeFinalTable,
		includeStepsCountDomain: Boolean = includeFinalTable,
		stepsSchemaMutation: ((SupportSQLiteDatabase) -> Unit)? = null,
		markerValue: String? = null,
	) {
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(version) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						if (includeBaseline) {
							db.execSQL(
								"CREATE TABLE room_master_table " +
									"(id INTEGER PRIMARY KEY, identity_hash TEXT)",
							)
							db.execSQL("CREATE TABLE activity (id INTEGER PRIMARY KEY)")
							db.execSQL("CREATE TABLE tracker_run (id INTEGER PRIMARY KEY)")
							val finalColumn = if (includeFinalColumn) {
								", pressure_writer_owner_generation INTEGER"
							} else {
								""
							}
							db.execSQL(
								"CREATE TABLE pending_signal (id INTEGER PRIMARY KEY$finalColumn)",
							)
						}
						if (includeFinalTable) {
							db.execSQL(
								"CREATE TABLE imported_wifi_deletion_generation (" +
									"run_identity TEXT PRIMARY KEY, " +
									"deletion_scope_digest TEXT NOT NULL)",
							)
							if (includeFinalIndex) {
								db.execSQL(
									"CREATE UNIQUE INDEX idx_imported_wifi_deletion_scope " +
										"ON imported_wifi_deletion_generation(deletion_scope_digest)",
								)
							}
						}
						if (includeRetentionTables) {
							db.execSQL(
								"CREATE TABLE ambient_steps_retention_authority (" +
									"scope TEXT NOT NULL, approval_revision INTEGER NOT NULL, " +
									"PRIMARY KEY(scope, approval_revision))",
							)
							db.execSQL(
								"CREATE TABLE ambient_steps_native_replay_footprint (" +
									"protected_identity TEXT PRIMARY KEY)",
							)
							db.execSQL(
								"CREATE TABLE collected_data_deletion_operation (" +
									"operation_id TEXT PRIMARY KEY, " +
									"retention_work_execution_id TEXT, " +
									"retention_destructive_plan TEXT, " +
									"settled_retained_from_ms INTEGER)",
							)
							val retainedFrom = if (includeRadioReceiptColumn) {
								", retained_from_ms INTEGER"
							} else {
								""
							}
							db.execSQL(
								"CREATE TABLE cell_captured_entry_deletion_receipt (" +
									"logical_tracking_id TEXT PRIMARY KEY$retainedFrom)",
							)
							if (includeFinalIndex) {
								db.execSQL(
									"CREATE INDEX idx_ambient_steps_retention_scope " +
										"ON ambient_steps_retention_authority(scope)",
								)
							}
						}
						if (includeCallerAuthority) {
							db.execSQL(
								"CREATE TABLE source_caller_accepted_authority (" +
									"reference TEXT NOT NULL, format_version INTEGER NOT NULL, " +
									"origin TEXT NOT NULL, accepted_purpose TEXT NOT NULL, " +
									"source_kind INTEGER NOT NULL, purpose TEXT NOT NULL, " +
									"policy_revision INTEGER NOT NULL, consent_epoch INTEGER NOT NULL, " +
									"collected_data_epoch INTEGER NOT NULL, retained_from_ms INTEGER, " +
									"rollout_revision INTEGER NOT NULL, execution_revision INTEGER NOT NULL, " +
									"owner_cas_token TEXT NOT NULL, logical_tracking_id TEXT, " +
									"manifest_revision INTEGER, status TEXT NOT NULL, " +
									"created_at_ms INTEGER NOT NULL, retired_at_ms INTEGER, " +
									"retire_reason TEXT, effect_checksum TEXT NOT NULL, " +
									"PRIMARY KEY(reference, source_kind, purpose))",
							)
							if (includeFinalIndex) {
								db.execSQL(
									"CREATE INDEX idx_source_caller_authority_retired " +
										"ON source_caller_accepted_authority(status, retired_at_ms)",
								)
								db.execSQL(
									"CREATE INDEX idx_source_caller_authority_status " +
										"ON source_caller_accepted_authority(status)",
								)
							}
						}
						if (includeStepsCountDomain) {
							db.execSQL(
								"CREATE TABLE imported_steps_entry (" +
									"identity TEXT NOT NULL PRIMARY KEY, " +
									"start_time_ms INTEGER NOT NULL)",
							)
							db.execSQL(
								"CREATE INDEX idx_imported_steps_entry_cursor " +
									"ON imported_steps_entry(start_time_ms DESC, identity DESC)",
							)
							db.execSQL(
								"CREATE TABLE ambient_steps_fact_revision (" +
									"logical_fact_id TEXT NOT NULL, " +
									"semantic_revision INTEGER NOT NULL, " +
									"operation TEXT NOT NULL, " +
									"effect_checksum TEXT NOT NULL, " +
									"applied_at_ms INTEGER NOT NULL, " +
									"PRIMARY KEY(logical_fact_id, semantic_revision))",
							)
							check(
								StepsCountDomainSchema.installIfAbsent(db) ==
									StepsCountDomainSchemaState.ValidV2,
							)
							createImportedPortableStepsCountDomainTables(db)
							stepsSchemaMutation?.invoke(db)
						}
						if (markerValue != null) {
							db.execSQL(
								"INSERT INTO room_master_table(id, identity_hash) VALUES (?, ?)",
								arrayOf(FINAL_V28_MARKER_ID, markerValue),
							)
						} else if (includeMarker) {
							createFinalV28SchemaAssemblyMarker(db)
						}
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = Unit
				})
				.build(),
		)
		helper.writableDatabase
		helper.close()
	}

	private fun databaseFile(): File = context.getDatabasePath(DATABASE_NAME)

	private fun createCorruptFileFamily(): Map<File, String> {
		val files = databaseFileFamily()
		createFixture(version = LAST_RELEASED_ACTIVE_DATABASE_VERSION)
		RandomAccessFile(files.first(), "rw").use { database ->
			database.seek(0L)
			database.write("not-a-sqlite-header".encodeToByteArray())
		}
		files.drop(1).forEachIndexed { index, file ->
			file.writeBytes(ByteArray(64) { offset -> ((index + 1) * 31 + offset).toByte() })
		}
		return files.associateWith(::sha256)
	}

	private fun assertFileFamilyUnchanged(expected: Map<File, String>) {
		expected.forEach { (file, hash) ->
			file.exists() shouldBe true
			sha256(file) shouldBe hash
		}
	}

	private fun databaseFileFamily(): List<File> {
		val main = databaseFile()
		return listOf(
			main,
			File("${main.path}-wal"),
			File("${main.path}-shm"),
			File("${main.path}-journal"),
		)
	}

	private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
		.digest(file.readBytes())
		.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

	private fun deleteFixture() {
		context.deleteDatabase(DATABASE_NAME)
		databaseFileFamily().forEach { it.delete() }
	}

	private companion object {
		const val DATABASE_NAME = "development-v28-containment-test.db"
	}
}
