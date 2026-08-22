package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseImportCollisionTest {

	@Test
	fun `colliding parent and child ids are remapped without breaking foreign keys`() =
		withDatabases { sourceRoom, targetRoom ->
			val source = sourceRoom.openHelper.writableDatabase
			val target = targetRoom.openHelper.writableDatabase
			createParentChildSchema(source, includeForeignKey = true)
			createParentChildSchema(target, includeForeignKey = true)
			target.execSQL("INSERT INTO parent (id, name) VALUES (1, 'existing')")
			target.execSQL(
				"INSERT INTO child (id, parent_id, value) VALUES (1, 1, 'existing-child')",
			)
			source.execSQL("INSERT INTO parent (id, name) VALUES (1, 'imported')")
			source.execSQL(
				"INSERT INTO child (id, parent_id, value) VALUES (1, 1, 'imported-child')",
			)

			val result = testImporter().importDatabase(source, target)
				.shouldBeInstanceOf<DatabaseImportResult.Success>()
				.result

			result.failedCount shouldBe 0
			result.successCount shouldBe 2
			target.singleInt("SELECT COUNT(*) FROM parent") shouldBe 2
			target.query(
				"""
				SELECT parent.name
				FROM child
				JOIN parent ON parent.id = child.parent_id
				WHERE child.value = 'imported-child'
				""".trimIndent(),
			).use {
				it.moveToFirst() shouldBe true
				it.getString(0) shouldBe "imported"
			}
			target.query("PRAGMA foreign_key_check").use {
				it.count shouldBe 0
			}
			source.singleInt("SELECT id FROM parent WHERE name = 'imported'") shouldBe 1
			source.singleInt("SELECT parent_id FROM child WHERE value = 'imported-child'") shouldBe 1
		}

	@Test
	fun `database containing an update trigger is rejected before remapping`() =
		withDatabases { sourceRoom, targetRoom ->
			val source = sourceRoom.openHelper.writableDatabase
			val target = targetRoom.openHelper.writableDatabase
			createParentChildSchema(source, includeForeignKey = true)
			createParentChildSchema(target, includeForeignKey = true)
			source.execSQL("CREATE TABLE trigger_marker (`value` TEXT NOT NULL)")
			source.execSQL(
				"""
				CREATE TRIGGER malicious_after_update
				AFTER UPDATE ON parent
				BEGIN
					INSERT INTO trigger_marker (`value`) VALUES ('trigger-fired');
				END
				""".trimIndent(),
			)
			target.execSQL("INSERT INTO parent (id, name) VALUES (1, 'existing')")
			source.execSQL("INSERT INTO parent (id, name) VALUES (1, 'imported')")

			val failure = testImporter().importDatabase(source, target)
				.shouldBeInstanceOf<DatabaseImportResult.Failure>()

			failure.reason.message shouldContain
				"Unsupported trigger schema object in imported database: malicious_after_update"
			source.singleInt("SELECT COUNT(*) FROM trigger_marker") shouldBe 0
			target.singleInt("SELECT COUNT(*) FROM parent") shouldBe 1
		}

	@Test
	fun `application database schema requires restore instead of merge import`() =
		withDatabases { sourceRoom, targetRoom ->
			val source = sourceRoom.openHelper.writableDatabase
			val target = targetRoom.openHelper.writableDatabase

			val failure = DatabaseImport().importDatabase(source, target)
				.shouldBeInstanceOf<DatabaseImportResult.Failure>()

			failure.reason shouldBe DatabaseImportFailure.TrackerDatabaseRestoreRequired
		}

	@Test
	fun `secondary unique collision is skipped while non-conflicting rows are preserved`() =
		withDatabases { sourceRoom, targetRoom ->
			val source = sourceRoom.openHelper.writableDatabase
			val target = targetRoom.openHelper.writableDatabase
			createUniqueSchema(source)
			createUniqueSchema(target)
			target.execSQL("INSERT INTO unique_item (id, code) VALUES (1, 'duplicate')")
			source.execSQL(
				"INSERT INTO unique_item (id, code) VALUES (1, 'duplicate'), (2, 'fresh')",
			)

			val result = testImporter().importDatabase(source, target)
				.shouldBeInstanceOf<DatabaseImportResult.Success>()
				.result

			result.successCount shouldBe 1
			result.skippedCount shouldBe 1
			result.failedCount shouldBe 0
			target.singleInt("SELECT COUNT(*) FROM unique_item") shouldBe 2
			target.singleInt("SELECT COUNT(*) FROM unique_item WHERE code = 'fresh'") shouldBe 1
		}

	@Test
	fun `constraint failure rolls back rows imported earlier in the database file`() =
		withDatabases { sourceRoom, targetRoom ->
			val source = sourceRoom.openHelper.writableDatabase
			val target = targetRoom.openHelper.writableDatabase
			createRollbackSchema(source, includeForeignKey = false)
			createRollbackSchema(target, includeForeignKey = true)
			source.execSQL("INSERT INTO a_valid (id, value) VALUES (1, 'must-roll-back')")
			source.execSQL("INSERT INTO z_child (id, parent_id) VALUES (1, 999)")

			val result = testImporter().importCopiedDatabase(source, targetRoom)

			result.failedCount shouldBe 1
			result.errors.single() shouldContain "Constraint violation in table z_child"
			target.singleInt("SELECT COUNT(*) FROM a_valid") shouldBe 0
			target.singleInt("SELECT COUNT(*) FROM z_child") shouldBe 0
		}

	@Test
	fun `binary columns survive collision remapping unchanged`() =
		withDatabases { sourceRoom, targetRoom ->
			val source = sourceRoom.openHelper.writableDatabase
			val target = targetRoom.openHelper.writableDatabase
			createBlobSchema(source)
			createBlobSchema(target)
			target.execSQL("INSERT INTO blob_item (id, payload) VALUES (1, X'00')")
			source.execSQL("INSERT INTO blob_item (id, payload) VALUES (1, X'0102FF')")

			testImporter().importDatabase(source, target)
				.shouldBeInstanceOf<DatabaseImportResult.Success>()

			target.query("SELECT payload FROM blob_item WHERE id != 1").use {
				it.moveToFirst() shouldBe true
				it.getBlob(0).contentEquals(byteArrayOf(1, 2, -1)) shouldBe true
			}
		}

	@Test
	fun `foreign keys in a skipped incompatible table follow remapped parents`() =
		withDatabases { sourceRoom, targetRoom ->
			val source = sourceRoom.openHelper.writableDatabase
			val target = targetRoom.openHelper.writableDatabase
			createParentChildSchema(source, includeForeignKey = true)
			createIncompatibleTargetSchema(target)
			target.execSQL("INSERT INTO parent (id, name) VALUES (1, 'existing')")
			source.execSQL("INSERT INTO parent (id, name) VALUES (1, 'imported')")
			source.execSQL("INSERT INTO child (id, parent_id, value) VALUES (1, 1, 'skipped')")

			val result = testImporter().importDatabase(source, target)
				.shouldBeInstanceOf<DatabaseImportResult.Success>()
				.result

			result.successCount shouldBe 1
			target.singleInt("SELECT COUNT(*) FROM parent") shouldBe 2
			target.singleInt("SELECT COUNT(*) FROM child") shouldBe 0
			source.singleInt("SELECT parent_id FROM child") shouldBe 1
			source.query("PRAGMA foreign_key_check").use { it.count shouldBe 0 }
		}

	@Test
	fun `old Tracker database cannot resurrect facts after collected data deletion`() =
		withDatabases { sourceRoom, targetRoom ->
			val source = sourceRoom.openHelper.writableDatabase
			val target = targetRoom.openHelper.writableDatabase
			source.execSQL(
				"INSERT INTO activity (id, name, iconName) VALUES (1000, 'Imported custom activity', NULL)",
			)
			source.execSQL(
				"INSERT INTO daily_summary " +
					"(date_epoch_day, total_distance_m, total_steps, total_duration_ms, trip_count, " +
					"active_tracking_ms, last_updated_ms, created_at) " +
					"VALUES (20000, 123.0, 45, 60000, 1, 60000, 1000, 1000)",
			)
			target.execSQL(
				"INSERT OR REPLACE INTO source_evidence_state " +
					"(id, revision, collected_data_epoch, retained_from_ms, updated_at_ms) " +
					"VALUES (1, 41, 7, 5000, 9000)",
			)

			val failure = DatabaseImport().importDatabase(source, target)
				.shouldBeInstanceOf<DatabaseImportResult.Failure>()

			failure.reason shouldBe DatabaseImportFailure.TrackerDatabaseRestoreRequired
			target.singleInt(
				"SELECT COUNT(*) FROM activity WHERE name = 'Imported custom activity'",
			) shouldBe 0
			target.singleInt("SELECT COUNT(*) FROM daily_summary WHERE date_epoch_day = 20000") shouldBe 0
			target.query(
				"SELECT revision, collected_data_epoch, retained_from_ms, updated_at_ms " +
					"FROM source_evidence_state WHERE id = 1",
			).use {
				it.moveToFirst() shouldBe true
				it.getLong(0) shouldBe 41L
				it.getLong(1) shouldBe 7L
				it.getLong(2) shouldBe 5000L
				it.getLong(3) shouldBe 9000L
			}
		}

	private fun withDatabases(block: (AppDatabase, AppDatabase) -> Unit) {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val sourceRoom = AppDatabase.testDatabase(context)
		val targetRoom = AppDatabase.testDatabase(context)
		try {
			block(sourceRoom, targetRoom)
		} finally {
			sourceRoom.close()
			targetRoom.close()
		}
	}

	private fun testImporter() = DatabaseImport(
		allowedMergeTables = setOf(
			"parent",
			"child",
			"unique_item",
			"a_valid",
			"z_child",
			"blob_item",
		),
		rejectTrackerDatabaseBackups = false,
	)

	private fun createParentChildSchema(
		database: SupportSQLiteDatabase,
		includeForeignKey: Boolean,
	) {
		database.execSQL(
			"""
			CREATE TABLE parent (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`name` TEXT NOT NULL
			)
			""".trimIndent(),
		)
		val foreignKey = if (includeForeignKey) {
			", FOREIGN KEY(`parent_id`) REFERENCES `parent`(`id`) ON DELETE CASCADE"
		} else {
			""
		}
		database.execSQL(
			"""
			CREATE TABLE child (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`parent_id` INTEGER NOT NULL,
				`value` TEXT NOT NULL
				$foreignKey
			)
			""".trimIndent(),
		)
	}

	private fun createUniqueSchema(database: SupportSQLiteDatabase) {
		database.execSQL(
			"""
			CREATE TABLE unique_item (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`code` TEXT NOT NULL
			)
			""".trimIndent(),
		)
		database.execSQL(
			"CREATE UNIQUE INDEX index_unique_item_code ON unique_item (code)",
		)
	}

	private fun createIncompatibleTargetSchema(database: SupportSQLiteDatabase) {
		database.execSQL(
			"""
			CREATE TABLE parent (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`name` TEXT NOT NULL
			)
			""".trimIndent(),
		)
		database.execSQL(
			"""
			CREATE TABLE child (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`parent_id` INTEGER NOT NULL,
				`value` TEXT NOT NULL,
				`required_new` TEXT NOT NULL,
				FOREIGN KEY(`parent_id`) REFERENCES `parent`(`id`) ON DELETE CASCADE
			)
			""".trimIndent(),
		)
	}

	private fun createRollbackSchema(
		database: SupportSQLiteDatabase,
		includeForeignKey: Boolean,
	) {
		database.execSQL(
			"CREATE TABLE a_valid (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `value` TEXT NOT NULL)",
		)
		database.execSQL(
			"CREATE TABLE parent (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)",
		)
		val foreignKey = if (includeForeignKey) {
			", FOREIGN KEY(`parent_id`) REFERENCES `parent`(`id`)"
		} else {
			""
		}
		database.execSQL(
			"""
			CREATE TABLE z_child (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`parent_id` INTEGER NOT NULL
				$foreignKey
			)
			""".trimIndent(),
		)
	}

	private fun createBlobSchema(database: SupportSQLiteDatabase) {
		database.execSQL(
			"CREATE TABLE blob_item (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `payload` BLOB NOT NULL)",
		)
	}

	private fun SupportSQLiteDatabase.singleInt(sql: String): Int {
		return query(sql).use {
			it.moveToFirst()
			it.getInt(0)
		}
	}
}
