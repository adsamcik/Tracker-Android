package com.adsamcik.tracker.logger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release2024_1LogMigrationTest {
	private lateinit var context: Application
	private var database: LogDatabase? = null

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(TEST_DATABASE)
		val target = context.getDatabasePath(TEST_DATABASE)
		target.parentFile?.mkdirs()
		checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE)) {
			"Missing release fixture: $FIXTURE"
		}.use { input ->
			target.outputStream().use(input::copyTo)
		}
	}

	@After
	fun tearDown() {
		database?.close()
		context.deleteDatabase(TEST_DATABASE)
	}

	@Test
	fun `v1 log database migrates without deleting any log row`() {
		LogDatabase.databaseName shouldBe TEST_DATABASE
		val migrated = Room.databaseBuilder(context, LogDatabase::class.java, TEST_DATABASE)
			.addMigrations(*LogDatabase.migrations)
			.allowMainThreadQueries()
			.build()
		database = migrated
		val raw = migrated.openHelper.writableDatabase

		raw.version shouldBe 2
		raw.query("SELECT COUNT(*) FROM log_data").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getInt(0) shouldBe 3
		}
		raw.query("SELECT message, data, source FROM log_data WHERE id = 2").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0) shouldContain "Síť-测试"
			cursor.getString(1) shouldBe """{"value":null}"""
			cursor.getString(2) shouldBe "Activity"
		}
		raw.query("SELECT COUNT(*) FROM crash_data").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getInt(0) shouldBe 0
		}
		raw.query("SELECT timeStamp, message, data, source FROM log_data WHERE id = 3").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) shouldBe 9_223_372_036_854_000_000L
			cursor.getString(1) shouldBe ""
			cursor.getString(2).length shouldBe 4096
			cursor.getString(3) shouldBe "Boundary"
		}
	}

	private companion object {
		const val TEST_DATABASE = "debug_database"
		const val FIXTURE = "baseline/2024.1/debug_database_logger.db"
	}
}
