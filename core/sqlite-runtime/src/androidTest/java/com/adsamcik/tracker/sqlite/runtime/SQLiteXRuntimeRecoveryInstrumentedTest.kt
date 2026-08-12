package com.adsamcik.tracker.sqlite.runtime

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Device validation for the packaged native binding and its crash-recovery filesystem contract. */
@RunWith(AndroidJUnit4::class)
class SQLiteXRuntimeRecoveryInstrumentedTest {
	private lateinit var context: Context
	private lateinit var databaseFile: File
	private lateinit var baseSnapshot: File
	private lateinit var walSnapshot: File

	@Before
	fun setUp() {
		context = InstrumentationRegistry.getInstrumentation().targetContext
		databaseFile = context.getDatabasePath(DATABASE_NAME)
		baseSnapshot = File(context.cacheDir, "$DATABASE_NAME.crash-base")
		walSnapshot = File(context.cacheDir, "$DATABASE_NAME.crash-wal")
		deleteArtifacts()
	}

	@After
	fun tearDown() {
		deleteArtifacts()
	}

	@Test
	fun packagedRuntimeRecoversCommittedWalSnapshotAfterFreshOpen() {
		val firstHelper = createHelper()
		firstHelper.setWriteAheadLoggingEnabled(true)
		try {
			val database = firstHelper.writableDatabase
			assertRuntimeIdentity(database)
			assertEquals("wal", stringValue(database, "PRAGMA journal_mode"))
			assertCallbackConnectionProbe(database)

			// Put the schema and user_version in the base file, then keep the data commit solely in WAL.
			assertCheckpointSucceeded(database, "PRAGMA wal_checkpoint(TRUNCATE)")
			database.execSQL("PRAGMA wal_autocheckpoint = 0")
			database.execSQL("INSERT INTO recovery_probe(value) VALUES ('committed-before-kill')")

			val liveWal = File("${databaseFile.path}-wal")
			assertTrue("Expected a WAL file after the committed write", liveWal.isFile)
			assertTrue("Expected committed frames beyond the WAL header", liveWal.length() > WAL_HEADER_BYTES)

			// Copy only DB + WAL while the connection is live. Omitting SHM mirrors its disposable
			// nature after abrupt process death; the next process must rebuild it and recover the WAL.
			databaseFile.copyTo(baseSnapshot, overwrite = true)
			liveWal.copyTo(walSnapshot, overwrite = true)
		} finally {
			firstHelper.close()
		}

		restoreCrashSnapshot()

		val restartedHelper = createHelper()
		restartedHelper.setWriteAheadLoggingEnabled(true)
		try {
			val recovered = restartedHelper.writableDatabase
			assertRuntimeIdentity(recovered)
			assertEquals("wal", stringValue(recovered, "PRAGMA journal_mode"))
			assertCallbackConnectionProbe(recovered)
			assertEquals("ok", stringValue(recovered, "PRAGMA quick_check"))
			assertEquals(
				1L,
				longValue(
					recovered,
					"SELECT COUNT(*) FROM recovery_probe WHERE value = 'committed-before-kill'",
				),
			)
		} finally {
			restartedHelper.close()
		}
	}

	private fun createHelper(): SupportSQLiteOpenHelper =
		SQLiteXSupportSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(DATABASE_NAME)
				.callback(
					object : SupportSQLiteOpenHelper.Callback(SCHEMA_VERSION) {
						override fun onOpen(db: SupportSQLiteDatabase) {
							db.execSQL(
								"CREATE TEMP TABLE callback_connection_probe(value INTEGER NOT NULL)",
							)
							db.execSQL("INSERT INTO callback_connection_probe(value) VALUES (1)")
						}

						override fun onCreate(db: SupportSQLiteDatabase) {
							db.execSQL(
								"CREATE TABLE recovery_probe(id INTEGER PRIMARY KEY, value TEXT NOT NULL)",
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

	private fun restoreCrashSnapshot() {
		context.deleteDatabase(DATABASE_NAME)
		databaseFile.parentFile?.mkdirs()
		baseSnapshot.copyTo(databaseFile, overwrite = true)
		walSnapshot.copyTo(File("${databaseFile.path}-wal"), overwrite = true)
		File("${databaseFile.path}-shm").delete()
	}

	private fun assertRuntimeIdentity(database: SupportSQLiteDatabase) {
		assertEquals(EXPECTED_SQLITE_VERSION, stringValue(database, "SELECT sqlite_version()"))
		assertEquals(EXPECTED_SQLITE_SOURCE_ID, stringValue(database, "SELECT sqlite_source_id()"))
	}

	private fun assertCallbackConnectionProbe(database: SupportSQLiteDatabase) {
		database.beginTransaction()
		try {
			assertEquals(1L, longValue(database, "SELECT COUNT(*) FROM callback_connection_probe"))
			database.setTransactionSuccessful()
		} finally {
			database.endTransaction()
		}
	}

	private fun assertCheckpointSucceeded(database: SupportSQLiteDatabase, sql: String) {
		database.query(sql).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(0, cursor.getInt(0))
		}
	}

	private fun stringValue(database: SupportSQLiteDatabase, sql: String): String =
		database.query(sql).use { cursor ->
			assertTrue(cursor.moveToFirst())
			cursor.getString(0)
		}

	private fun longValue(database: SupportSQLiteDatabase, sql: String): Long =
		database.query(sql).use { cursor ->
			assertTrue(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private fun deleteArtifacts() {
		if (::context.isInitialized) {
			context.deleteDatabase(DATABASE_NAME)
		}
		if (::databaseFile.isInitialized) {
			listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
				File("${databaseFile.path}$suffix").delete()
			}
		}
		if (::baseSnapshot.isInitialized) baseSnapshot.delete()
		if (::walSnapshot.isInitialized) walSnapshot.delete()
	}

	private companion object {
		const val DATABASE_NAME = "sqlite_runtime_recovery_test.db"
		const val SCHEMA_VERSION = 1
		const val WAL_HEADER_BYTES = 32L
		const val EXPECTED_SQLITE_VERSION = "3.53.3"
		const val EXPECTED_SQLITE_SOURCE_ID =
			"2026-06-26 20:14:12 d4c0e51e4aeb96955b99185ab9cde75c339e2c29c3f3f12428d364a10d782c62"
	}
}
