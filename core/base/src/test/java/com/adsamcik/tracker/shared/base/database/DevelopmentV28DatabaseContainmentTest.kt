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
// One synthetic database fixture verifies the same non-destructive preflight and containment contract
// across development schema variants.
@Suppress("LargeClass")
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
	fun `both pre reconciliation assembly markers are contained`() {
		listOf(
			"tracker-v28-session-organization-20260927",
			"tracker-v28-adaptive-location-authority-20260926",
			"tracker-v28-reconciled-source-inputs-20260927",
			"tracker-v28-authenticated-retention-scope-proof-20260921",
		).forEach { marker ->
			deleteFixture()
			createFixture(28, includeFinalTable = true, includeFinalColumn = true,
				includeFinalIndex = true, markerValue = marker)
			preflight() shouldBe ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28)
		}
	}

	@Test
	fun `claimed current assembly without Location file identity is contained without changing the file`() {
		createFixture(28, includeMarker = true, includeFinalTable = true,
			includeFinalColumn = true, includeFinalIndex = true,
			locationSchemaMutation = { it.execSQL("DROP TABLE location_file_identity") })
		val file = context.getDatabasePath(DATABASE_NAME)
		val before = file.readBytes().toList()
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA)
		file.readBytes().toList() shouldBe before
	}

	@Test
	fun `pre adaptive Location development marker is stale`() {
		createFixture(28, includeFinalTable = true, includeFinalColumn = true,
			includeFinalIndex = true, markerValue = "tracker-v28-applied-source-plan-state-20260920")
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28)
	}

	@Test
	fun `current assembly requires annotation bounds adapter uniqueness and label cleanup`() {
		val mutations: List<(SupportSQLiteDatabase) -> Unit> = listOf(
			{ it.execSQL("DROP TABLE organization_annotation") },
			{ it.execSQL("DROP INDEX idx_organization_adapter"); it.execSQL("CREATE INDEX idx_organization_adapter ON organization_annotation(recording_adapter_key)") },
			{ it.execSQL("DROP INDEX idx_organization_bounds"); it.execSQL("CREATE INDEX idx_organization_bounds ON organization_annotation(hidden, end_time_ms, start_time_ms)") },
			{ it.execSQL("DROP TRIGGER clear_organization_activity_label") },
		)
		mutations.forEach { mutate ->
			deleteFixture()
			createFixture(28, includeMarker = true, includeFinalTable = true, includeFinalColumn = true,
				includeFinalIndex = true, stepsSchemaMutation = mutate)
			val before = context.getDatabasePath(DATABASE_NAME).readBytes().toList()
			preflight() shouldBe ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA)
			context.getDatabasePath(DATABASE_NAME).readBytes().toList() shouldBe before
		}
	}

	@Test
	fun `claimed current assembly without Location purpose authority is contained`() {
		createFixture(28, includeMarker = true, includeFinalTable = true,
			includeFinalColumn = true, includeFinalIndex = true,
			locationSchemaMutation = { it.execSQL("DROP TABLE location_sample_purpose_authority") })
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA)
	}

	@Test
	fun `claimed current assembly without unique Location receipt identity is contained`() {
		createFixture(28, includeMarker = true, includeFinalTable = true,
			includeFinalColumn = true, includeFinalIndex = true,
			locationSchemaMutation = { it.execSQL("DROP INDEX idx_location_receipt_event") })
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA)
	}

	@Test
	fun `known development v27 routes to the normal migration path`() {
		createFixture(version = INITIAL_ACTIVE_DATABASE_VERSION)

		preflight() shouldBe ActiveDatabasePreflightResult.KnownDevelopmentV27
	}

	@Test
	fun `fresh final v28 marker is accepted on every reopen`() {
		createFixture(
			version = 28,
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
			version = 28,
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
			version = 28,
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
			version = 28,
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
			version = 28,
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
			version = 28,
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
	fun `previous portable staging marker is classified as stale`() {
		createFixture(
			version = 28,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			markerValue = "tracker-v28-portable-steps-disk-staging-20260920",
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
		)
	}

	@Test
	fun `pre retention proof lifecycle marker is classified as stale`() {
		createFixture(
			version = 28,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			markerValue = "tracker-v28-lifecycle-source-proof-20260920",
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
		)
	}

	@Test
	fun `marked v28 missing an indispensable final table is contained`() {
		createFixture(
			version = 28,
			includeMarker = true,
			includeFinalColumn = true,
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing an applied plan payload column is contained`() {
		APPLIED_PLAN_PAYLOAD_COLUMNS.forEach { missingColumn ->
			deleteFixture()
			createFixture(
				version = 28,
				includeMarker = true,
				includeFinalTable = true,
				includeFinalColumn = true,
				includeFinalIndex = true,
				appliedPlanPayloadColumns = APPLIED_PLAN_PAYLOAD_COLUMNS - missingColumn,
			)

			preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
				ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
			)
		}
	}

	@Test
	fun `pre-retention v28 missing retention sentinels is contained`() {
		createFixture(
			version = 28,
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
	fun `marked v28 missing source event retention proof is contained`() {
		createFixture(
			version = 28,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP TABLE source_event_retention_proof")
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 with incomplete source event retention authority is contained`() {
		createFixture(
			version = 28,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP TABLE source_event_retention_proof")
				database.execSQL(
					"CREATE TABLE source_event_retention_proof (" +
						"source_kind INTEGER NOT NULL, event_id TEXT NOT NULL, " +
						"source_admission_ordinal INTEGER NOT NULL, " +
						"collected_data_epoch INTEGER NOT NULL, reason TEXT NOT NULL, " +
						"effect_checksum TEXT NOT NULL, PRIMARY KEY(source_kind, event_id))",
				)
				database.execSQL(
					"CREATE UNIQUE INDEX idx_source_event_retention_proof_admission " +
						"ON source_event_retention_proof(source_kind, source_admission_ordinal)",
				)
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing retained source scope authority is contained`() {
		createFixture(
			version = 28,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP TABLE source_event_retention_scope_proof")
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing exact Pressure lineage retention authority is contained`() {
		createFixture(
			version = 28,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP TABLE pressure_fact_retention_authority")
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing Pressure retained revision authority is contained`() {
		createFixture(
			version = 28,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP TABLE pressure_fact_retention_revision_authority")
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `marked v28 missing Pressure retention resurrection trigger is contained`() {
		createFixture(
			version = 28,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP TRIGGER reject_pressure_fact_retention_resurrection")
			},
		)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
	}

	@Test
	fun `same named Pressure retention trigger on another table is preserved and contained`() {
		createFixture(
			version = 28,
			includeMarker = true,
			includeFinalTable = true,
			includeFinalColumn = true,
			includeFinalIndex = true,
			stepsSchemaMutation = { database ->
				database.execSQL("DROP TRIGGER reject_pressure_fact_retention_resurrection")
				database.execSQL(
					"CREATE TRIGGER reject_pressure_fact_retention_resurrection " +
						"BEFORE INSERT ON source_event_retention_proof BEGIN SELECT 1; END",
				)
			},
		)
		val before = databaseFileFamily().filter(File::exists).associateWith(::sha256)

		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
		)
		assertFileFamilyUnchanged(before)
	}

	@Test
	fun `same named weakened authority indexes are preserved and contained`() {
		val definitions = listOf(
			"idx_imported_wifi_deletion_scope" to
				"CREATE INDEX idx_imported_wifi_deletion_scope " +
					"ON imported_wifi_deletion_generation(deletion_scope_digest)",
			"idx_imported_wifi_deletion_scope" to
				"CREATE UNIQUE INDEX idx_imported_wifi_deletion_scope " +
					"ON imported_wifi_deletion_generation(deletion_scope_digest) WHERE 0",
			"idx_imported_wifi_deletion_scope" to
				"CREATE UNIQUE INDEX idx_imported_wifi_deletion_scope " +
					"ON imported_wifi_deletion_generation(deletion_scope_digest COLLATE NOCASE)",
			"idx_ambient_steps_retention_scope" to
				"CREATE UNIQUE INDEX idx_ambient_steps_retention_scope " +
					"ON ambient_steps_retention_authority(scope)",
			"idx_ambient_steps_retention_scope" to
				"CREATE INDEX idx_ambient_steps_retention_scope " +
					"ON ambient_steps_retention_authority(scope) WHERE 0",
			"idx_source_caller_authority_status" to
				"CREATE UNIQUE INDEX idx_source_caller_authority_status " +
					"ON source_caller_accepted_authority(status)",
			"idx_source_caller_authority_status" to
				"CREATE INDEX idx_source_caller_authority_status " +
					"ON source_caller_accepted_authority(status) WHERE 0",
		)
		definitions.forEach { (name, definition) ->
			deleteFixture()
			createFixture(
				version = 28,
				includeMarker = true,
				includeFinalTable = true,
				includeFinalColumn = true,
				includeFinalIndex = true,
				stepsSchemaMutation = { database ->
					database.execSQL("DROP INDEX `$name`")
					database.execSQL(definition)
				},
			)
			val before = databaseFileFamily().filter(File::exists).associateWith(::sha256)

			preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
				ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V28_SCHEMA,
			)
			assertFileFamilyUnchanged(before)
		}
	}

	@Test
	fun `marked v28 missing an indispensable final column is contained`() {
		createFixture(
			version = 28,
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
			version = 28,
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
			version = 28,
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
			version = 28,
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
			version = 28,
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
			version = 28,
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
			version = 28,
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
			version = 28,
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
			version = 28,
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
			version = 28,
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
			version = 28,
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
	fun `corrupt database family is never opened or changed`() {
		val before = createCorruptFileFamily()

		val result = preflight()
		// Native SQLite reports corruption; Robolectric's native bridge can instead report an
		// operational open failure. Both states must prevent the delegate from touching the files.
		check(
			result == ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.UNREADABLE_DATABASE) ||
				result == ActiveDatabasePreflightResult.Retryable(
					ActiveDatabaseRetryableReason.OPERATIONALLY_UNAVAILABLE,
				),
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

		when (result) {
			is ActiveDatabasePreflightResult.Blocked -> {
				shouldThrow<ActiveDatabaseOpenBlockedException> {
					helper.writableDatabase
				}.reason shouldBe ActiveDatabaseBlockReason.UNREADABLE_DATABASE
			}
			is ActiveDatabasePreflightResult.Retryable -> {
				shouldThrow<ActiveDatabaseOpenRetryableException> {
					helper.writableDatabase
				}.reason shouldBe ActiveDatabaseRetryableReason.OPERATIONALLY_UNAVAILABLE
			}
			else -> error("Corrupt database admitted")
		}
		delegateOpened shouldBe false
		assertFileFamilyUnchanged(before)
		helper.close()
	}

	@Test
	fun `blocked preflight never opens or changes the database file`() {
		createFixture(
			version = 28,
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
	fun `development v27 migration failure is contained without replacing the source file`() {
		createFixture(version = INITIAL_ACTIVE_DATABASE_VERSION)
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
			ActiveDatabaseBlockReason.DEVELOPMENT_V27_MIGRATION_VALIDATION_FAILED
		helper.close()
		preflight() shouldBe ActiveDatabasePreflightResult.KnownDevelopmentV27
	}

	@Test
	fun `database contention is retryable and a later preflight succeeds`() {
		createFixture(version = INITIAL_ACTIVE_DATABASE_VERSION)
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
						INITIAL_ACTIVE_DATABASE_VERSION,
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
		lockingDatabase.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
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

		preflight() shouldBe ActiveDatabasePreflightResult.KnownDevelopmentV27
		guardedHelper.readableDatabase
		delegateOpened shouldBe true
		guardedHelper.close()
	}

	@Test
	fun `guarded delegate contention remains retryable`() {
		createFixture(
			version = 28,
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

	@Test
	fun `v29 missing an original released column stays preserved and blocked`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME).allowMainThreadQueries().build()
		try {
			database.openHelper.writableDatabase.execSQL("ALTER TABLE cell_sample RENAME COLUMN legacy_mnc TO missing_legacy_mnc")
		} finally { database.close() }
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA)
		databaseFile().exists() shouldBe true
	}

	@Test
	fun `v29 missing an original compatibility table stays preserved and blocked`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME).allowMainThreadQueries().build()
		try {
			database.openHelper.writableDatabase.execSQL("DROP TABLE legacy_location_wifi_count")
		} finally { database.close() }
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA)
		databaseFile().exists() shouldBe true
	}

	private fun preflight(): ActiveDatabasePreflightResult =
		ActiveDatabasePreflight(databaseFile()).inspect()

	@Test
	fun `fresh v29 database carries radio child tables and legacy copy triggers`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME)
			.allowMainThreadQueries()
			.build()
		try {
			database.openHelper.writableDatabase
		} finally {
			database.close()
		}
		preflight() shouldBe ActiveDatabasePreflightResult.FinalV29
	}

	@Test
	fun `older unshipped v29 without Steps origin is preserved and blocked`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME).allowMainThreadQueries().build()
		try {
			database.openHelper.writableDatabase.execSQL(
				"ALTER TABLE session_manifest_source RENAME COLUMN steps_recording_manifest_revision TO missing_steps_origin")
		} finally { database.close() }
		val preserved = databaseFile().readBytes()
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA)
		databaseFile().readBytes().contentEquals(preserved) shouldBe true
	}

	@Test
	fun `v29 database missing a radio child table is blocked before Room opens`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME)
			.allowMainThreadQueries()
			.build()
		try {
			database.openHelper.writableDatabase.execSQL("DROP TABLE legacy_wifi_child")
		} finally {
			database.close()
		}
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA,
		)
	}

	@Test
	fun `older development v29 without the Wi-Fi carrier marker is preserved and blocked`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME)
			.allowMainThreadQueries().build()
		try {
			database.openHelper.writableDatabase.execSQL(
				"ALTER TABLE wifi_captured_fact_revision RENAME COLUMN payload_version TO missing_payload_version")
		} finally {
			database.close()
		}
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA)
		databaseFile().exists() shouldBe true
	}

	@Test
	fun `v29 database missing a background group column is blocked before Room opens`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME)
			.allowMainThreadQueries()
			.build()
		try {
			database.openHelper.writableDatabase.execSQL(
				"ALTER TABLE ambient_wifi_fact_revision RENAME COLUMN purpose_demand_id TO lost_purpose_demand_id",
			)
		} finally {
			database.close()
		}
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA,
		)
	}

	@Test
	fun `v29 blocks a nonzero background host default`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME)
			.allowMainThreadQueries()
			.build()
		try {
			val db = database.openHelper.writableDatabase
			db.execSQL("ALTER TABLE source_policy RENAME COLUMN background_notification_host_enabled TO old_host_enabled")
			db.execSQL("ALTER TABLE source_policy ADD COLUMN background_notification_host_enabled INTEGER NOT NULL DEFAULT 1")
		} finally {
			database.close()
		}
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA,
		)
	}

	@Test
	fun `v29 database missing Location archive membership is blocked before Room opens`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME).allowMainThreadQueries().build()
		try {
			database.openHelper.writableDatabase.execSQL("DROP TABLE location_file_archive_point")
		} finally {
			database.close()
		}
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA,
		)
	}

	@Test
	fun `v29 database with nullable Location proof is blocked before Room opens`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME).allowMainThreadQueries().build()
		try {
			val db = database.openHelper.writableDatabase
			db.execSQL("ALTER TABLE location_accepted_receipt RENAME COLUMN retained_proof_checksum TO old_proof_checksum")
			db.execSQL("ALTER TABLE location_accepted_receipt ADD COLUMN retained_proof_checksum TEXT DEFAULT ''")
		} finally {
			database.close()
		}
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA,
		)
	}

	@Test
	fun `v29 database missing archive identity index is blocked before Room opens`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME).allowMainThreadQueries().build()
		try {
			database.openHelper.writableDatabase.execSQL("DROP INDEX idx_location_archive_point_identity")
		} finally {
			database.close()
		}
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA,
		)
	}

	@Test
	fun `v29 database missing imported annotation selection is blocked before Room opens`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME).allowMainThreadQueries().build()
		try {
			database.openHelper.writableDatabase.execSQL(
				"ALTER TABLE organization_annotation RENAME COLUMN imported_source_selection TO missing_imported_selection",
			)
		} finally {
			database.close()
		}
		preflight() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA,
		)
	}

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
		appliedPlanPayloadColumns: Set<String> = APPLIED_PLAN_PAYLOAD_COLUMNS,
		stepsSchemaMutation: ((SupportSQLiteDatabase) -> Unit)? = null,
		locationSchemaMutation: ((SupportSQLiteDatabase) -> Unit)? = null,
		markerValue: String? = null,
	) {
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(version) {
					// Keep the synthetic development-schema fixture declarations and their explicit
					// missing-table/index/column switches visible together.
					@Suppress("LongMethod")
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
                            db.execSQL("CREATE TABLE acquisition_plan_revision (balance_profile_version INTEGER)")
                            db.execSQL("CREATE TABLE source_desired_plan (requested_balance_level INTEGER, focus_requested INTEGER NOT NULL DEFAULT 0)")
							createLocationFileIdentityTable(db)
							createLocationPurposeTables(db)
							db.execSQL("CREATE TABLE location_observation_decision (" +
								"writer_projection_id TEXT, writer_projection_version INTEGER, " +
								"writer_binding_generation INTEGER, writer_owner_generation INTEGER)")
							db.execSQL("CREATE TABLE source_policy (balance_profile_version INTEGER, " +
								"balance_level INTEGER, overall_balance_level INTEGER, source_balance_override INTEGER, location_background_mode INTEGER, " +
								"location_motion_assistance_enabled INTEGER NOT NULL DEFAULT 0)")
							locationSchemaMutation?.invoke(db)
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
							val appliedPayloadColumns = buildList {
								if ("applied_payload_version" in appliedPlanPayloadColumns) {
										add("applied_payload_version INTEGER")
								}
								if ("applied_payload" in appliedPlanPayloadColumns) {
										add("applied_payload BLOB")
								}
								if ("applied_payload_checksum" in appliedPlanPayloadColumns) {
										add("applied_payload_checksum TEXT")
								}
							}.joinToString(separator = ", ")
								.takeIf(String::isNotEmpty)
								?.let { columns -> ", $columns" }
								.orEmpty()
							db.execSQL(
								"CREATE TABLE source_applied_plan_state (" +
										"source_kind INTEGER NOT NULL PRIMARY KEY, " +
										"desired_revision INTEGER NOT NULL, applied_revision INTEGER, " +
										"source_instance_id TEXT, registration_generation INTEGER, " +
										"applied_at_elapsed_nanos INTEGER, status TEXT NOT NULL, " +
										"degraded_reasons TEXT NOT NULL, updated_at_ms INTEGER NOT NULL" +
										"$appliedPayloadColumns)",
							)
						}
						if (includeRetentionTables) {
							db.execSQL(
								"CREATE TABLE pressure_fact_revision (" +
									"writer_projection_id TEXT NOT NULL, " +
									"writer_projection_version INTEGER NOT NULL, " +
									"logical_fact_id TEXT NOT NULL, " +
									"source_event_id TEXT NOT NULL, " +
									"source_admission_ordinal INTEGER NOT NULL, " +
									"PRIMARY KEY(writer_projection_id, writer_projection_version, " +
									"logical_fact_id))",
							)
							db.execSQL(
								"CREATE TABLE source_event_retention_proof (" +
									"source_kind INTEGER NOT NULL, event_id TEXT NOT NULL, " +
									"source_admission_ordinal INTEGER NOT NULL, " +
									"purpose_eligibility_mask INTEGER NOT NULL, " +
									"source_policy_revision INTEGER, capture_consent_epoch INTEGER, " +
									"collected_data_epoch INTEGER NOT NULL, " +
									"logical_tracking_id TEXT, service_run_id TEXT, " +
									"scope_deletion_generation INTEGER, " +
									"logical_run_scope_digest TEXT, " +
									"wal_payload_checksum TEXT NOT NULL, " +
									"wal_integrity_identity TEXT NOT NULL, " +
									"proof_authority_fingerprint TEXT NOT NULL, reason TEXT NOT NULL, " +
									"effect_checksum TEXT NOT NULL, PRIMARY KEY(source_kind, event_id))",
							)
							db.execSQL(
								"CREATE TABLE source_event_retention_scope_proof (" +
									"source_kind INTEGER NOT NULL, purpose TEXT NOT NULL, " +
									"logical_tracking_id TEXT NOT NULL, service_run_id TEXT NOT NULL, " +
									"source_policy_revision INTEGER NOT NULL, " +
									"capture_consent_epoch INTEGER NOT NULL, " +
									"collected_data_epoch INTEGER NOT NULL, " +
									"scope_deletion_generation INTEGER NOT NULL, " +
									"logical_run_scope_digest TEXT NOT NULL, " +
									"retained_event_high_water_ordinal INTEGER NOT NULL, " +
									"retained_event_authority_checksum TEXT NOT NULL, " +
									"effect_checksum TEXT NOT NULL, " +
									"PRIMARY KEY(source_kind, logical_run_scope_digest))",
							)
							db.execSQL(
								"CREATE TABLE captured_radio_fact_retention_proof (" +
									"source_kind INTEGER NOT NULL, writer_projection_id TEXT NOT NULL, " +
									"writer_projection_version INTEGER NOT NULL, " +
									"writer_binding_generation INTEGER NOT NULL, " +
									"writer_owner_generation INTEGER NOT NULL, " +
									"logical_fact_id TEXT NOT NULL, semantic_revision INTEGER NOT NULL, " +
									"mutation_id TEXT NOT NULL, fact_effect_checksum TEXT NOT NULL, " +
									"source_event_id TEXT NOT NULL, source_admission_ordinal INTEGER NOT NULL, " +
									"wal_integrity_identity TEXT NOT NULL, " +
									"source_policy_revision INTEGER NOT NULL, " +
									"capture_consent_epoch INTEGER NOT NULL, " +
									"collected_data_epoch INTEGER NOT NULL, " +
									"logical_tracking_id TEXT NOT NULL, " +
									"service_run_id TEXT NOT NULL, " +
									"scope_deletion_generation INTEGER NOT NULL, " +
									"logical_run_scope_digest TEXT NOT NULL, " +
									"cursor_revision INTEGER NOT NULL, cursor_mutation_id TEXT NOT NULL, " +
									"cursor_effect_checksum TEXT NOT NULL, " +
									"cursor_source_admission_ordinal INTEGER NOT NULL, " +
									"cursor_updated_at_ms INTEGER NOT NULL, " +
									"aggregate_owner_logical_fact_id TEXT, " +
									"aggregate_owner_semantic_revision INTEGER, " +
									"aggregate_owner_source_event_id TEXT, " +
									"aggregate_owner_source_admission_ordinal INTEGER, " +
									"aggregate_owner_wal_integrity_identity TEXT, " +
									"aggregate_owner_effect_checksum TEXT, " +
									"aggregate_owner_cursor_revision INTEGER, " +
									"retained_from_ms INTEGER NOT NULL, retained_at_ms INTEGER NOT NULL, " +
									"reason TEXT NOT NULL, effect_checksum TEXT NOT NULL, " +
									"PRIMARY KEY(source_kind, writer_projection_id, " +
									"writer_projection_version, logical_fact_id))",
							)
							db.execSQL(
								"CREATE TABLE pressure_fact_retention_authority (" +
									"writer_projection_id TEXT NOT NULL, " +
									"writer_projection_version INTEGER NOT NULL, " +
									"logical_fact_id TEXT NOT NULL, " +
									"current_semantic_revision INTEGER NOT NULL, " +
									"current_source_event_id TEXT NOT NULL, " +
									"current_source_admission_ordinal INTEGER NOT NULL, " +
									"current_source_policy_revision INTEGER NOT NULL, " +
									"current_capture_consent_epoch INTEGER NOT NULL, " +
									"trigger_semantic_revision INTEGER NOT NULL, " +
									"trigger_source_event_id TEXT NOT NULL, " +
									"trigger_source_admission_ordinal INTEGER NOT NULL, " +
									"trigger_source_policy_revision INTEGER NOT NULL, " +
									"trigger_capture_consent_epoch INTEGER NOT NULL, " +
									"logical_tracking_id TEXT NOT NULL, service_run_id TEXT NOT NULL, " +
									"collected_data_epoch INTEGER NOT NULL, retained_from_ms INTEGER NOT NULL, " +
									"lineage_interval_start_ms INTEGER NOT NULL, " +
									"lineage_interval_end_ms INTEGER NOT NULL, " +
									"lineage_earliest_possible_start_ms INTEGER NOT NULL, " +
									"lineage_revision_count INTEGER NOT NULL, " +
									"lineage_effect_checksum TEXT NOT NULL, " +
									"revision_authority_set_checksum TEXT NOT NULL, " +
									"retained_at_ms INTEGER NOT NULL, " +
									"reason TEXT NOT NULL, effect_checksum TEXT NOT NULL, " +
									"PRIMARY KEY(writer_projection_id, writer_projection_version, " +
									"logical_fact_id))",
							)
							db.execSQL(
								"CREATE TABLE pressure_fact_retention_revision_authority (" +
									"writer_projection_id TEXT NOT NULL, " +
									"writer_projection_version INTEGER NOT NULL, " +
									"logical_fact_id TEXT NOT NULL, semantic_revision INTEGER NOT NULL, " +
									"source_event_id TEXT NOT NULL, " +
									"source_admission_ordinal INTEGER NOT NULL, " +
									"source_policy_revision INTEGER NOT NULL, " +
									"capture_consent_epoch INTEGER NOT NULL, " +
									"revision_effect_checksum TEXT NOT NULL, " +
									"parent_authority_checksum TEXT NOT NULL, " +
									"effect_checksum TEXT NOT NULL, " +
									"PRIMARY KEY(writer_projection_id, writer_projection_version, " +
									"logical_fact_id, semantic_revision), " +
									"FOREIGN KEY(writer_projection_id, writer_projection_version, " +
									"logical_fact_id) REFERENCES pressure_fact_retention_authority(" +
									"writer_projection_id, writer_projection_version, logical_fact_id) " +
									"ON DELETE CASCADE)",
							)
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
									"settled_retained_from_ms INTEGER, " +
									"source_maintenance_at_ms INTEGER, " +
									"retention_active_sources TEXT)",
							)
							db.execSQL(
								"CREATE TABLE retention_work_execution_receipt (" +
									"execution_id TEXT PRIMARY KEY, work_request_id TEXT, " +
									"execution_generation INTEGER, worker_kind TEXT, " +
									"started_at_ms INTEGER, state TEXT, destructive_plan TEXT, " +
									"updated_at_ms INTEGER)",
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
									"CREATE UNIQUE INDEX idx_source_event_retention_proof_admission " +
										"ON source_event_retention_proof(" +
										"source_kind, source_admission_ordinal)",
								)
								db.execSQL(
									"CREATE UNIQUE INDEX idx_source_event_retention_scope_run " +
										"ON source_event_retention_scope_proof(" +
										"source_kind, logical_tracking_id, service_run_id)",
								)
								db.execSQL(
									"CREATE UNIQUE INDEX idx_captured_radio_retention_event " +
										"ON captured_radio_fact_retention_proof(" +
										"source_kind, source_event_id)",
								)
								db.execSQL(
									"CREATE INDEX idx_captured_radio_retention_floor " +
										"ON captured_radio_fact_retention_proof(" +
										"source_kind, retained_from_ms)",
								)
								db.execSQL(
									"CREATE INDEX idx_captured_radio_retention_run_admission " +
										"ON captured_radio_fact_retention_proof(source_kind, " +
										"logical_tracking_id, service_run_id, collected_data_epoch, " +
										"source_admission_ordinal)",
								)
								db.execSQL("CREATE TABLE activity_captured_evidence (" +
								"writer_projection_id TEXT, writer_projection_version INTEGER, " +
								"source_admission_ordinal INTEGER)")
								db.execSQL("CREATE INDEX idx_activity_captured_evidence_writer_admission " +
								"ON activity_captured_evidence(writer_projection_id, writer_projection_version, " +
								"source_admission_ordinal)")
								listOf("wifi", "cell").forEach { source ->
									db.execSQL("CREATE TABLE ${source}_captured_fact_revision (" +
										"writer_projection_id TEXT, writer_projection_version INTEGER, " +
										"source_admission_ordinal INTEGER)")
									db.execSQL("CREATE INDEX idx_${source}_captured_fact_admission " +
										"ON ${source}_captured_fact_revision(writer_projection_id, " +
										"writer_projection_version, source_admission_ordinal)")
								}
								db.execSQL(
									"CREATE INDEX idx_ambient_steps_retention_scope " +
										"ON ambient_steps_retention_authority(scope)",
								)
								db.execSQL(
									"CREATE UNIQUE INDEX idx_pressure_retention_current_event " +
										"ON pressure_fact_retention_authority(writer_projection_id, " +
										"writer_projection_version, current_source_event_id)",
								)
								db.execSQL(
									"CREATE UNIQUE INDEX idx_pressure_retention_current_admission " +
										"ON pressure_fact_retention_authority(writer_projection_id, " +
										"writer_projection_version, current_source_admission_ordinal)",
								)
								db.execSQL(
									"CREATE INDEX idx_pressure_retention_trigger_evidence " +
										"ON pressure_fact_retention_authority(trigger_source_event_id, " +
										"trigger_source_admission_ordinal)",
								)
								db.execSQL(
									"CREATE INDEX idx_pressure_retention_run_scope " +
										"ON pressure_fact_retention_authority(service_run_id, " +
										"logical_tracking_id, logical_fact_id)",
								)
								db.execSQL(
									"CREATE UNIQUE INDEX idx_pressure_retention_revision_event " +
										"ON pressure_fact_retention_revision_authority(" +
										"writer_projection_id, writer_projection_version, " +
										"source_event_id)",
								)
								db.execSQL(
									"CREATE UNIQUE INDEX idx_pressure_retention_revision_admission " +
										"ON pressure_fact_retention_revision_authority(" +
										"writer_projection_id, writer_projection_version, " +
										"source_admission_ordinal)",
								)
								db.execSQL(
									"CREATE UNIQUE INDEX idx_pressure_fact_revision_writer_admission " +
										"ON pressure_fact_revision(writer_projection_id, " +
										"writer_projection_version, source_admission_ordinal)",
								)
								db.execSQL(
									"CREATE TRIGGER reject_pressure_fact_retention_resurrection " +
										"BEFORE INSERT ON pressure_fact_revision WHEN EXISTS (" +
										"SELECT 1 FROM pressure_fact_retention_authority AS retained " +
										"WHERE retained.writer_projection_id = NEW.writer_projection_id " +
										"AND retained.writer_projection_version = " +
										"NEW.writer_projection_version " +
										"AND retained.logical_fact_id = NEW.logical_fact_id) " +
										"OR EXISTS (SELECT 1 FROM " +
										"pressure_fact_retention_revision_authority AS revision " +
										"WHERE revision.writer_projection_id = " +
										"NEW.writer_projection_id AND " +
										"revision.writer_projection_version = " +
										"NEW.writer_projection_version AND (" +
										"revision.logical_fact_id = NEW.logical_fact_id OR " +
										"revision.source_event_id = NEW.source_event_id OR " +
										"revision.source_admission_ordinal = " +
										"NEW.source_admission_ordinal)) BEGIN " +
										"SELECT RAISE(ABORT, 'Pressure retention authority rejects " +
										"fact resurrection'); END",
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
							createOrganizationAnnotationTable(db)
							db.execSQL("CREATE TABLE IF NOT EXISTS activity (id INTEGER NOT NULL PRIMARY KEY)")
							createOrganizationAnnotationLabelTrigger(db)
							stepsSchemaMutation?.invoke(db)
						}
						if (markerValue != null) {
							db.execSQL(
								"INSERT INTO room_master_table(id, identity_hash) VALUES (?, ?)",
								arrayOf<Any?>(FINAL_V28_MARKER_ID, markerValue),
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

	private companion object {
		const val DATABASE_NAME = "development-v28-containment-test.db"
		val APPLIED_PLAN_PAYLOAD_COLUMNS = setOf(
			"applied_payload_version",
			"applied_payload",
			"applied_payload_checksum",
		)
	}

	private fun createCorruptFileFamily(): Map<File, String> {
		val files = databaseFileFamily()
		createFixture(version = INITIAL_ACTIVE_DATABASE_VERSION)
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

}
