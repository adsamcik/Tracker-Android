package com.adsamcik.tracker.sqlite.runtime

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Safe, temporary-database fault injection against the exact SQLiteX runtime shipped in the APK. */
@RunWith(AndroidJUnit4::class)
class SQLiteXRuntimeFaultInstrumentedTest {
	private lateinit var context: Context

	@Before
	fun setUp() {
		context = InstrumentationRegistry.getInstrumentation().targetContext
		context.deleteDatabase(DATABASE_NAME)
	}

	@After
	fun tearDown() {
		context.deleteDatabase(DATABASE_NAME)
	}

	@Test
	fun writerLockSurfacesVendorLockAndNextWriteSucceedsAfterRelease() {
		val first = createHelper()
		val second = createHelper()
		val executor = Executors.newSingleThreadExecutor()
		try {
			val lockOwner = first.writableDatabase
			val contender = second.writableDatabase
			contender.execSQL("PRAGMA busy_timeout = 100")
			lockOwner.beginTransaction()
			try {
				lockOwner.execSQL("INSERT INTO fault_probe(value) VALUES ('lock-owner')")
				val failure = executor.submit<Throwable?> {
					runCatching {
						contender.execSQL("INSERT INTO fault_probe(value) VALUES ('contender')")
					}.exceptionOrNull()
				}.get(5, TimeUnit.SECONDS)
				assertNotNull("A second writer must observe the injected lock", failure)
				requireNotNull(failure)
				Log.i(TAG, "lockException=${failure.javaClass.name} message=${failure.message}")
				assertTrue("Expected SQLite lock/busy failure: $failure", failure.isSQLiteLock())
				lockOwner.setTransactionSuccessful()
			} finally {
				lockOwner.endTransaction()
			}

			contender.execSQL("INSERT INTO fault_probe(value) VALUES ('after-release')")
			assertEquals(2L, longValue(contender, "SELECT COUNT(*) FROM fault_probe"))
		} finally {
			executor.shutdownNow()
			second.close()
			first.close()
		}
	}

	@Test
	fun maxPageCountSurfacesVendorFullAndDatabaseRecoversWhenCapacityReturns() {
		val helper = createHelper()
		try {
			val database = helper.writableDatabase
			database.execSQL("PRAGMA journal_mode = DELETE")
			database.execSQL("INSERT INTO fault_probe(value) VALUES ('baseline')")
			val currentPages = longValue(database, "PRAGMA page_count")
			database.execSQL("PRAGMA max_page_count = $currentPages")

			val failure = runCatching {
				database.execSQL(
					"INSERT INTO fault_probe(value) SELECT hex(randomblob(1048576))",
				)
			}.exceptionOrNull()
			assertNotNull("The page cap must inject SQLITE_FULL", failure)
			requireNotNull(failure)
			Log.i(TAG, "fullException=${failure.javaClass.name} message=${failure.message}")
			assertTrue("Expected SQLite full failure: $failure", failure.isSQLiteFull())
			assertEquals(1L, longValue(database, "SELECT COUNT(*) FROM fault_probe"))

			database.execSQL("PRAGMA max_page_count = 1073741823")
			database.execSQL("INSERT INTO fault_probe(value) VALUES ('after-capacity-returned')")
			assertEquals(2L, longValue(database, "SELECT COUNT(*) FROM fault_probe"))
		} finally {
			helper.close()
		}
	}

	private fun createHelper(): SupportSQLiteOpenHelper =
		SQLiteXSupportSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(
					object : SupportSQLiteOpenHelper.Callback(1) {
						override fun onCreate(db: SupportSQLiteDatabase) {
							db.execSQL(
								"CREATE TABLE fault_probe(id INTEGER PRIMARY KEY, value TEXT NOT NULL)",
							)
						}

						override fun onUpgrade(
							db: SupportSQLiteDatabase,
							oldVersion: Int,
							newVersion: Int,
						) = error("Unexpected upgrade from $oldVersion to $newVersion")
					},
				)
				.build(),
		)

	private fun longValue(database: SupportSQLiteDatabase, sql: String): Long =
		database.query(sql).use { cursor ->
			assertTrue(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private fun Throwable.isSQLiteLock(): Boolean {
		val description = "$javaClass $message".lowercase()
		return description.contains("locked") || description.contains("sqlite_busy") ||
			description.contains("sqlite_locked")
	}

	private fun Throwable.isSQLiteFull(): Boolean {
		val description = "$javaClass $message".lowercase()
		return description.contains("sqlitefull") || description.contains("sqlite_full") ||
			description.contains("disk is full")
	}

	private companion object {
		const val DATABASE_NAME = "sqlite_runtime_fault_test.db"
		const val TAG = "SQLiteXFaultCheck"
	}
}
