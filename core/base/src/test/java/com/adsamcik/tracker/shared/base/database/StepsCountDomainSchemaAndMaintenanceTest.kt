package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainSchemaMarkerEntity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
	fun `comments remain part of exact trigger authentication`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.replaceTrigger(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replace("WHEN NEW.", "WHEN /* injected */ NEW.")
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
	fun `quote injected trigger literal is incompatible`() {
		installSchema()
		val sqlite = database.openHelper.writableDatabase
		sqlite.replaceTrigger(StepsCountDomainSchema.AMBIENT_RETRACTION_TRIGGER) { sql ->
			sql.replace("'RETRACT'", "'''RETRACT'''")
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
		val sql = query(
			"SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = ?",
			arrayOf(name),
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getString(0)
		}
		execSQL("PRAGMA writable_schema = ON")
		try {
			execSQL(
				"UPDATE sqlite_master SET sql = ? WHERE type = 'trigger' AND name = ?",
				arrayOf(transform(sql), name),
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
	): SourceEventWalEntity {
		val unsigned = SourceEventWalEntity(
			eventId = eventId,
			providerDedupKey = "dedup-$eventId",
			logicalTrackingId = "tracking",
			serviceRunId = "run",
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			sourceInstanceId = "owner-$sourceSequence",
			registrationGeneration = sourceSequence,
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

	private fun token(digit: Char) =
		StepsCounterDomainToken.opaque("sha256:${digit.toString().repeat(64)}")
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
