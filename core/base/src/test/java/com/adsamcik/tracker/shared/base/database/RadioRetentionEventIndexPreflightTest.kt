package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.util.TableInfo
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
class RadioRetentionEventIndexPreflightTest {
	private lateinit var context: Application

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(DATABASE_NAME)
	}

	@After
	fun tearDown() {
		context.deleteDatabase(DATABASE_NAME)
	}

	@Test
	fun `fresh v29 retains the nonunique event index and passes current preflight`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME).allowMainThreadQueries().build()
		try {
			val table = TableInfo.read(database.openHelper.writableDatabase, TABLE)
			requireNotNull(table.indices).single { it.name == INDEX }.unique shouldBe false
		} finally {
			database.close()
		}

		ActiveDatabasePreflight(context.getDatabasePath(DATABASE_NAME)).inspect() shouldBe
			ActiveDatabasePreflightResult.FinalV29
	}

	@Test
	fun `earlier unshipped v29 unique event index stays preserved and blocked`() {
		val database = AppDatabase.fileBuilder(context, DATABASE_NAME).allowMainThreadQueries().build()
		try {
			val db = database.openHelper.writableDatabase
			db.execSQL("DROP INDEX $INDEX")
			db.execSQL("CREATE UNIQUE INDEX $INDEX ON $TABLE(source_kind, source_event_id)")
		} finally {
			database.close()
		}
		val file = context.getDatabasePath(DATABASE_NAME)
		val before = file.readBytes().toList()

		ActiveDatabasePreflight(file).inspect() shouldBe ActiveDatabasePreflightResult.Blocked(
			ActiveDatabaseBlockReason.INCOMPLETE_FINAL_V29_SCHEMA,
		)
		file.readBytes().toList() shouldBe before
	}

	private companion object {
		const val DATABASE_NAME = "radio-retention-event-index-test.db"
		const val TABLE = "captured_radio_fact_retention_proof"
		const val INDEX = "idx_captured_radio_retention_event"
	}
}
