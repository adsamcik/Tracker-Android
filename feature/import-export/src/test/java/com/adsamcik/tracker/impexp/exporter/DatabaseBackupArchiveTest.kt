package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseBackupArchiveTest {
	@Test
	fun `writes every application database and records its checksum in manifest`() {
		val directory = File("build/database-backup-archive-test").apply {
			deleteRecursively()
			mkdirs()
		}
		val databases = mapOf(
			"main_database" to "main content".toByteArray(),
			"preference_database" to "preferences".toByteArray(),
			"points_database" to "points".toByteArray(),
		)
		databases.forEach { (name, content) -> File(directory, name).writeBytes(content) }

		val output = ByteArrayOutputStream()
		DatabaseBackupArchive.write(
			directory.listFiles()!!.toList(),
			output,
			DatabaseCopyLockProvider { Closeable {} },
		)

		val archiveEntries = unzip(output.toByteArray())
		archiveEntries.keys shouldContainExactlyInAnyOrder listOf(
			"databases/main_database",
			"databases/preference_database",
			"databases/points_database",
			"manifest.json",
		)
		databases.forEach { (name, content) ->
			archiveEntries["databases/$name"] shouldBe content
			val checksum = MessageDigest.getInstance("SHA-256")
				.digest(content)
				.joinToString("") { "%02x".format(it) }
			archiveEntries.getValue("manifest.json").decodeToString()
				.contains(""""file":"$name","sizeBytes":${content.size},"sha256":"$checksum"""") shouldBe true
		}
	}

	@Test
	fun `raw database copy lock blocks a concurrent writer until archive entry finishes`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val databaseName = "database-backup-lock-test.db"
		context.deleteDatabase(databaseName)
		val databaseFile = context.getDatabasePath(databaseName)
		databaseFile.parentFile?.mkdirs()
		SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
			database.enableWriteAheadLogging()
			database.execSQL("CREATE TABLE sample (`id` INTEGER PRIMARY KEY, `value` TEXT NOT NULL)")
			database.execSQL("INSERT INTO sample (`id`, `value`) VALUES (1, 'before-copy')")
		}

		val copyStarted = CountDownLatch(1)
		val allowCopyToFinish = CountDownLatch(1)
		val writerAttempted = CountDownLatch(1)
		val writerFinished = CountDownLatch(1)
		val archiveFailure = AtomicReference<Throwable>()
		val writerFailure = AtomicReference<Throwable>()
		val output = PausingOutputStream(copyStarted, allowCopyToFinish)
		val writerDatabase = SQLiteDatabase.openDatabase(
			databaseFile.path,
			null,
			SQLiteDatabase.OPEN_READWRITE,
		)

		val archiveThread = thread(name = "database-archive-copy") {
			try {
				DatabaseBackupArchive.write(
					listOf(databaseFile),
					output,
					SQLiteDatabaseCopyLockProvider,
				)
			} catch (exception: Throwable) {
				archiveFailure.set(exception)
			}
		}
		val writerThread = thread(name = "database-backup-concurrent-writer") {
			try {
				copyStarted.await(5, TimeUnit.SECONDS) shouldBe true
				writerAttempted.countDown()
				writerDatabase.execSQL(
					"INSERT INTO sample (`id`, `value`) VALUES (2, 'during-copy')",
				)
			} catch (exception: Throwable) {
				writerFailure.set(exception)
			} finally {
				writerFinished.countDown()
			}
		}

		try {
			copyStarted.await(5, TimeUnit.SECONDS) shouldBe true
			writerAttempted.await(5, TimeUnit.SECONDS) shouldBe true
			writerFinished.await(300, TimeUnit.MILLISECONDS) shouldBe false
		} finally {
			allowCopyToFinish.countDown()
			archiveThread.join(5_000)
			writerThread.join(5_000)
			writerDatabase.close()
		}

		archiveThread.isAlive shouldBe false
		writerThread.isAlive shouldBe false
		archiveFailure.get().shouldBeNull()
		writerFailure.get().shouldBeNull()
		val archivedDatabase = File(context.cacheDir, "archived-$databaseName").apply {
			writeBytes(unzip(output.toByteArray()).getValue("databases/$databaseName"))
		}
		SQLiteDatabase.openDatabase(
			archivedDatabase.path,
			null,
			SQLiteDatabase.OPEN_READONLY,
		).use { database ->
			database.rawQuery("SELECT COUNT(*) FROM sample", null).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getInt(0) shouldBe 1
			}
		}
		archivedDatabase.delete()
		SQLiteDatabase.openDatabase(
			databaseFile.path,
			null,
			SQLiteDatabase.OPEN_READONLY,
		).use { database ->
			database.rawQuery("SELECT COUNT(*) FROM sample", null).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getInt(0) shouldBe 2
			}
		}
		context.deleteDatabase(databaseName)
	}

	private fun unzip(bytes: ByteArray): Map<String, ByteArray> =
		buildMap {
			ZipInputStream(bytes.inputStream()).use { archive ->
				while (true) {
					val entry = archive.nextEntry ?: break
					put(entry.name, archive.readBytes())
					archive.closeEntry()
				}
			}
		}
}

private class PausingOutputStream(
		private val copyStarted: CountDownLatch,
		private val allowCopyToFinish: CountDownLatch,
) : ByteArrayOutputStream() {
	private val paused = AtomicBoolean()

	override fun write(buffer: ByteArray, offset: Int, length: Int) {
		pauseOnce()
		super.write(buffer, offset, length)
	}

	override fun write(value: Int) {
		pauseOnce()
		super.write(value)
	}

	private fun pauseOnce() {
		if (paused.compareAndSet(false, true)) {
			copyStarted.countDown()
			check(allowCopyToFinish.await(5, TimeUnit.SECONDS)) {
				"Timed out waiting to finish database copy"
			}
		}
	}
}
