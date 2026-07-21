package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V39ToV40MigrationTest {
	private lateinit var context: Application
	private lateinit var helper: SupportSQLiteOpenHelper
	private lateinit var db: SupportSQLiteDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(TEST_DB)
		helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(TEST_DB)
				.callback(object : SupportSQLiteOpenHelper.Callback(39) {
					override fun onCreate(db: SupportSQLiteDatabase) = Unit

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = Unit
				})
				.build(),
		)
		db = helper.writableDatabase
	}

	@After
	fun tearDown() {
		helper.close()
		context.deleteDatabase(TEST_DB)
	}

	@Test
	fun `migration adds durable import job and entry receipts`() {
		MIGRATION_39_40.migrate(db)

		tableExists("import_job_receipt") shouldBe true
		tableExists("import_entry_receipt") shouldBe true
		indexExists("import_job_receipt", "index_import_job_receipt_status") shouldBe true
		indexExists(
			"import_entry_receipt",
			"index_import_entry_receipt_job_id_status",
		) shouldBe true
		foreignKeyDeleteAction("import_entry_receipt") shouldBe "CASCADE"
	}

	private fun tableExists(table: String): Boolean =
		db.query(
			"SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?)",
			arrayOf<Any?>(table),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getInt(0) == 1
		}

	private fun indexExists(table: String, index: String): Boolean =
		db.query("PRAGMA index_list($table)").use { cursor ->
			val nameIndex = cursor.getColumnIndexOrThrow("name")
			generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
				.any { it == index }
		}

	private fun foreignKeyDeleteAction(table: String): String =
		db.query("PRAGMA foreign_key_list($table)").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(cursor.getColumnIndexOrThrow("on_delete"))
		}

	private companion object {
		const val TEST_DB = "v39_to_v40_migration_test.db"
	}
}
