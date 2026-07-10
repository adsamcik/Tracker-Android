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
class V33ToV34MigrationTest {
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
				.callback(object : SupportSQLiteOpenHelper.Callback(33) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						db.execSQL(
							"""
							CREATE TABLE pending_signal (
								id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
								session_id INTEGER NOT NULL,
								signal_json TEXT NOT NULL,
								created_at INTEGER NOT NULL
							)
							""".trimIndent(),
						)
						db.execSQL(
							"""
							CREATE INDEX idx_pending_signal_session_time
							ON pending_signal(session_id, created_at)
							""".trimIndent(),
						)
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = Unit
				})
				.build(),
		)
		db = helper.writableDatabase
		db.execSQL(
			"""
			INSERT INTO pending_signal(session_id, signal_json, created_at)
			VALUES (7, '{}', 1000)
			""".trimIndent(),
		)
	}

	@After
	fun tearDown() {
		helper.close()
		context.deleteDatabase(TEST_DB)
	}

	@Test
	fun `migration adds global recovery ordering index and preserves rows`() {
		MIGRATION_33_34.migrate(db)

		db.query(
			"""
			SELECT COUNT(*) FROM sqlite_master
			WHERE type = 'index' AND name = 'idx_pending_signal_recovery_order'
			""".trimIndent(),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getInt(0) shouldBe 1
		}
		db.query("SELECT COUNT(*) FROM pending_signal").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getInt(0) shouldBe 1
		}
	}

	private companion object {
		const val TEST_DB = "v33_to_v34_migration_test.db"
	}
}
