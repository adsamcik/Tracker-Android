package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.Database
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainSchemaMarkerEntity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass", "TooManyFunctions")
class StepsCountDomainSchemaAndMaintenanceTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `DDL is idempotent and installs exact sentinel indexes foreign keys and triggers`() {
		StepsCountDomainSchema.inspect(database.openHelper.writableDatabase) shouldBe
			StepsCountDomainSchemaState.Absent
		installSchema()
		StepsCountDomainSchema.inspect(database.openHelper.writableDatabase) shouldBe
			StepsCountDomainSchemaState.ValidV2
		installSchema()
		val sqlite = database.openHelper.writableDatabase

		sqlite.count(
			"SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN (?, ?, ?, ?)",
			arrayOf(
				StepsCountDomainSchema.RECEIPT_TABLE,
				StepsCountDomainSchema.OWNER_TABLE,
				StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE,
				StepsCountDomainSchema.SCHEMA_MARKER_TABLE,
			),
		) shouldBe 4L
		sqlite.count(
			"SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name IN (?, ?, ?)",
			arrayOf(
				StepsCountDomainSchema.AMBIENT_NO_RESURRECTION_TRIGGER,
				StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER,
				StepsCountDomainSchema.TERMINAL_OWNER_TRIGGER,
			),
		) shouldBe 3L
		sqlite.count(
			"SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
				"AND name IN (?, ?, ?, ?, ?, ?)",
			arrayOf(
				"idx_steps_count_domain_receipt_owner",
				"idx_steps_count_domain_receipt_compatibility",
				"idx_steps_count_domain_owner_scope",
				"idx_steps_count_domain_owner_receipt",
				"idx_steps_count_domain_owner_terminal_age",
				"idx_steps_count_domain_completeness_owner",
			),
		) shouldBe 6L
		sqlite.count(
			"SELECT COUNT(*) FROM steps_count_domain_schema_marker WHERE id = 1 " +
				"AND contract_version = 2 AND token_semantics = 'PROVIDER_COUNTER_EPOCH_V1' " +
				"AND terminal_unproven = 1",
		) shouldBe 1L
		sqlite.count(
			"SELECT COUNT(*) FROM pragma_foreign_key_list('steps_count_domain_owner_revision') " +
				"WHERE `table` = 'steps_count_domain_receipt' AND on_delete = 'RESTRICT'",
		) shouldBe 1L
		sqlite.count(
			"SELECT COUNT(*) FROM pragma_foreign_key_list(" +
				"'steps_count_domain_completeness_marker') " +
				"WHERE `table` = 'steps_count_domain_owner_revision' AND on_delete = 'CASCADE'",
		) shouldBe 3L
	}

	@Test
	fun `Room entity scaffold is recognized and completed atomically from its creation callback`() {
		val context: Application = ApplicationProvider.getApplicationContext()
		var observedState: StepsCountDomainSchemaState? = null
		var installedState: StepsCountDomainSchemaState? = null
		val scaffold = Room.inMemoryDatabaseBuilder(
			context,
			StepsCountDomainScaffoldTestDatabase::class.java,
		).allowMainThreadQueries()
			.addCallback(object : RoomDatabase.Callback() {
				override fun onCreate(db: SupportSQLiteDatabase) {
					observedState = StepsCountDomainSchema.inspect(db)
					installedState = StepsCountDomainSchema.installIfAbsent(db)
				}
			})
			.build()
		try {
			scaffold.openHelper.writableDatabase
			observedState shouldBe StepsCountDomainSchemaState.FreshRoomScaffold
			installedState shouldBe StepsCountDomainSchemaState.ValidV2
			StepsCountDomainSchema.inspect(scaffold.openHelper.writableDatabase) shouldBe
				StepsCountDomainSchemaState.ValidV2
		} finally {
			scaffold.close()
		}
	}

	@Test
	fun `Room 2_8_4 invalidation trigger lifecycle is accepted for every authority table`() {
		val context: Application = ApplicationProvider.getApplicationContext()
		val scaffold = Room.inMemoryDatabaseBuilder(
			context,
			StepsCountDomainScaffoldTestDatabase::class.java,
		).allowMainThreadQueries()
			.addCallback(object : RoomDatabase.Callback() {
				override fun onCreate(db: SupportSQLiteDatabase) {
					check(
						StepsCountDomainSchema.installIfAbsent(db) ==
							StepsCountDomainSchemaState.ValidV2,
					)
				}
			})
			.build()
		val observedTables = arrayOf(
			StepsCountDomainSchema.RECEIPT_TABLE,
			StepsCountDomainSchema.OWNER_TABLE,
			StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE,
			StepsCountDomainSchema.SCHEMA_MARKER_TABLE,
			"ambient_steps_fact_revision",
		)
		val observer = object : InvalidationTracker.Observer(
			observedTables.first(),
			*observedTables.drop(1).toTypedArray(),
		) {
			override fun onInvalidated(tables: Set<String>) = Unit
		}
		try {
			val sqlite = scaffold.openHelper.writableDatabase
			scaffold.invalidationTracker.addObserver(observer)

			val triggers = sqlite.query(
				"SELECT name FROM sqlite_temp_master WHERE type = 'trigger' " +
					"AND name LIKE 'room_table_modification_trigger_%' ORDER BY name",
			).use { cursor ->
				buildList {
					while (cursor.moveToNext()) add(cursor.getString(0))
				}
			}
			triggers shouldBe observedTables.flatMap { table ->
				listOf("DELETE", "INSERT", "UPDATE").map { operation ->
					"room_table_modification_trigger_${table}_$operation"
				}
			}.sorted()
			StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2

			scaffold.invalidationTracker.removeObserver(observer)
			sqlite.count(
				"SELECT COUNT(*) FROM sqlite_temp_master WHERE type = 'trigger' " +
					"AND name LIKE 'room_table_modification_trigger_%'",
			) shouldBe 0L
			StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2
		} finally {
			runCatching { scaffold.invalidationTracker.removeObserver(observer) }
			scaffold.close()
		}
	}

	@Test
	fun `real Room invalidation observer for an unrelated table is ignored by Steps authority`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		val table = "source_event_wal"
		val observer = object : InvalidationTracker.Observer(table) {
			override fun onInvalidated(tables: Set<String>) = Unit
		}
		try {
			database.invalidationTracker.addObserver(observer)

			sqlite.query(
				"SELECT name FROM sqlite_temp_master WHERE type = 'trigger' " +
					"AND tbl_name = ? ORDER BY name",
				arrayOf(table),
			).use { cursor ->
				buildList {
					while (cursor.moveToNext()) add(cursor.getString(0))
				}
			} shouldBe listOf("DELETE", "INSERT", "UPDATE").map { operation ->
				"room_table_modification_trigger_${table}_$operation"
			}
			StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2
		} finally {
			runCatching { database.invalidationTracker.removeObserver(observer) }
		}
	}

	@Test
	fun `Room-looking invalidation trigger with spoofed body is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		val name =
			"room_table_modification_trigger_${StepsCountDomainSchema.RECEIPT_TABLE}_INSERT"
		sqlite.execSQL(
			"CREATE TEMP TABLE IF NOT EXISTS room_table_modification_log (" +
				"table_id INTEGER PRIMARY KEY, invalidated INTEGER NOT NULL DEFAULT 0)",
		)
		sqlite.execSQL("INSERT OR REPLACE INTO room_table_modification_log VALUES (7, 0)")
		sqlite.execSQL(
			"CREATE TEMP TRIGGER `$name` AFTER INSERT ON " +
				"`${StepsCountDomainSchema.RECEIPT_TABLE}` BEGIN " +
				"UPDATE room_table_modification_log SET invalidated = 2 " +
				"WHERE table_id = 7 AND invalidated = 0; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `spoofed Room invalidation prefix on an unrelated target is rejected`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL("CREATE TEMP TABLE unrelated_room_target (value INTEGER NOT NULL)")
		sqlite.execSQL(
			"CREATE TEMP TRIGGER room_table_modification_trigger_unrelated_room_target_INSERT " +
				"AFTER INSERT ON unrelated_room_target BEGIN SELECT NEW.value; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `run high-water surfaces null blob blank and whitespace run identities without broad sweep`() =
		runTest {
			val wal = insertWal("raw-service-run-identity", 1L)
			val sqlite = database.openHelper.writableDatabase
			val corruptions = listOf(
				"" to ("text" to ""),
				" \t\n\r" to ("text" to " \t\n\r"),
				null to ("null" to null),
				byteArrayOf(1, 2) to ("blob" to null),
			)

			for ((storedValue, expected) in corruptions) {
				sqlite.execSQL(
					"UPDATE source_event_wal SET service_run_id = ? " +
						"WHERE admission_ordinal = ?",
					arrayOf(storedValue, wal.admissionOrdinal),
				)

				val evidence = database.sourceEventWalDao().rawRunSourceCaptureHighWater(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					logicalTrackingId = "tracking",
					serviceRunId = "run",
					runManifestRevisions = listOf(1L),
					throughOrdinal = wal.admissionOrdinal,
					capturePurposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
					allowedPurposeMask = SourceBrokerPurpose.ALL_MASK,
					limit = 2,
				).single()

				evidence.storageClassSignature?.split('|')?.get(4) shouldBe expected.first
				evidence.serviceRunId shouldBe expected.second
				evidence.admissionOrdinal shouldBe wal.admissionOrdinal
			}

			sqlite.execSQL(
				"UPDATE source_event_wal SET service_run_id = NULL, " +
					"logical_tracking_id = 'unrelated-logical' WHERE admission_ordinal = ?",
				arrayOf(wal.admissionOrdinal),
			)
			database.sourceEventWalDao().rawRunSourceCaptureHighWater(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				logicalTrackingId = "tracking",
				serviceRunId = "run",
				runManifestRevisions = listOf(1L),
				throughOrdinal = wal.admissionOrdinal,
				capturePurposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				allowedPurposeMask = SourceBrokerPurpose.ALL_MASK,
				limit = 2,
			) shouldBe emptyList()
		}

	@Test
	fun `only the exact Room 2_8_4 temporary invalidation log DDL is accepted`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		val spoofedDefinitions = listOf(
			"CREATE TEMP TABLE room_table_modification_log (" +
				"table_id INTEGER PRIMARY KEY CHECK(table_id >= 0), " +
				"invalidated INTEGER NOT NULL DEFAULT 0)",
			"CREATE TEMP TABLE room_table_modification_log (" +
				"table_id INTEGER PRIMARY KEY, invalidated INTEGER NOT NULL DEFAULT 1)",
			"CREATE TEMP TABLE room_table_modification_log (" +
				"table_id INTEGER PRIMARY KEY, invalidated INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID",
		)

		spoofedDefinitions.forEach { definition ->
			sqlite.execSQL(definition)
			StepsCountDomainSchema.inspect(sqlite) shouldBe
				StepsCountDomainSchemaState.Incompatible
			sqlite.execSQL("DROP TABLE room_table_modification_log")
		}

		sqlite.execSQL(
			"CREATE TEMP TABLE IF NOT EXISTS room_table_modification_log (" +
				"table_id INTEGER PRIMARY KEY, invalidated INTEGER NOT NULL DEFAULT 0)",
		)
		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2
		sqlite.execSQL("INSERT INTO room_table_modification_log VALUES (7, 2)")
		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `trigger attached to the Room invalidation log is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"CREATE TEMP TABLE IF NOT EXISTS room_table_modification_log (" +
				"table_id INTEGER PRIMARY KEY, invalidated INTEGER NOT NULL DEFAULT 0)",
		)
		sqlite.execSQL(
			"CREATE TEMP TRIGGER unexpected_room_log_trigger " +
				"AFTER UPDATE ON room_table_modification_log BEGIN SELECT 1; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `unexpected trigger attached to an authority table is incompatible regardless of name`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"CREATE TRIGGER unexpected_receipt_trigger AFTER INSERT ON " +
				"steps_count_domain_receipt BEGIN SELECT 1; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `exact expected Ambient trigger set is accepted`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		val ambientTriggers = sqlite.query(
			"SELECT name FROM sqlite_master WHERE type = 'trigger' " +
				"AND tbl_name = 'ambient_steps_fact_revision' ORDER BY name",
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) add(cursor.getString(0))
			}
		}

		ambientTriggers shouldBe listOf(
			StepsCountDomainSchema.AMBIENT_NO_RESURRECTION_TRIGGER,
			StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER,
		).sorted()
		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2
	}

	@Test
	fun `duplicate expected trigger metadata rows cannot collapse into the valid set`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.duplicateStoredTrigger(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `duplicate expected trigger identity in temp catalog is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL("CREATE TEMP TABLE unrelated_temp_trigger_host (value INTEGER NOT NULL)")
		sqlite.execSQL(
			"CREATE TEMP TRIGGER ${StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER} " +
				"AFTER INSERT ON temp.unrelated_temp_trigger_host BEGIN SELECT NEW.value; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `temporary authority table shadow is incompatible before Room trigger installation`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"CREATE TEMP TABLE steps_count_domain_receipt (shadow_value INTEGER NOT NULL)",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `attached shadow trigger does not count as a main authority trigger`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"ATTACH DATABASE ':memory:' AS shadow_catalog",
		)
		sqlite.execSQL(
			"CREATE TABLE shadow_catalog.ambient_steps_fact_revision " +
				"(shadow_value INTEGER NOT NULL)",
		)
		sqlite.execSQL(
			"CREATE TEMP TRIGGER unrelated_attached_shadow_trigger AFTER INSERT ON " +
				"shadow_catalog.ambient_steps_fact_revision BEGIN SELECT NEW.shadow_value; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2

		sqlite.execSQL(
			"CREATE TEMP TRIGGER arbitrary_main_receipt_trigger AFTER INSERT ON " +
				"main.steps_count_domain_receipt BEGIN SELECT 1; END",
		)
		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `temporary authority view shadow is incompatible before Room trigger installation`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"CREATE TEMP VIEW steps_count_domain_receipt AS " +
				"SELECT 1 AS shadow_value",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `qualified main trigger remains authority despite an authority named temp view`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"CREATE TEMP VIEW steps_count_domain_receipt AS " +
				"SELECT 1 AS shadow_value",
		)
		sqlite.execSQL(
			"CREATE TEMP TRIGGER explicit_main_authority_trigger AFTER INSERT ON " +
				"main.steps_count_domain_receipt BEGIN SELECT 1; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `unqualified temp trigger resolves an attached view through schema lookup`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL("ATTACH DATABASE ':memory:' AS attached_view_catalog")
		sqlite.execSQL(
			"CREATE VIEW attached_view_catalog.benign_attached_view AS " +
				"SELECT 1 AS shadow_value",
		)
		sqlite.execSQL(
			"CREATE TEMP TRIGGER benign_attached_view_shadow INSTEAD OF INSERT ON " +
				"benign_attached_view BEGIN SELECT NEW.shadow_value; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2
	}

	@Test
	fun `trigger authentication requires complete WHEN BEGIN body and END structure`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		val name = StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER
		val original = sqlite.storedTriggerSql(name)
		val malformed = listOf(
			original.substringBefore("WHEN") + "WHEN",
			original.substringBefore("BEGIN") + "BEGIN",
			original.substringBefore("BEGIN") + "BEGIN SELECT 1;",
			original.substringBeforeLast("END"),
			"$original SELECT 1",
			"$original;;",
			"$original /* trailing comment */",
		)

		malformed.forEach { sql ->
			sqlite.setStoredTriggerSql(name, sql)
			StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
			sqlite.setStoredTriggerSql(name, original)
			StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2
		}
	}

	@Test
	fun `trigger authentication normalizes only keyword case and insignificant whitespace`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.replaceTrigger(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replace("CREATE TRIGGER", "create\rtrigger")
				.replace("AFTER INSERT ON", "after\u000cinsert\n on")
				.replace("WHEN NEW.", "when\n NEW.")
				.replace("BEGIN", "begin")
				.replace("INSERT OR ABORT INTO", "insert\n or\tabort into")
				.replace("VALUES", "values")
				.replace("END", "end")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2
	}

	@Test
	fun `one trailing trigger semicolon is insignificant`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			"$sql;"
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2
	}

	@Test
	fun `Unicode long s cannot authenticate as ASCII S inside COALESCE`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.TERMINAL_OWNER_TRIGGER) { sql ->
			sql.replace("COALESCE", "COALE\u017fCE")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `Unicode whitespace cannot replace SQLite ASCII whitespace`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replaceFirst("CREATE TRIGGER", "CREATE\u2003TRIGGER")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `vertical tab cannot replace SQLite ASCII whitespace`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replaceFirst("CREATE TRIGGER", "CREATE\u000bTRIGGER")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `harmless ASCII comments preserve the exact trigger token sequence`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.replaceTrigger(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replace("WHEN NEW.", "WHEN /* injected */ NEW.")
				.replaceFirst("BEGIN", "BEGIN -- harmless\n")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2
	}

	@Test
	fun `comments cannot concatenate keyword tokens`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.TERMINAL_OWNER_TRIGGER) { sql ->
			sql.replaceFirst("INSERT", "IN/* split */SERT")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `unclosed quoting fails closed`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replace("'RETRACT'", "'RETRACT")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `unclosed block comment fails closed`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replace("WHEN NEW.", "WHEN /* unclosed NEW.")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `case changed trigger literal is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.replaceTrigger(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replace("'RETRACT'", "'retract'")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `alternate identifier quoting in an expected trigger is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.replaceTrigger(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replace("NEW.`operation`", "NEW.\"operation\"")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `escaped quoted identifier content remains exact`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replaceFirst("`operation`", "`oper``ation`")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `unclosed bracket identifier fails closed`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replaceFirst("`operation`", "[operation")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `quote injected trigger literal is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.replaceTrigger(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replace("'RETRACT'", "'''RETRACT'''")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `split multi character operator cannot authenticate`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.TERMINAL_OWNER_TRIGGER) { sql ->
			sql.replaceFirst("!=", "! =")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `split blob prefix and string literal cannot authenticate`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.TERMINAL_OWNER_TRIGGER) { sql ->
			sql.replaceFirst("''", "X '41'")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `split numeric exponent cannot authenticate`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.TERMINAL_OWNER_TRIGGER) { sql ->
			sql.replaceFirst("SELECT 1", "SELECT 1e +2")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `bare identifier case is not normalized as keyword case`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.TERMINAL_OWNER_TRIGGER) { sql ->
			sql.replace("AS terminal", "AS TERMINAL")
				.replace("terminal.", "TERMINAL.")
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `NULL stored trigger SQL fails closed`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.setStoredTriggerSql(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER, null)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `stored trigger SQL beyond lexical bound fails closed`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql + "/*" + "a".repeat(70_000) + "*/"
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `stored trigger token count beyond lexical bound fails closed`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.rewriteStoredTriggerSql(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql + ";".repeat(5_000)
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `extra expected-trigger body statement is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.replaceTrigger(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.substringBeforeLast("END") + "SELECT 1;\nEND"
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `arbitrary extra trigger attached to Ambient facts is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"CREATE TRIGGER arbitrary_ambient_trigger AFTER INSERT ON " +
				"ambient_steps_fact_revision BEGIN SELECT NEW.semantic_revision; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `arbitrary trigger on ASCII case variant authority table is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"CREATE TRIGGER arbitrary_case_variant_receipt AFTER INSERT ON " +
				"StEpS_CoUnT_DoMaIn_ReCeIpT BEGIN SELECT 1; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `arbitrary trigger on ASCII case variant Ambient table is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"CREATE TRIGGER arbitrary_case_variant_ambient AFTER INSERT ON " +
				"AmBiEnT_StEpS_FaCt_ReViSiOn BEGIN SELECT 1; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `forged unrelated tbl_name cannot hide arbitrary authority trigger`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		val trigger = "arbitrary_hidden_receipt_trigger"
		sqlite.execSQL(
			"CREATE TRIGGER $trigger AFTER INSERT ON " +
				"steps_count_domain_receipt BEGIN SELECT 1; END",
		)
		sqlite.setStoredTriggerTable(trigger, "source_policy")

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `qualified quoted authority target is derived through comments despite forged metadata`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		val trigger = "arbitrary_qualified_hidden_trigger"
		sqlite.execSQL("CREATE TABLE unrelated_trigger_host (value INTEGER NOT NULL)")
		sqlite.execSQL(
			"CREATE TRIGGER $trigger AFTER INSERT ON " +
				"unrelated_trigger_host BEGIN SELECT 1; END",
		)
		sqlite.rewriteStoredTriggerSql(trigger) { sql ->
			sql.replace(
				"ON unrelated_trigger_host",
				"ON /* authority target */ main . \"steps_count_domain_receipt\"",
			)
		}

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `temporary authority trigger participates in the exact trigger set`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"CREATE TEMP TRIGGER IF NOT EXISTS arbitrary_temp_receipt_trigger " +
				"AFTER INSERT ON main.steps_count_domain_receipt BEGIN SELECT 1; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `expected trigger table metadata is canonicalized with ASCII NOCASE only`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.setStoredTriggerTable(
			StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER,
			"AmBiEnT_StEpS_FaCt_ReViSiOn",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.ValidV2
	}

	@Test
	fun `Unicode table name case spoof cannot authenticate trigger metadata`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.setStoredTriggerTable(
			StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER,
			"ambient_\u017fteps_fact_revision",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `authority named trigger attached to unknown table remains incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL("CREATE TABLE unrelated_steps_trigger_host (value INTEGER NOT NULL)")
		sqlite.execSQL(
			"CREATE TRIGGER trg_steps_count_domain_unknown_attachment " +
				"AFTER INSERT ON unrelated_steps_trigger_host BEGIN SELECT 1; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `expected Ambient trigger name with impostor SQL is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"DROP TRIGGER ${StepsCountDomainSchema.AMBIENT_NO_RESURRECTION_TRIGGER}",
		)
		sqlite.execSQL(
			"CREATE TRIGGER ${StepsCountDomainSchema.AMBIENT_NO_RESURRECTION_TRIGGER} " +
				"BEFORE INSERT ON ambient_steps_fact_revision BEGIN SELECT 1; END",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `unexpected ordinary index attached to an authority table is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"CREATE INDEX unrelated_receipt_index ON " +
				"steps_count_domain_receipt(authority_revision)",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `extra UNIQUE table constraint and its autoindex are incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL("DROP TABLE steps_count_domain_schema_marker")
		sqlite.execSQL(
			"""
			CREATE TABLE steps_count_domain_schema_marker (
				id INTEGER NOT NULL,
				contract_version INTEGER NOT NULL,
				token_semantics TEXT NOT NULL,
				terminal_unproven INTEGER NOT NULL,
				PRIMARY KEY(id),
				UNIQUE(token_semantics)
			)
			""".trimIndent(),
		)
		insertSchemaMarker(sqlite)

		sqlite.count(
			"SELECT COUNT(*) FROM pragma_index_list('steps_count_domain_schema_marker') " +
				"WHERE name LIKE 'sqlite_autoindex_%'",
		) shouldBe 1L
		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `extra CHECK table constraint is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL("DROP TABLE steps_count_domain_schema_marker")
		sqlite.execSQL(
			"""
			CREATE TABLE steps_count_domain_schema_marker (
				id INTEGER NOT NULL,
				contract_version INTEGER NOT NULL,
				token_semantics TEXT NOT NULL,
				terminal_unproven INTEGER NOT NULL CHECK(terminal_unproven IN (0, 1)),
				PRIMARY KEY(id)
			)
			""".trimIndent(),
		)
		insertSchemaMarker(sqlite)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `count-domain owner and receipt reads authenticate every field in the requested scope`() =
		runTest {
			installSchema()
			val store = StepsCountDomainStore(database)
			val wal = insertWal("raw-count-domain", 41L, payloadVersion = 7)
			store.recordSessionWal(wal, token('a')) shouldBe StepsCountDomainWriteResult.INSERTED
			val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
				wal.admissionOrdinal,
				wal.eventId,
			)
			val key = StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				ownerIdentity,
				1L,
			)
			val stored = (store.readOwners(listOf(key)) as StepsCountDomainOwnerRead.Ready)
				.owners.getValue(key)
			val receipt = requireNotNull(stored.receipt)
			val sqlite = database.openHelper.writableDatabase

			val ownerMutations = listOf(
				StoredFieldMutation("scope_identity", "1", stored.owner.scopeIdentity),
				StoredFieldMutation("owner_revision", "'1x'", stored.owner.ownerRevision),
				StoredFieldMutation("operation", "X'01'", stored.owner.operation),
				StoredFieldMutation("receipt_identity", "1", stored.owner.receiptIdentity),
				StoredFieldMutation(
					"owner_effect_checksum",
					"NULL",
					stored.owner.ownerEffectChecksum,
				),
				StoredFieldMutation("linked_at_ms", "'41x'", stored.owner.linkedAtMs),
			)
			for (mutation in ownerMutations) {
				database.withTransaction {
					sqlite.execSQL(
						"UPDATE steps_count_domain_owner_revision SET " +
							"${mutation.column} = ${mutation.corruptSql} " +
							"WHERE owner_effect_checksum = ? AND linked_at_ms = ?",
						arrayOf(stored.owner.ownerEffectChecksum, stored.owner.linkedAtMs),
					)
					store.readOwners(listOf(key)) shouldBe StepsCountDomainOwnerRead.Unverifiable
					sqlite.execSQL(
						"UPDATE steps_count_domain_owner_revision SET ${mutation.column} = ? " +
							"WHERE owner_effect_checksum IS ? OR receipt_identity IS ?",
						arrayOf(
							mutation.originalValue,
							stored.owner.ownerEffectChecksum,
							stored.owner.receiptIdentity,
						),
					)
				}
			}

			val receiptMutations = listOf(
				StoredFieldMutation("receipt_identity", "X'01'", receipt.receiptIdentity),
				StoredFieldMutation("domain_identity", "X'01'", receipt.domainIdentity),
				StoredFieldMutation("owner_kind", "1", receipt.ownerKind),
				StoredFieldMutation("scope_identity", "1.5", receipt.scopeIdentity),
				StoredFieldMutation("owner_identity", "NULL", receipt.ownerIdentity),
				StoredFieldMutation("owner_revision", "'1x'", receipt.ownerRevision),
				StoredFieldMutation(
					"registration_generation",
					"X'01'",
					receipt.registrationGeneration,
				),
				StoredFieldMutation("collected_data_epoch", "1.5", receipt.collectedDataEpoch),
				StoredFieldMutation("authority_revision", "'1x'", receipt.authorityRevision),
				StoredFieldMutation(
					"authority_fingerprint",
					"NULL",
					receipt.authorityFingerprint,
				),
				StoredFieldMutation("coverage_kind", "1", receipt.coverageKind),
				StoredFieldMutation("coverage_version", "4294967297", receipt.coverageVersion),
				StoredFieldMutation(
					"count_domain_version",
					"4294967297",
					receipt.countDomainVersion,
				),
				StoredFieldMutation("effect_checksum", "X'01'", receipt.effectChecksum),
				StoredFieldMutation("completion_evidence_checksum", "1", null),
			)
			for (mutation in receiptMutations) {
				database.withTransaction {
					sqlite.execSQL(
						"UPDATE steps_count_domain_receipt SET " +
							"${mutation.column} = ${mutation.corruptSql} " +
							"WHERE effect_checksum = ? OR receipt_identity IS ?",
						arrayOf(receipt.effectChecksum, receipt.receiptIdentity),
					)
					store.readOwners(listOf(key)) shouldBe StepsCountDomainOwnerRead.Unverifiable
					if (mutation.column in setOf("coverage_version", "count_domain_version")) {
						store.recordSessionWal(wal, token('a')) shouldBe
							StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
					}
					sqlite.execSQL(
						"UPDATE steps_count_domain_receipt SET ${mutation.column} = ? " +
							"WHERE effect_checksum IS ? OR owner_identity = ?",
						arrayOf(
							mutation.originalValue,
							receipt.effectChecksum,
							receipt.ownerIdentity,
						),
					)
				}
			}

			database.withTransaction {
				sqlite.execSQL(
					"UPDATE steps_count_domain_receipt SET authority_revision = 9223372036854775808 " +
						"WHERE receipt_identity = ?",
					arrayOf(receipt.receiptIdentity),
				)
				store.readOwners(listOf(key)) shouldBe StepsCountDomainOwnerRead.Unverifiable
				sqlite.execSQL(
					"UPDATE steps_count_domain_receipt SET authority_revision = ? " +
						"WHERE owner_identity = ?",
					arrayOf(receipt.authorityRevision, receipt.ownerIdentity),
				)
			}
		}

	@Test
	fun `count-domain completeness marker reads authenticate required and nullable shapes`() =
		runTest {
			installSchema()
			val sqlite = database.openHelper.writableDatabase
			val ownerIdentity = "sha256:${"1".repeat(64)}"
			val scopeIdentity = "sha256:${"2".repeat(64)}"
			val effectChecksum = "3".repeat(64)
			val timelineChecksum = "4".repeat(64)
			val evidenceChecksum = StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				ownerIdentity = ownerIdentity,
				ownerRevision = 1L,
				terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN,
				lastAdmissionOrdinal = null,
				lastSourceSequence = null,
				providerFlushOutcome = "NOT_REQUESTED",
				registrationRemovalOutcome = "REMOVED",
				registrationTimelineChecksum = timelineChecksum,
			)
			sqlite.execSQL(
				"INSERT INTO steps_count_domain_owner_revision VALUES (?, ?, ?, 1, " +
					"'UNPROVEN', NULL, ?, 1)",
				arrayOf(
					StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
					scopeIdentity,
					ownerIdentity,
					effectChecksum,
				),
			)
			sqlite.execSQL(
				"INSERT INTO steps_count_domain_completeness_marker VALUES " +
					"(?, ?, 1, 'UNPROVEN', NULL, NULL, 'NOT_REQUESTED', 'REMOVED', ?, ?)",
				arrayOf(
					StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
					ownerIdentity,
					timelineChecksum,
					evidenceChecksum,
				),
			)
			val key = StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				ownerIdentity,
				1L,
			)
			val mutations = listOf(
				StoredFieldMutation("owner_kind", "1", "SESSION_COMPLETENESS"),
				StoredFieldMutation("owner_identity", "X'01'", ownerIdentity),
				StoredFieldMutation("owner_revision", "'1x'", 1L),
				StoredFieldMutation("terminal_state", "NULL", "UNPROVEN"),
				StoredFieldMutation("last_admission_ordinal", "'1x'", null),
				StoredFieldMutation("last_source_sequence", "1.5", null),
				StoredFieldMutation("provider_flush_outcome", "X'01'", "NOT_REQUESTED"),
				StoredFieldMutation("registration_removal_outcome", "1", "REMOVED"),
				StoredFieldMutation("registration_timeline_checksum", "NULL", timelineChecksum),
				StoredFieldMutation("evidence_checksum", "1.5", evidenceChecksum),
			)
			for (mutation in mutations) {
				database.withTransaction {
					sqlite.execSQL(
						"UPDATE steps_count_domain_completeness_marker SET " +
							"${mutation.column} = ${mutation.corruptSql} " +
							"WHERE evidence_checksum IS ? OR registration_timeline_checksum IS ?",
						arrayOf(evidenceChecksum, timelineChecksum),
					)
					StepsCountDomainStore(database).readOwners(listOf(key)) shouldBe
						StepsCountDomainOwnerRead.Unverifiable
					sqlite.execSQL(
						"UPDATE steps_count_domain_completeness_marker SET ${mutation.column} = ? " +
							"WHERE evidence_checksum IS ? OR registration_timeline_checksum IS ? " +
							"OR owner_revision IS ?",
						arrayOf(
							mutation.originalValue,
							evidenceChecksum,
							timelineChecksum,
							1L,
						),
					)
				}
			}
		}

	@Test
	fun `full clear deletes owners before receipts or preserves only terminal evidence`() = runTest {
		installSchema()
		val store = StepsCountDomainStore(database)
		val bound = insertWal("bound", 1L, payloadVersion = 7)
		store.recordSessionWal(bound, token('a')) shouldBe StepsCountDomainWriteResult.INSERTED
		val unproven = insertWal("unproven", 2L)
		store.recordSessionWal(unproven, null) shouldBe StepsCountDomainWriteResult.INSERTED

		val sqlite = database.openHelper.writableDatabase
		shouldThrow<Exception> {
			sqlite.execSQL("DELETE FROM steps_count_domain_receipt")
		}
		shouldThrow<IllegalStateException> {
			clearStepsCountDomainEvidenceInCurrentTransaction(
				database,
				StepsCountDomainFullClearMode.PRESERVE_TERMINAL,
			)
		}
		database.withTransaction {
			clearStepsCountDomainEvidenceInCurrentTransaction(
				database,
				StepsCountDomainFullClearMode.PRESERVE_TERMINAL,
			)
		} shouldBe StepsCountDomainMaintenanceResult.Applied(1, 1)
		sqlite.count("SELECT COUNT(*) FROM steps_count_domain_receipt") shouldBe 0L
		sqlite.count(
			"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
				"WHERE operation = 'UNPROVEN'",
		) shouldBe 1L

		database.withTransaction {
			clearStepsCountDomainEvidenceInCurrentTransaction(
				database,
				StepsCountDomainFullClearMode.REMOVE_ALL,
			)
		} shouldBe StepsCountDomainMaintenanceResult.Applied(1, 0)
		sqlite.count("SELECT COUNT(*) FROM steps_count_domain_owner_revision") shouldBe 0L
		sqlite.count("SELECT COUNT(*) FROM steps_count_domain_schema_marker") shouldBe 1L
	}

	@Test
	fun `multi-generation completeness markers authenticate their ordered registration prefixes`() =
		runTest {
			installSchema()
			val store = StepsCountDomainStore(database)
			val retirement = StepsCountDomainRetirementEvidence(
				providerFlushOutcome = "COMPLETE",
				registrationRemovalOutcome = "REMOVED",
			)
			val firstWal = insertWal(
				eventId = "prefix-generation-one",
				sourceSequence = 4L,
				payloadVersion = 7,
				sourceInstanceId = "steps-generation-one",
				registrationGeneration = 1L,
			)
			store.recordSessionWal(firstWal, token('a')) shouldBe
				StepsCountDomainWriteResult.INSERTED
			val first = SourceSessionCompletenessEntity(
				logicalTrackingId = "tracking",
				serviceRunId = "run",
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				sourceInstanceId = firstWal.sourceInstanceId,
				registrationGeneration = firstWal.registrationGeneration,
				lastAdmissionOrdinal = firstWal.admissionOrdinal,
				lastSourceSequence = firstWal.sourceSequence,
				appDrainComplete = true,
				providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = 100L,
			)
			database.sourceSessionDao().saveCompleteness(first)
			store.recordSessionCompleteness(first, retirement) shouldBe
				StepsCountDomainWriteResult.INSERTED

			val secondWal = insertWal(
				eventId = "prefix-generation-two",
				sourceSequence = 3L,
				payloadVersion = 7,
				sourceInstanceId = "steps-generation-two",
				registrationGeneration = 2L,
			)
			store.recordSessionWal(secondWal, token('a')) shouldBe
				StepsCountDomainWriteResult.INSERTED
			val second = first.copy(
				sourceInstanceId = secondWal.sourceInstanceId,
				registrationGeneration = secondWal.registrationGeneration,
				lastAdmissionOrdinal = secondWal.admissionOrdinal,
				lastSourceSequence = secondWal.sourceSequence,
				updatedAtMs = 200L,
			)
			database.sourceSessionDao().saveCompleteness(second)
			store.recordSessionCompleteness(second, retirement) shouldBe
				StepsCountDomainWriteResult.INSERTED

			val timeline = listOf(first, second)
			store.authenticateTerminalProductDisposition(
				"tracking",
				"run",
				timeline,
			) shouldBe StepsTerminalProductAuthentication.Materializable
			val keys = timeline.map { row ->
				StepsCountDomainOwnerLookupKey(
					StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
					StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
						row.logicalTrackingId,
						row.serviceRunId,
						row.sourceInstanceId,
						row.registrationGeneration,
					),
					StepsCountDomainReceiptIntegrity.completenessOwnerRevision(row),
				)
			}
			val stored = (store.readOwners(keys) as StepsCountDomainOwnerRead.Ready).owners
			stored.getValue(keys[0]).completenessMarker?.registrationTimelineChecksum shouldBe
				StepsCountDomainReceiptIntegrity.registrationTimelineChecksum(listOf(first))
			stored.getValue(keys[1]).completenessMarker?.registrationTimelineChecksum shouldBe
				StepsCountDomainReceiptIntegrity.registrationTimelineChecksum(timeline)
		}

	@Test
	fun `terminal compaction is bounded and removes oldest opaque owners`() = runTest {
		installSchema()
		val store = StepsCountDomainStore(database)
		repeat(3) { index ->
			store.recordSessionWal(insertWal("terminal-$index", index.toLong() + 1L), null) shouldBe
				StepsCountDomainWriteResult.INSERTED
		}

		store.compactTerminalOwners(
			maximumRetainedTerminalOwners = 1,
			batchSize = 2,
			sourceFenceAuthenticated = true,
		) shouldBe StepsCountDomainMaintenanceResult.Applied(2, 0)
		database.openHelper.writableDatabase.count(
			"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
				"WHERE operation = 'UNPROVEN'",
		) shouldBe 1L
	}

	@Test
	fun `bounded WAL retention removes exact owners and orphan receipts before payload rows`() =
		runTest {
			installSchema()
			val store = StepsCountDomainStore(database)
			val first = insertWal("wal-retention-bound", 1L, payloadVersion = 7)
			val second = insertWal("wal-retention-unproven", 2L)
			store.recordSessionWal(first, token('a')) shouldBe
				StepsCountDomainWriteResult.INSERTED
			store.recordSessionWal(second, null) shouldBe
				StepsCountDomainWriteResult.INSERTED

			store.removeSessionWalOwnersForPrune(
				safeOrdinal = second.admissionOrdinal,
				createdBeforeMs = 3L,
				limit = 2,
			) shouldBe StepsCountDomainMaintenanceResult.Applied(2, 1)
			database.openHelper.writableDatabase.count(
				"SELECT COUNT(*) FROM steps_count_domain_owner_revision",
			) shouldBe 0L
			database.openHelper.writableDatabase.count(
				"SELECT COUNT(*) FROM steps_count_domain_receipt",
			) shouldBe 0L
		}

	@Test
	fun `WAL retention preserves the final Steps admission for a nonterminal registration`() =
		runTest {
			installSchema()
			val store = StepsCountDomainStore(database)
			val first = insertWal(
				"wal-active-first",
				1L,
				payloadVersion = 7,
				sourceInstanceId = "active-steps",
				registrationGeneration = 7L,
			)
			val final = insertWal(
				"wal-active-final",
				2L,
				payloadVersion = 7,
				sourceInstanceId = "active-steps",
				registrationGeneration = 7L,
			)
			store.recordSessionWal(first, token('a')) shouldBe
				StepsCountDomainWriteResult.INSERTED
			store.recordSessionWal(final, token('a')) shouldBe
				StepsCountDomainWriteResult.INSERTED
			database.sourceBrokerDao().insertRegistration(
				ProviderRegistrationGenerationEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					registrationGeneration = 7L,
					sourceInstanceId = "active-steps",
					ownerScope = "source-broker:${SourceDestinationOwnerEntity.SOURCE_STEPS}",
					clockDomainId = "boot",
					physicalConfigurationFingerprint = "steps",
					collectedDataEpoch = 7L,
					providerResidency =
						ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
					providerProcessIncarnationId = "process",
					status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
					reservedAtMs = 1L,
					reservedElapsedRealtimeNanos = 1L,
					acceptedAtMs = 1L,
					acceptedElapsedRealtimeNanos = 1L,
					retiredAtMs = null,
					retiredElapsedRealtimeNanos = null,
					failureCode = null,
				),
			)

			store.removeSessionWalOwnersForPrune(
				safeOrdinal = final.admissionOrdinal,
				createdBeforeMs = 3L,
				limit = 2,
			) shouldBe StepsCountDomainMaintenanceResult.Applied(1, 1)
			database.sourceEventWalDao().deleteProjectedSourceBatch(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				safeOrdinal = final.admissionOrdinal,
				createdBeforeMs = 3L,
				limit = 2,
			) shouldBe 1

			database.sourceEventWalDao().getByAdmissionOrdinal(first.admissionOrdinal) shouldBe null
			requireNotNull(
				database.sourceEventWalDao().getByAdmissionOrdinal(final.admissionOrdinal),
			).eventId shouldBe final.eventId
			val finalKey = StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
					final.admissionOrdinal,
					final.eventId,
				),
				1L,
			)
			(store.readOwners(listOf(finalKey)) as StepsCountDomainOwnerRead.Ready)
				.owners.containsKey(finalKey) shouldBe true
		}

	@Test
	fun `fact retention preserves its WAL owner until atomic WAL pruning`() = runTest {
		installSchema()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7L))
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = "run",
				logicalTrackingId = "tracking",
				state = "FINALIZED",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 1L,
				startedElapsedNanos = 1L,
				completedAtMs = 2L,
				completionReason = "TEST",
				bootId = "boot",
			),
		)
		val store = StepsCountDomainStore(database)
		val wal = insertWal("retained-fact-wal", 1L, payloadVersion = 7)
		store.recordSessionWal(wal, token('a')) shouldBe StepsCountDomainWriteResult.INSERTED
		val unsignedFact = StepFactRevisionEntity(
			logicalFactId = "${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:${wal.eventId}",
			semanticRevision = 1L,
			mutationId =
				"${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:${wal.eventId}:1:UPSERT",
			stepIntervalId = null,
			sourceEventId = wal.eventId,
			sourceAdmissionOrdinal = wal.admissionOrdinal,
			originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
			originIdentity = wal.eventId,
			writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = 1L,
			intervalEndTimeMs = 2L,
			intervalStartElapsedRealtimeNanos = 1_000_000L,
			intervalEndElapsedRealtimeNanos = 2_000_000L,
			clockDomainId = "boot",
			bootClockDomainId = "boot",
			cumulativeStepCountStart = 10L,
			cumulativeStepCountEnd = 12L,
			wallTimeUncertaintyMs = 0L,
			coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
			effectiveStepCount = 2L,
			logicalTrackingId = "tracking",
			serviceRunId = "run",
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = 1L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			collectedDataEpoch = 7L,
			scopeDeletionGeneration = 0L,
			effectChecksum = "unsigned",
			appliedAtMs = 2L,
		)
		val fact = unsignedFact.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsignedFact),
		)
		database.stepFactRevisionDao().insert(fact)
		store.recordSessionFact(fact) shouldBe StepsCountDomainWriteResult.INSERTED
		val walKey = StepsCountDomainOwnerLookupKey(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
				wal.admissionOrdinal,
				wal.eventId,
			),
			1L,
		)
		val factKey = StepsCountDomainOwnerLookupKey(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.logicalFactId,
			),
			fact.semanticRevision,
		)

		database.sourceEvidenceStateDao().updateLifecycle(7L, 3L, 3L)
		database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(3L, 7L, 3L) shouldBe 1
		(store.readOwners(listOf(walKey, factKey)) as StepsCountDomainOwnerRead.Ready)
			.owners.keys shouldBe setOf(walKey)
		requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(wal.admissionOrdinal))

		database.withTransaction {
			store.removeSessionWalOwnersForPrune(
				safeOrdinal = wal.admissionOrdinal,
				createdBeforeMs = 2L,
				limit = 1,
			) shouldBe StepsCountDomainMaintenanceResult.Applied(1, 1)
			database.sourceEventWalDao().deleteProjectedSourceBatch(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				safeOrdinal = wal.admissionOrdinal,
				createdBeforeMs = 2L,
				limit = 1,
			) shouldBe 1
		}

		(store.readOwners(listOf(walKey)) as StepsCountDomainOwnerRead.Ready)
			.owners shouldBe emptyMap()
		database.sourceEventWalDao().getByAdmissionOrdinal(wal.admissionOrdinal) shouldBe null
	}

	@Test
	fun `unrelated malformed owner key does not force a full-table hot-path scan`() = runTest {
		installSchema()
		val store = StepsCountDomainStore(database)
		val wal = insertWal("scoped-owner-key", 1L, payloadVersion = 7)
		store.recordSessionWal(wal, token('a')) shouldBe StepsCountDomainWriteResult.INSERTED
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"INSERT INTO steps_count_domain_owner_revision VALUES " +
				"('SESSION_WAL', ?, X'01', 1, 'UNPROVEN', NULL, ?, 1)",
			arrayOf("sha256:${"1".repeat(64)}", "2".repeat(64)),
		)

		store.recordSessionWal(wal, token('a')) shouldBe StepsCountDomainWriteResult.EXACT_REPLAY
	}

	@Test
	fun `scoped malformed owner key cannot hide behind typed equality`() = runTest {
		installSchema()
		val store = StepsCountDomainStore(database)
		val wal = insertWal("malformed-owner-key", 1L, payloadVersion = 7)
		store.recordSessionWal(wal, token('a')) shouldBe StepsCountDomainWriteResult.INSERTED
		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
			wal.admissionOrdinal,
			wal.eventId,
		)
		val scopeIdentity =
			StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity("tracking", "run")
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO steps_count_domain_owner_revision VALUES " +
				"('SESSION_WAL', ?, CAST(? AS BLOB), 2, 'UNPROVEN', NULL, ?, 2)",
			arrayOf(scopeIdentity, ownerIdentity, "2".repeat(64)),
		)

		store.recordSessionWal(wal, token('a')) shouldBe
			StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
	}

	@Test
	fun `exact owner lookup remains bounded by scope with long unrelated history`() = runTest {
		installSchema()
		val store = StepsCountDomainStore(database)
		val wal = insertWal("bounded-owner-history", 1L, payloadVersion = 7)
		store.recordSessionWal(wal, token('a')) shouldBe StepsCountDomainWriteResult.INSERTED
		val sqlite = database.openHelper.writableDatabase
		repeat(512) { index ->
			val identity = "sha256:${index.toString(16).padStart(64, '0')}"
			sqlite.execSQL(
				"INSERT INTO steps_count_domain_owner_revision VALUES " +
					"('AMBIENT_FACT', ?, ?, 1, 'UNPROVEN', NULL, ?, ?)",
				arrayOf(identity, identity, "3".repeat(64), index.toLong()),
			)
		}
		val key = StepsCountDomainOwnerLookupKey(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
				wal.admissionOrdinal,
				wal.eventId,
			),
			1L,
		)

		(store.readOwners(listOf(key)) as StepsCountDomainOwnerRead.Ready)
			.owners.containsKey(key) shouldBe true
	}

	@Test
	fun `maintenance authenticates a candidate lineage through exact multi-page continuation`() =
		runTest {
			installSchema()
			val key = insertAmbientUnprovenOwnerHistory('a', 130)
			var authenticatedPages = 0

			StepsCountDomainStore(database).removeOwners(listOf(key)) { checkpoint ->
				if (checkpoint ==
					StepsCountDomainMaintenanceCheckpoint.OWNER_DOMAIN_PAGE_AUTHENTICATED
				) {
					authenticatedPages += 1
				}
			} shouldBe StepsCountDomainMaintenanceResult.Applied(1, 0)

			authenticatedPages shouldBe 3
			database.openHelper.writableDatabase.count(
				"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
					"WHERE owner_identity = ?",
				arrayOf(key.ownerIdentity),
			) shouldBe 129L
		}

	@Test
	fun `maintenance cancellation between owner pages preserves the exact candidate`() = runTest {
		installSchema()
		val key = insertAmbientUnprovenOwnerHistory('b', 130)
		var authenticatedPages = 0

		shouldThrow<CancellationException> {
			StepsCountDomainStore(database).removeOwners(listOf(key)) { checkpoint ->
				if (checkpoint ==
					StepsCountDomainMaintenanceCheckpoint.OWNER_DOMAIN_PAGE_AUTHENTICATED &&
					++authenticatedPages == 2
				) {
					throw CancellationException("stop maintenance")
				}
			}
		}

		database.openHelper.writableDatabase.count(
			"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
				"WHERE owner_identity = ? AND owner_revision = ?",
			arrayOf(key.ownerIdentity, key.ownerRevision),
		) shouldBe 1L
	}

	@Test
	fun `corruption introduced between domain pages invalidates the maintenance snapshot`() =
		runTest {
			installSchema()
			val key = insertAmbientUnprovenOwnerHistory('c', 130)
			var authenticatedPages = 0

			StepsCountDomainStore(database).removeOwners(listOf(key)) { checkpoint ->
				if (checkpoint ==
					StepsCountDomainMaintenanceCheckpoint.OWNER_DOMAIN_PAGE_AUTHENTICATED &&
					++authenticatedPages == 1
				) {
					database.openHelper.writableDatabase.execSQL(
						"UPDATE steps_count_domain_owner_revision SET operation = 1 " +
							"WHERE owner_identity = ? AND owner_revision = 100",
						arrayOf(key.ownerIdentity),
					)
				}
			} shouldBe
				StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
			database.openHelper.writableDatabase.count(
				"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
					"WHERE owner_identity = ? AND owner_revision = ?",
				arrayOf(key.ownerIdentity, key.ownerRevision),
			) shouldBe 1L
			database.openHelper.writableDatabase.count(
				"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
					"WHERE owner_identity = ? AND owner_revision = 100 AND operation = 'UNPROVEN'",
				arrayOf(key.ownerIdentity),
			) shouldBe 1L
		}

	@Test
	fun `unowned mutation after one removal invalidates the snapshot before the next deletion`() =
		runTest {
			installSchema()
			val first = insertAmbientUnprovenOwnerHistory('4', 1)
			val second = insertAmbientUnprovenOwnerHistory('5', 1)
			val sqlite = database.openHelper.writableDatabase

			shouldThrow<IllegalStateException> {
				StepsCountDomainStore(database).withOwnerMaintenance { maintenance ->
					maintenance.removeOwners(listOf(first)) shouldBe
						StepsCountDomainMaintenanceResult.Applied(1, 0)
					sqlite.execSQL(
						"UPDATE steps_count_domain_owner_revision SET linked_at_ms = 2 " +
							"WHERE owner_identity = ? AND owner_revision = ?",
						arrayOf(second.ownerIdentity, second.ownerRevision),
					)
					maintenance.removeOwners(listOf(second))
				}
			}

			sqlite.count(
				"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
					"WHERE owner_identity IN (?, ?)",
				arrayOf(first.ownerIdentity, second.ownerIdentity),
			) shouldBe 2L
			sqlite.count(
				"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
					"WHERE owner_identity = ? AND linked_at_ms = 1",
				arrayOf(second.ownerIdentity),
			) shouldBe 1L
		}

	@Test
	fun `maintenance authenticates candidate completeness marker before owner deletion`() =
		runTest {
			installSchema()
			val ownerIdentity = "sha256:${"5".repeat(64)}"
			val scopeIdentity = "sha256:${"6".repeat(64)}"
			val timelineChecksum = "7".repeat(64)
			val evidenceChecksum = StepsCountDomainReceiptIntegrity.completenessMarkerChecksum(
				ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				ownerIdentity = ownerIdentity,
				ownerRevision = 1L,
				terminalState = StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN,
				lastAdmissionOrdinal = null,
				lastSourceSequence = null,
				providerFlushOutcome = "NOT_REQUESTED",
				registrationRemovalOutcome = "REMOVED",
				registrationTimelineChecksum = timelineChecksum,
			)
			val sqlite = database.openHelper.writableDatabase
			sqlite.execSQL(
				"INSERT INTO steps_count_domain_owner_revision VALUES " +
					"('SESSION_COMPLETENESS', ?, ?, 1, 'UNPROVEN', NULL, ?, 1)",
				arrayOf(scopeIdentity, ownerIdentity, "8".repeat(64)),
			)
			sqlite.execSQL(
				"INSERT INTO steps_count_domain_completeness_marker VALUES " +
					"('SESSION_COMPLETENESS', ?, 1, 'UNPROVEN', NULL, NULL, " +
					"'NOT_REQUESTED', 'REMOVED', ?, ?)",
				arrayOf(ownerIdentity, timelineChecksum, evidenceChecksum),
			)
			sqlite.execSQL(
				"UPDATE steps_count_domain_completeness_marker " +
					"SET provider_flush_outcome = 1 WHERE owner_identity = ?",
				arrayOf(ownerIdentity),
			)
			val key = StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				ownerIdentity,
				1L,
			)

			StepsCountDomainStore(database).removeOwners(listOf(key)) shouldBe
				StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
			sqlite.count(
				"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
					"WHERE owner_identity = ?",
				arrayOf(ownerIdentity),
			) shouldBe 1L
		}

	@Test
	fun `maintenance full-domain audit rejects an unrelated malformed owner`() = runTest {
		installSchema()
		val key = insertAmbientUnprovenOwnerHistory('9', 1)
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO steps_count_domain_owner_revision VALUES " +
				"('AMBIENT_FACT', ?, X'01', 1, 'UNPROVEN', NULL, ?, 1)",
			arrayOf("sha256:${"a".repeat(64)}", "b".repeat(64)),
		)

		StepsCountDomainStore(database).removeOwners(listOf(key)) shouldBe
			StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	}

	@Test
	fun `one maintenance snapshot audits once across multiple candidate batches`() = runTest {
		val queries = mutableListOf<String>()
		database.close()
		database = Room.inMemoryDatabaseBuilder(
			ApplicationProvider.getApplicationContext<Application>(),
			AppDatabase::class.java,
		).allowMainThreadQueries()
			.setQueryCallback(
				{ sql, _ -> queries += sql.replace(Regex("\\s+"), " ").trim().lowercase() },
				Executor(Runnable::run),
			)
			.build()
		installSchema()
		insertAmbientUnprovenOwnerHistory('d', 512)
		val candidates = listOf('1', '2', '3', '4').map { digit ->
			insertAmbientUnprovenOwnerHistory(digit, 1)
		}
		queries.clear()

		StepsCountDomainStore(database).withOwnerMaintenance { maintenance ->
			candidates.forEach { key ->
				maintenance.removeOwners(listOf(key)) shouldBe
					StepsCountDomainMaintenanceResult.Applied(1, 0)
			}
		}

		queries.count { query ->
			query.startsWith(
				"select rowid as maintenance_rowid, typeof(owner_kind), " +
					"typeof(owner_identity), typeof(owner_revision)",
			)
		} shouldBe 9
		queries.count { query ->
			query.startsWith("select * from main.steps_count_domain_owner_revision") &&
				query.contains("order by owner_kind, owner_identity, owner_revision limit ?")
		} shouldBe 9
		queries.count { query ->
			query.startsWith(
				"select * from main.steps_count_domain_owner_revision where " +
					"(owner_kind = ? and owner_identity = ? and owner_revision = ?)",
			)
		} shouldBe candidates.size
		queries.any { query ->
			query.contains("cast(owner_kind as text)") ||
				query.contains("cast(owner_identity as text)")
		} shouldBe false
	}

	@Test
	fun `maintenance snapshot cannot be reused after its owner loop returns`() = runTest {
		installSchema()
		val key = insertAmbientUnprovenOwnerHistory('e', 1)
		var escaped: StepsCountDomainStore.OwnerMaintenanceSession? = null
		val store = StepsCountDomainStore(database)

		store.withOwnerMaintenance { maintenance ->
			escaped = maintenance
		}

		shouldThrow<IllegalStateException> {
			requireNotNull(escaped).removeOwners(listOf(key))
		}
		database.openHelper.writableDatabase.count(
			"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
				"WHERE owner_identity = ? AND owner_revision = ?",
			arrayOf(key.ownerIdentity, key.ownerRevision),
		) shouldBe 1L
	}

	@Test
	fun `WAL maintenance audits once and keyset-pages multiple candidate batches`() = runTest {
		val queries = mutableListOf<String>()
		database.close()
		database = Room.inMemoryDatabaseBuilder(
			ApplicationProvider.getApplicationContext<Application>(),
			AppDatabase::class.java,
		).allowMainThreadQueries()
			.setQueryCallback(
				{ sql, _ -> queries += sql.replace(Regex("\\s+"), " ").trim().lowercase() },
				Executor(Runnable::run),
			)
			.build()
		installSchema()
		val store = StepsCountDomainStore(database)
		repeat(257) { index ->
			val wal = insertWal("wal-page-$index", index.toLong() + 1L, payloadVersion = 7)
			store.recordSessionWal(wal, token('a')) shouldBe StepsCountDomainWriteResult.INSERTED
		}
		queries.clear()
		val callbacks = mutableMapOf<StepsCountDomainMaintenanceCheckpoint, Int>()

		store.removeSessionWalOwnersForPrune(
			safeOrdinal = 257L,
			createdBeforeMs = 258L,
			limit = 257,
		) { checkpoint ->
			callbacks[checkpoint] = callbacks.getOrDefault(checkpoint, 0) + 1
		} shouldBe StepsCountDomainMaintenanceResult.Applied(257, 257)

		callbacks[StepsCountDomainMaintenanceCheckpoint.OWNER_DOMAIN_PAGE_AUTHENTICATED] shouldBe 5
		callbacks[StepsCountDomainMaintenanceCheckpoint.WAL_DOMAIN_PAGE_AUTHENTICATED] shouldBe 3
		callbacks[StepsCountDomainMaintenanceCheckpoint.WAL_CANDIDATE_PAGE_AUTHENTICATED] shouldBe 3
		queries.count { query ->
			query.startsWith(
				"select rowid as maintenance_rowid, admission_ordinal, event_id, " +
					"created_at_ms, source_kind, " +
					"source_instance_id, registration_generation",
			)
		} shouldBe 3
		queries.none { query -> query.contains("cast(source_kind as integer)") } shouldBe true
	}

	@Test
	fun `WAL maintenance cancellation between audit pages preserves every WAL row`() = runTest {
		installSchema()
		repeat(130) { index ->
			insertWal("wal-cancel-$index", index.toLong() + 1L, payloadVersion = 7)
		}
		var walPages = 0

		shouldThrow<CancellationException> {
			StepsCountDomainStore(database).removeSessionWalOwnersForPrune(
				safeOrdinal = 130L,
				createdBeforeMs = 131L,
				limit = 130,
			) { checkpoint ->
				if (checkpoint ==
					StepsCountDomainMaintenanceCheckpoint.WAL_DOMAIN_PAGE_AUTHENTICATED &&
					++walPages == 2
				) {
					throw CancellationException("stop WAL audit")
				}
			}
		}

		database.sourceEventWalDao().countAll() shouldBe 130L
	}

	@Test
	fun `WAL corruption introduced between audit pages invalidates the snapshot`() = runTest {
		installSchema()
		repeat(130) { index ->
			insertWal("wal-corrupt-$index", index.toLong() + 1L, payloadVersion = 7)
		}
		var walPages = 0

		StepsCountDomainStore(database).removeSessionWalOwnersForPrune(
			safeOrdinal = 130L,
			createdBeforeMs = 131L,
			limit = 130,
		) { checkpoint ->
			if (checkpoint ==
				StepsCountDomainMaintenanceCheckpoint.WAL_DOMAIN_PAGE_AUTHENTICATED &&
				++walPages == 1
			) {
				database.openHelper.writableDatabase.execSQL(
					"UPDATE source_event_wal SET event_id = '' WHERE admission_ordinal = 100",
				)
			}
		} shouldBe StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable

		requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(100L)).eventId shouldBe
			"wal-corrupt-99"
	}

	@Test
	fun `invalid maintenance cursor row is unverifiable instead of throwing`() = runTest {
		installSchema()
		val wal = insertWal("maintenance-invalid-range", 1L, payloadVersion = 7)
		StepsCountDomainStore(database).recordSessionWal(wal, token('a')) shouldBe
			StepsCountDomainWriteResult.INSERTED
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET event_id = '' WHERE admission_ordinal = ?",
			arrayOf(wal.admissionOrdinal),
		)

		StepsCountDomainStore(database).removeSessionWalOwnersForPrune(
			safeOrdinal = wal.admissionOrdinal,
			createdBeforeMs = 2L,
			limit = 1,
		) shouldBe StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	}

	@Test
	fun `maintenance authenticates owner predicates and WAL protection evidence before deletion`() =
		runTest {
			installSchema()
			val store = StepsCountDomainStore(database)
			val wal = insertWal("maintenance-predicate-corruption", 1L, payloadVersion = 7)
			store.recordSessionWal(wal, token('a')) shouldBe
				StepsCountDomainWriteResult.INSERTED
			val sqlite = database.openHelper.writableDatabase

			val mutations = listOf(
				"UPDATE steps_count_domain_owner_revision SET operation = 1" to
					"UPDATE steps_count_domain_owner_revision SET operation = 'BIND'",
				"UPDATE steps_count_domain_owner_revision SET linked_at_ms = 'bad'" to
					"UPDATE steps_count_domain_owner_revision SET linked_at_ms = 1",
				"UPDATE steps_count_domain_receipt SET coverage_version = 4294967297" to
					"UPDATE steps_count_domain_receipt SET coverage_version = 7",
				"UPDATE source_event_wal SET created_at_ms = 'bad'" to
					"UPDATE source_event_wal SET created_at_ms = 1",
				"UPDATE source_event_wal SET source_kind = '3x'" to
					"UPDATE source_event_wal SET source_kind = 3",
				"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = X'10'" to
					"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = 4",
			)
			for ((mutation, restore) in mutations) {
				database.withTransaction {
					sqlite.execSQL(mutation)
					store.removeSessionWalOwnersForPrune(
						safeOrdinal = wal.admissionOrdinal,
						createdBeforeMs = 2L,
						limit = 1,
					) shouldBe StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
					sqlite.execSQL(restore)
				}
			}
			database.sourceBrokerDao().insertRegistration(
				ProviderRegistrationGenerationEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					registrationGeneration = wal.registrationGeneration,
					sourceInstanceId = wal.sourceInstanceId,
					ownerScope = "source-broker:${SourceDestinationOwnerEntity.SOURCE_STEPS}",
					clockDomainId = "boot",
					physicalConfigurationFingerprint = "steps",
					collectedDataEpoch = 7L,
					providerResidency =
						ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
					providerProcessIncarnationId = "process",
					status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
					reservedAtMs = 1L,
					reservedElapsedRealtimeNanos = 1L,
					acceptedAtMs = 1L,
					acceptedElapsedRealtimeNanos = 1L,
					retiredAtMs = null,
					retiredElapsedRealtimeNanos = null,
					failureCode = null,
				),
			)
			sqlite.execSQL(
				"UPDATE provider_registration_generation SET status = 1 " +
					"WHERE source_kind = 3 AND registration_generation = 1",
			)
			store.removeSessionWalOwnersForPrune(
				safeOrdinal = wal.admissionOrdinal,
				createdBeforeMs = 2L,
				limit = 1,
			) shouldBe StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
			sqlite.count("SELECT COUNT(*) FROM steps_count_domain_owner_revision") shouldBe 1L
		}

	@Test
	fun `schema sentinel rejects real and blob storage classes without coercion`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"UPDATE steps_count_domain_schema_marker SET contract_version = 1.5 WHERE id = 1",
		)
		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible

		sqlite.execSQL(
			"UPDATE steps_count_domain_schema_marker SET contract_version = 2, " +
				"token_semantics = X'01' WHERE id = 1",
		)
		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
	}

	@Test
	fun `Steps evidence publication uses monotonic wall clock time`() = runTest {
		val dao = database.sourceEvidenceStateDao()
		dao.ensure(SourceEvidenceState())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_evidence_state SET revision = 0, updated_at_ms = 10000 WHERE id = 1",
		)

		database.withTransaction {
			publishStepsCountDomainEvidenceRevisionAtWallTime(database, wallTimeMs = 2_000L)
		}
		requireNotNull(dao.get()).let { state ->
			state.revision shouldBe 1L
			state.updatedAtMs shouldBe 10_000L
		}

		database.withTransaction {
			publishStepsCountDomainEvidenceRevisionAtWallTime(database, wallTimeMs = 12_000L)
		}
		requireNotNull(dao.get()).let { state ->
			state.revision shouldBe 2L
			state.updatedAtMs shouldBe 12_000L
		}
	}

	@Test
	fun `corrupt schema sentinel is incompatible and cannot be repaired or activate writers`() =
		runTest {
			installSchema()
			database.openHelper.writableDatabase.execSQL(
				"UPDATE steps_count_domain_schema_marker SET contract_version = 1 WHERE id = 1",
			)
			StepsCountDomainSchema.inspect(database.openHelper.writableDatabase) shouldBe
				StepsCountDomainSchemaState.Incompatible
			StepsCountDomainSchema.installIfAbsent(database.openHelper.writableDatabase) shouldBe
				StepsCountDomainSchemaState.Incompatible
			database.openHelper.writableDatabase.count(
				"SELECT COUNT(*) FROM steps_count_domain_schema_marker " +
					"WHERE id = 1 AND contract_version = 1",
			) shouldBe 1L

			StepsCountDomainStore(database).recordSessionWal(
				insertWal("sentinel-corrupt", 1L, payloadVersion = 7),
				token('a'),
			) shouldBe StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
		}

	@Test
	fun `v2 marker cannot activate a stale or incomplete development schema`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"DROP TRIGGER ${StepsCountDomainSchema.TERMINAL_OWNER_TRIGGER}",
		)

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
		StepsCountDomainSchema.installIfAbsent(sqlite) shouldBe
			StepsCountDomainSchemaState.Incompatible
		sqlite.count(
			"SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = ?",
			arrayOf(StepsCountDomainSchema.TERMINAL_OWNER_TRIGGER),
		) shouldBe 0L
		sqlite.count(
			"SELECT COUNT(*) FROM steps_count_domain_schema_marker WHERE id = 1",
		) shouldBe 1L
	}

	@Test
	fun `terminal owner trigger rejects direct bind after unproven evidence`() = runTest {
		installSchema()
		val wal = insertWal("terminal-trigger", 1L)
		StepsCountDomainStore(database).recordSessionWal(wal, null) shouldBe
			StepsCountDomainWriteResult.INSERTED
		val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
			wal.admissionOrdinal,
			wal.eventId,
		)

		shouldThrow<Exception> {
			database.openHelper.writableDatabase.execSQL(
				"INSERT INTO steps_count_domain_owner_revision VALUES (?, ?, ?, 2, " +
					"'BIND', NULL, ?, 2)",
				arrayOf(
					"SESSION_WAL",
					StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity("tracking", "run"),
					ownerIdentity,
					"b".repeat(64),
				),
			)
		}
	}

	@Test
	fun `terminal trigger permits contiguous Ambient unproven corrections but no upgrade`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		val identity = "sha256:${"7".repeat(64)}"
		val scope = "sha256:${"8".repeat(64)}"
		sqlite.execSQL(
			"INSERT INTO steps_count_domain_owner_revision VALUES " +
				"('AMBIENT_FACT', ?, ?, 1, 'UNPROVEN', NULL, ?, 1)",
			arrayOf(scope, identity, "a".repeat(64)),
		)
		sqlite.execSQL(
			"INSERT INTO steps_count_domain_owner_revision VALUES " +
				"('AMBIENT_FACT', ?, ?, 2, 'UNPROVEN', NULL, ?, 2)",
			arrayOf(scope, identity, "b".repeat(64)),
		)
		sqlite.execSQL(
			"INSERT OR IGNORE INTO steps_count_domain_owner_revision VALUES " +
				"('AMBIENT_FACT', ?, ?, 2, 'UNPROVEN', NULL, ?, 2)",
			arrayOf(scope, identity, "b".repeat(64)),
		)

		shouldThrow<Exception> {
			sqlite.execSQL(
				"INSERT INTO steps_count_domain_owner_revision VALUES " +
					"('AMBIENT_FACT', ?, ?, 3, 'BIND', NULL, ?, 3)",
				arrayOf(scope, identity, "c".repeat(64)),
			)
		}
		shouldThrow<Exception> {
			sqlite.execSQL(
				"INSERT INTO steps_count_domain_owner_revision VALUES " +
					"('AMBIENT_FACT', ?, ?, 4, 'UNPROVEN', NULL, ?, 4)",
				arrayOf(scope, identity, "d".repeat(64)),
			)
		}
		sqlite.count(
			"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
				"WHERE owner_kind = 'AMBIENT_FACT' AND operation = 'UNPROVEN'",
		) shouldBe 2L
	}

	@Test
	fun `markerless e500 schema is incompatible and never receives v2 DDL or marker`() = runTest {
		installE500SchemaFixture()
		val sqlite = database.openHelper.writableDatabase

		StepsCountDomainSchema.inspect(sqlite) shouldBe StepsCountDomainSchemaState.Incompatible
		StepsCountDomainSchema.installIfAbsent(sqlite) shouldBe
			StepsCountDomainSchemaState.Incompatible
		sqlite.count(
			"SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
				"AND name = 'steps_count_domain_completeness_marker'",
		) shouldBe 0L
		sqlite.count(
			"SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
				"AND name = 'steps_count_domain_schema_marker'",
		) shouldBe 0L
		StepsCountDomainStore(database).recordSessionWal(
			insertWal("legacy-e500", 1L, payloadVersion = 7),
			token('a'),
		) shouldBe StepsCountDomainWriteResult.STORED_EVIDENCE_UNVERIFIABLE
	}

	private fun installSchema() {
		StepsCountDomainSchema.installIfAbsent(database.openHelper.writableDatabase) shouldBe
			StepsCountDomainSchemaState.ValidV2
	}

	private fun insertSchemaMarker(sqlite: SupportSQLiteDatabase) {
		sqlite.execSQL(
			"INSERT INTO steps_count_domain_schema_marker " +
				"(id, contract_version, token_semantics, terminal_unproven) " +
				"VALUES (1, 2, 'PROVIDER_COUNTER_EPOCH_V1', 1)",
		)
	}

	private fun SupportSQLiteDatabase.replaceTrigger(
		name: String,
		transform: (String) -> String,
	) {
		val sql = query(
			"SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = ?",
			arrayOf(name),
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getString(0)
		}
		execSQL("DROP TRIGGER `$name`")
		execSQL(transform(sql))
	}

	private fun SupportSQLiteDatabase.rewriteStoredTriggerSql(
		name: String,
		transform: (String) -> String,
	) {
		setStoredTriggerSql(name, transform(storedTriggerSql(name)))
	}

	private fun SupportSQLiteDatabase.storedTriggerSql(name: String): String =
		query(
			"SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = ?",
			arrayOf(name),
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getString(0)
		}

	private fun SupportSQLiteDatabase.duplicateStoredTrigger(name: String) {
		execSQL("PRAGMA writable_schema = ON")
		try {
			execSQL(
				"INSERT INTO sqlite_master (type, name, tbl_name, rootpage, sql) " +
					"SELECT type, name, tbl_name, rootpage, sql FROM sqlite_master " +
					"WHERE type = 'trigger' AND name = ?",
				arrayOf(name),
			)
		} finally {
			execSQL("PRAGMA writable_schema = OFF")
		}
	}

	private fun SupportSQLiteDatabase.setStoredTriggerSql(
		name: String,
		sql: String?,
	) {
		execSQL("PRAGMA writable_schema = ON")
		try {
			execSQL(
				"UPDATE sqlite_master SET sql = ? WHERE type = 'trigger' AND name = ?",
				arrayOf(sql, name),
			)
		} finally {
			execSQL("PRAGMA writable_schema = OFF")
		}
	}

	private fun SupportSQLiteDatabase.setStoredTriggerTable(
		name: String,
		table: String,
	) {
		execSQL("PRAGMA writable_schema = ON")
		try {
			execSQL(
				"UPDATE sqlite_master SET tbl_name = ? WHERE type = 'trigger' AND name = ?",
				arrayOf(table, name),
			)
		} finally {
			execSQL("PRAGMA writable_schema = OFF")
		}
	}

	private fun installE500SchemaFixture() {
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL(
			"""
			CREATE TABLE steps_count_domain_receipt (
				receipt_identity TEXT NOT NULL PRIMARY KEY,
				domain_identity TEXT NOT NULL,
				provider_domain_identity TEXT NOT NULL,
				source_instance_identity TEXT NOT NULL,
				owner_kind TEXT NOT NULL,
				scope_identity TEXT NOT NULL,
				owner_identity TEXT NOT NULL,
				owner_revision INTEGER NOT NULL,
				registration_generation INTEGER NOT NULL,
				collected_data_epoch INTEGER NOT NULL,
				authority_revision INTEGER NOT NULL,
				authority_fingerprint TEXT NOT NULL,
				coverage_kind TEXT NOT NULL,
				coverage_version INTEGER NOT NULL,
				count_domain_version INTEGER NOT NULL,
				effect_checksum TEXT NOT NULL
			)
			""".trimIndent(),
		)
		sqlite.execSQL(
			"CREATE UNIQUE INDEX idx_steps_count_domain_receipt_owner " +
				"ON steps_count_domain_receipt(owner_kind, owner_identity, owner_revision)",
		)
		sqlite.execSQL(
			"CREATE INDEX idx_steps_count_domain_receipt_compatibility " +
				"ON steps_count_domain_receipt(" +
				"domain_identity, collected_data_epoch, count_domain_version)",
		)
		sqlite.execSQL(
			"""
			CREATE TABLE steps_count_domain_owner_revision (
				owner_kind TEXT NOT NULL,
				scope_identity TEXT NOT NULL,
				owner_identity TEXT NOT NULL,
				owner_revision INTEGER NOT NULL,
				operation TEXT NOT NULL,
				receipt_identity TEXT,
				owner_effect_checksum TEXT NOT NULL,
				linked_at_ms INTEGER NOT NULL,
				PRIMARY KEY(owner_kind, owner_identity, owner_revision),
				FOREIGN KEY(receipt_identity)
					REFERENCES steps_count_domain_receipt(receipt_identity)
					ON UPDATE NO ACTION ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
			)
			""".trimIndent(),
		)
		sqlite.execSQL(
			"CREATE INDEX idx_steps_count_domain_owner_scope " +
				"ON steps_count_domain_owner_revision(" +
				"owner_kind, scope_identity, owner_identity, owner_revision)",
		)
		sqlite.execSQL(
			"CREATE INDEX idx_steps_count_domain_owner_receipt " +
				"ON steps_count_domain_owner_revision(receipt_identity)",
		)
		sqlite.execSQL(
			"""
			CREATE TRIGGER trg_steps_count_domain_ambient_no_resurrection
			BEFORE INSERT ON ambient_steps_fact_revision
			WHEN NEW.operation = 'UPSERT' AND EXISTS (
				SELECT 1
				FROM steps_count_domain_owner_revision AS owner
				WHERE owner.owner_kind = 'AMBIENT_FACT'
				  AND owner.owner_identity = NEW.logical_fact_id
				  AND owner.operation = 'RETRACT'
			)
			BEGIN
				SELECT RAISE(ABORT, 'Ambient Steps count-domain owner is terminally retracted');
			END
			""".trimIndent(),
		)
		sqlite.execSQL(
			"""
			CREATE TRIGGER trg_steps_count_domain_ambient_retraction
			AFTER INSERT ON ambient_steps_fact_revision
			WHEN NEW.operation = 'RETRACT'
			BEGIN
				INSERT OR ABORT INTO steps_count_domain_owner_revision (
					owner_kind,
					scope_identity,
					owner_identity,
					owner_revision,
					operation,
					receipt_identity,
					owner_effect_checksum,
					linked_at_ms
				) VALUES (
					'AMBIENT_FACT',
					NEW.logical_fact_id,
					NEW.logical_fact_id,
					NEW.semantic_revision,
					'RETRACT',
					NULL,
					NEW.effect_checksum,
					NEW.applied_at_ms
				);
			END
			""".trimIndent(),
		)
	}

	private suspend fun insertWal(
		eventId: String,
		sourceSequence: Long,
		payloadVersion: Int = 1,
		sourceInstanceId: String = "owner-$sourceSequence",
		registrationGeneration: Long = sourceSequence,
	): SourceEventWalEntity {
		val unsigned = SourceEventWalEntity(
			eventId = eventId,
			providerDedupKey = "dedup-$eventId",
			logicalTrackingId = "tracking",
			serviceRunId = "run",
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = registrationGeneration,
			physicalConfigurationFingerprint = "configuration-$sourceSequence",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "a".repeat(64),
			sourceSequence = sourceSequence,
			configRevision = sourceSequence,
			planAttribution = 0,
			clockDomainId = "boot",
			observedElapsedNanos = sourceSequence,
			receivedElapsedNanos = sourceSequence,
			wallTimeMs = sourceSequence,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 7L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = sourceSequence,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = payloadVersion,
			payload = byteArrayOf(sourceSequence.toByte()),
			payloadChecksum = "",
			createdAtMs = sourceSequence,
		)
		val payloadSigned = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		val signed = payloadSigned.copy(
			integrityIdentity = payloadSigned.calculatedIntegrityIdentity(),
		)
		val ordinal = database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(signed)
		return signed.copy(admissionOrdinal = ordinal)
	}

	private fun insertAmbientUnprovenOwnerHistory(
		digit: Char,
		revisionCount: Int,
	): StepsCountDomainOwnerLookupKey {
		require(digit in '0'..'9' || digit in 'a'..'f')
		require(revisionCount > 0)
		val identity = "sha256:${digit.toString().repeat(64)}"
		val scopeDigit = if (digit == 'f') 'e' else 'f'
		val scope = "sha256:${scopeDigit.toString().repeat(64)}"
		val sqlite = database.openHelper.writableDatabase
		repeat(revisionCount) { index ->
			val revision = index.toLong() + 1L
			val checksumDigit = (index % 16).toString(16)
			sqlite.execSQL(
				"INSERT INTO steps_count_domain_owner_revision VALUES " +
					"('AMBIENT_FACT', ?, ?, ?, 'UNPROVEN', NULL, ?, ?)",
				arrayOf(
					scope,
					identity,
					revision,
					checksumDigit.repeat(64),
					revision,
				),
			)
		}
		return StepsCountDomainOwnerLookupKey(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
			ownerIdentity = identity,
			ownerRevision = revisionCount.toLong(),
		)
	}

	private fun token(digit: Char) =
		StepsCounterDomainToken.opaque("sha256:${digit.toString().repeat(64)}")

	private data class StoredFieldMutation(
		val column: String,
		val corruptSql: String,
		val originalValue: Any?,
	)
}

private fun SupportSQLiteDatabase.count(
	sql: String,
	args: Array<out Any?> = emptyArray(),
): Long = query(sql, args).use { cursor ->
	check(cursor.moveToFirst())
	cursor.getLong(0)
}

@Database(
	entities = [
		AmbientStepsFactRevisionEntity::class,
		StepsCountDomainReceiptEntity::class,
		StepsCountDomainOwnerRevisionEntity::class,
		StepsCountDomainCompletenessMarkerEntity::class,
		StepsCountDomainSchemaMarkerEntity::class,
	],
	version = 1,
	exportSchema = false,
)
internal abstract class StepsCountDomainScaffoldTestDatabase : RoomDatabase()
