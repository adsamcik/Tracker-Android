package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DevelopmentV28SQLiteXContainmentInstrumentedTest {
	private lateinit var context: Context
	private lateinit var databaseFile: File

	@Before
	fun setUp() {
		context = InstrumentationRegistry.getInstrumentation().targetContext
		databaseFile = context.getDatabasePath(DATABASE_NAME)
		deleteFileFamily()
		createCorruptFileFamily()
	}

	@After
	fun tearDown() {
		deleteFileFamily()
	}

	@Test
	fun admittedDelegateCorruptionPreservesFilesAndBypassesRoomDeletionCallback() {
		assertBlockedFileFamilyUnchanged()
	}

	@Test
	fun truncatedHeaderPreservesFilesAndBypassesRoomDeletionCallback() {
		databaseFile.writeBytes("SQLite".toByteArray(Charsets.US_ASCII))
		assertBlockedFileFamilyUnchanged()
	}

	@Test
	fun preservationAllowsNewAndEmptyDatabases() {
		for (existingEmptyFile in listOf(false, true)) {
			deleteFileFamily()
			if (existingEmptyFile) {
				databaseFile.parentFile?.mkdirs()
				databaseFile.writeBytes(byteArrayOf())
			}
			preservedHelper().use { helper ->
				assertEquals(CURRENT_DATABASE_VERSION, helper.writableDatabase.version)
			}
			assertTrue(databaseFile.isFile)
		}
	}

	@Test
	fun preservationAllowsValidSavedDataToReopen() {
		deleteFileFamily()
		preservedHelper().use { helper ->
			helper.writableDatabase.execSQL("CREATE TABLE retained(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
			helper.writableDatabase.execSQL("INSERT INTO retained(id, value) VALUES (1, 'saved')")
		}
		preservedHelper().use { helper ->
			helper.writableDatabase.query("SELECT value FROM retained WHERE id = 1").use { row ->
				assertTrue(row.moveToFirst())
				assertEquals("saved", row.getString(0))
			}
		}
	}

	private fun assertBlockedFileFamilyUnchanged() {
		val before = fileFamily().associateWith(::sha256)
		var roomCorruptionCallbackCalled = false
		val helper = preservedHelper { roomCorruptionCallbackCalled = true }
		try {
			val failure = runCatching { helper.writableDatabase }.exceptionOrNull()
			val failureDescription = failure?.let { "${it.javaClass.name}: ${it.message}" }
				?: "database opened successfully"

			assertTrue(
				"Expected blocked corruption; actual: $failureDescription",
				failure is ActiveDatabaseOpenBlockedException,
			)
			assertEquals(
				ActiveDatabaseBlockReason.UNREADABLE_DATABASE,
				(failure as ActiveDatabaseOpenBlockedException).reason,
			)
			assertFalse(roomCorruptionCallbackCalled)
			before.forEach { (file, hash) ->
				assertTrue("Missing preserved database file: ${file.name}", file.exists())
				assertEquals("Changed preserved database file: ${file.name}", hash, sha256(file))
			}
		} finally {
			helper.close()
		}
	}

	private fun preservedHelper(onCorruptionDetected: () -> Unit = {}): SupportSQLiteOpenHelper =
		DevelopmentV28ContainmentOpenHelperFactory(
			delegate = SQLiteXSupportSQLiteOpenHelperFactory(
				preserveDatabaseFilesOnCorruption = true,
			),
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

					override fun onCorruption(db: SupportSQLiteDatabase) {
						onCorruptionDetected()
						super.onCorruption(db)
					}
				})
				.build(),
		)

	private fun createCorruptFileFamily() {
		databaseFile.parentFile?.mkdirs()
		fileFamily().forEachIndexed { index, file ->
			file.writeBytes(ByteArray(128) { offset -> (index * 37 + offset).toByte() })
		}
	}

	private fun fileFamily(): List<File> = listOf(
		databaseFile,
		File("${databaseFile.path}-wal"),
		File("${databaseFile.path}-shm"),
		File("${databaseFile.path}-journal"),
	)

	private fun deleteFileFamily() {
		if (::context.isInitialized) {
			context.deleteDatabase(DATABASE_NAME)
		}
		if (::databaseFile.isInitialized) {
			fileFamily().forEach { it.delete() }
		}
	}

	private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
		.digest(file.readBytes())
		.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

	private companion object {
		const val DATABASE_NAME = "development-v28-sqlitex-containment-test.db"
	}
}
