package com.adsamcik.tracker.shared.base.database.legacy

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyDatabaseRepositoryTest {
	private lateinit var context: Application
	private lateinit var repository: LegacyDatabaseRepository

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(LEGACY_DATABASE_NAME)
		context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.clear()
			.commit()
		repository = LegacyDatabaseRepository(context, nowMillis = { 1234L })
	}

	@After
	fun tearDown() {
		context.deleteDatabase(LEGACY_DATABASE_NAME)
		context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.clear()
			.commit()
	}

	@Test
	fun `export authorizes deletion only after destination close succeeds`() {
		createLegacyDatabase()
		val failedDestination = object : ByteArrayOutputStream() {
			override fun close() {
				super.close()
				throw IOException("destination close failed")
			}
		}

		shouldThrow<LegacyDatabaseException> { repository.export(failedDestination) }

		(failedDestination.size() > 0) shouldBe true
		repository.currentState().externallyExported shouldBe false
		repository.currentState().canDelete shouldBe false

		var closed = false
		val successfulDestination = object : ByteArrayOutputStream() {
			override fun close() {
				super.close()
				closed = true
			}
		}
		repository.export(successfulDestination)

		closed shouldBe true
		repository.currentState().externallyExported shouldBe true
		repository.currentState().canDelete shouldBe true
		val databaseFile = context.getDatabasePath(LEGACY_DATABASE_NAME)
		val sidecars = listOf(File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"))
		sidecars.forEach { it.writeBytes(byteArrayOf(1, 2, 3)) }
		(repository.delete() > 0L) shouldBe true
		databaseFile.exists() shouldBe false
		sidecars.any(File::exists) shouldBe false
	}

	@Test
	fun `strict inspection failure can be recorded durably`() {
		context.getDatabasePath(LEGACY_DATABASE_NAME).apply {
			parentFile?.mkdirs()
			writeBytes(byteArrayOf(1, 2, 3, 4))
		}

		shouldThrow<LegacyDatabaseException> { repository.inspect() }
		repository.markFailed(IllegalStateException("broken source"))

		val state = repository.currentState()
		state.importStatus shouldBe LegacyImportStatus.FAILED
		state.lastError shouldBe "IllegalStateException"
	}

	private fun createLegacyDatabase() {
		SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(LEGACY_DATABASE_NAME), null).use {
			it.version = 26
			it.execSQL("CREATE TABLE sample(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
			it.execSQL("INSERT INTO sample(id, value) VALUES (1, 'preserved')")
		}
	}

	private companion object {
		const val PREFERENCES_NAME = "legacy_database_v27"
	}
}
