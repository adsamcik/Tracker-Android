package com.adsamcik.tracker.shared.base.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SQLiteRuntimeTelemetryTest {

	@TempDir
	lateinit var temporaryDirectory: Path

	@Test
	fun `collect records runtime engine pragmas file sizes and passive checkpoint state`() {
		val databaseFile = databaseFile(databaseBytes = 11, walBytes = 17)
		val database = runtimeDatabase(journalMode = "wal")

		val snapshot = SQLiteRuntimeTelemetry.collect(database, databaseFile)

		snapshot.sqliteVersion shouldBe "3.51.3"
		snapshot.sqliteSourceId shouldBe "2026-03-13 source-id"
		snapshot.journalMode shouldBe "wal"
		snapshot.synchronous shouldBe "2"
		snapshot.walAutoCheckpoint shouldBe "1000"
		snapshot.databaseBytes shouldBe 11L
		snapshot.walBytes shouldBe 17L
		snapshot.checkpoint shouldBe SQLiteRuntimeTelemetry.Checkpoint(
			busy = 1,
			walFrames = 24,
			checkpointedFrames = 12,
		)
		snapshot.unavailable shouldBe emptyList<String>()
	}

	@Test
	fun `collect skips checkpoint probe when WAL is not active`() {
		val database = runtimeDatabase(journalMode = "delete")

		val snapshot = SQLiteRuntimeTelemetry.collect(database, databaseFile())

		snapshot.checkpoint shouldBe null
		verify(exactly = 0) { database.query("PRAGMA wal_checkpoint(PASSIVE)") }
	}

	@Test
	fun `open event never exposes database path`() {
		val databaseFile = databaseFile(databaseBytes = 7, walBytes = 9)
		val messages = mutableListOf<String>()

		SQLiteRuntimeTelemetry.recordOnOpen(
			database = runtimeDatabase(journalMode = "wal"),
			databaseType = "AppDatabase",
			databaseFile = databaseFile,
			emit = messages::add,
		)

		messages.single() shouldContain "event=sqlite_runtime database=AppDatabase"
		messages.single() shouldContain "wal_checkpoint_busy=1"
		messages.single() shouldNotContain databaseFile.path
	}

	@Test
	fun `unavailable diagnostics are reported without failing database open`() {
		val database = mockk<SupportSQLiteDatabase>()
		every { database.query(any<String>()) } throws IllegalStateException("not available")
		val messages = mutableListOf<String>()

		SQLiteRuntimeTelemetry.recordOnOpen(
			database = database,
			databaseType = "AppDatabase",
			databaseFile = databaseFile(),
			emit = messages::add,
		)

		messages.single() shouldContain "unavailable=journal_mode,sqlite_version,sqlite_source_id,synchronous,wal_autocheckpoint"
	}

	@Test
	fun `diagnostic sink failure never escapes database open`() {
		SQLiteRuntimeTelemetry.recordOnOpen(
			database = runtimeDatabase(journalMode = "wal"),
			databaseType = "AppDatabase",
			databaseFile = databaseFile(),
			emit = { error("diagnostic sink unavailable") },
		)
	}

	private fun runtimeDatabase(journalMode: String): SupportSQLiteDatabase {
		return mockk {
			every { query("PRAGMA journal_mode") } returns cursorOf(journalMode)
			every { query("SELECT sqlite_version()") } returns cursorOf("3.51.3")
			every { query("SELECT sqlite_source_id()") } returns cursorOf("2026-03-13 source-id")
			every { query("PRAGMA synchronous") } returns cursorOf("2")
			every { query("PRAGMA wal_autocheckpoint") } returns cursorOf("1000")
			every { query("PRAGMA wal_checkpoint(PASSIVE)") } returns cursorOf(1L, 24L, 12L)
		}
	}

	private fun cursorOf(vararg values: Any?): Cursor {
		return mockk {
			every { moveToFirst() } returns true
			every { columnCount } returns values.size
			every { isNull(any<Int>()) } answers { values[firstArg<Int>()] == null }
			every { getString(any()) } answers { values[firstArg<Int>()].toString() }
			every { getLong(any()) } answers { (values[firstArg<Int>()] as Number).toLong() }
			every { close() } returns Unit
		}
	}

	private fun databaseFile(databaseBytes: Int = 0, walBytes: Int = 0): File {
		return temporaryDirectory.resolve("main_database").toFile().apply {
			writeBytes(ByteArray(databaseBytes))
			File("$path-wal").writeBytes(ByteArray(walBytes))
		}
	}
}
