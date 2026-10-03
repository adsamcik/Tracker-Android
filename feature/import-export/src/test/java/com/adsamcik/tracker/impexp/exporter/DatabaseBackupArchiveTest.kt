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
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseBackupArchiveTest {
	@Test
	fun `writes every application database and records its checksum in manifest`() = runTest {
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
				runBlocking {
					DatabaseBackupArchive.write(
						listOf(databaseFile),
						output,
						SQLiteDatabaseCopyLockProvider,
					)
				}
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

	@Test
	fun `cancelled backup refuses before acquiring any database lock`() = runTest {
		withBackupFiles { files ->
			var lockCount = 0
			var cancellation: CancellationException? = null
			val output = ByteArrayOutputStream()
			val transfer = launch(start = CoroutineStart.UNDISPATCHED) {
				currentCoroutineContext().cancel()
				try {
					DatabaseBackupArchive.write(files, output,
						DatabaseCopyLockProvider { lockCount++; Closeable {} })
				} catch (cancelled: CancellationException) {
					cancellation = cancelled
				}
			}
			transfer.join()
			(cancellation != null) shouldBe true
			lockCount shouldBe 0
			output.size() shouldBe 0
		}
	}

	@Test
	fun `cancellation during lock acquisition releases that lock without opening a sibling`() = runTest {
		withBackupFiles { files ->
			val acquired = mutableListOf<String>()
			val closed = mutableListOf<String>()
			val cleanupFailure = IOException("Lock release failed")
			var cancellation: CancellationException? = null
			val transfer = launch(start = CoroutineStart.UNDISPATCHED) {
				val caller = currentCoroutineContext()
				try {
					DatabaseBackupArchive.write(files, ByteArrayOutputStream(), DatabaseCopyLockProvider { file ->
						acquired += file.name
						caller.cancel()
						Closeable { closed += file.name; throw cleanupFailure }
					})
				} catch (cancelled: CancellationException) {
					cancellation = cancelled
				}
			}
			transfer.join()
			acquired shouldBe listOf(files.first().name)
			closed shouldBe acquired
			requireNotNull(cancellation).suppressed.toList() shouldBe listOf(cleanupFailure)
		}
	}

	@Test
	fun `cancellation during copied data closes output and lock before another file or manifest`() = runTest {
		withBackupFiles { files ->
			val original = files.first().readBytes()
			val acquired = mutableListOf<String>()
			val closed = mutableListOf<String>()
			var cancellation: CancellationException? = null
			lateinit var output: CancellingBackupOutputStream
			val transfer = launch(start = CoroutineStart.UNDISPATCHED) {
				val caller = currentCoroutineContext()
				output = CancellingBackupOutputStream { caller.cancel() }
				try {
					DatabaseBackupArchive.write(files, output, DatabaseCopyLockProvider { file ->
						acquired += file.name
						Closeable { closed += file.name }
					})
				} catch (cancelled: CancellationException) {
					cancellation = cancelled
				}
			}
			transfer.join()
			(cancellation != null) shouldBe true
			output.closed shouldBe true
			acquired shouldBe listOf(files.first().name)
			closed shouldBe acquired
			val partial = unzip(output.toByteArray())
			partial.keys shouldBe setOf("databases/${files.first().name}")
			(partial.getValue("databases/${files.first().name}").size in 1 until original.size) shouldBe true
			files.first().readBytes() shouldBe original
			files.last().readText() shouldBe "untouched sibling"
		}
	}

	@Test
	fun `copy failure stays primary when its database lock also fails to close`() = runTest {
		withBackupFiles { files ->
			val copyFailure = IOException("Destination write failed")
			val cleanupFailure = IOException("Lock release failed")
			val closed = mutableListOf<String>()
			val output = object : ByteArrayOutputStream() {
				private var failed = false
				override fun write(buffer: ByteArray, offset: Int, length: Int) {
					if (!failed) { failed = true; throw copyFailure }
					super.write(buffer, offset, length)
				}
			}
			val failure = assertFailsWith<IOException> {
				DatabaseBackupArchive.write(files, output, DatabaseCopyLockProvider { file ->
					Closeable { closed += file.name; throw cleanupFailure }
				})
			}
			(failure === copyFailure) shouldBe true
			failure.suppressed.toList() shouldBe listOf(cleanupFailure)
			closed shouldBe listOf(files.first().name)
		}
	}

	@Test
	fun `database exporter propagates copy cancellation and releases the real SQLite lock`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val name = "main_database_v27"
		context.deleteDatabase(name)
		val file = context.getDatabasePath(name)
		file.parentFile?.mkdirs()
		SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
			database.execSQL("CREATE TABLE saved (`id` INTEGER PRIMARY KEY, `value` BLOB NOT NULL)")
			database.execSQL("INSERT INTO saved (`id`, `value`) VALUES (1, randomblob(524288))")
		}
		try {
			var cancellation: CancellationException? = null
			var returned: ExportResult? = null
			lateinit var output: CancellingBackupOutputStream
			val transfer = launch(start = CoroutineStart.UNDISPATCHED) {
				val caller = currentCoroutineContext()
				output = CancellingBackupOutputStream { caller.cancel() }
				try {
					returned = DatabaseExporter().export(context, emptySequence(), output, null)
				} catch (cancelled: CancellationException) {
					cancellation = cancelled
				}
			}
			transfer.join()
			(cancellation != null) shouldBe true
			returned.shouldBeNull()
			output.closed shouldBe true
			SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
				database.execSQL("INSERT INTO saved (`id`, `value`) VALUES (2, X'01')")
				database.rawQuery("SELECT COUNT(*) FROM saved", null).use { cursor ->
					cursor.moveToFirst() shouldBe true
					cursor.getInt(0) shouldBe 2
				}
			}
		} finally {
			context.deleteDatabase(name)
		}
	}

	@Test
	fun `database exporter preserves cancellation and error from final output closure`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val name = "main_database_v27"
		context.deleteDatabase(name)
		val file = context.getDatabasePath(name)
		file.parentFile?.mkdirs()
		SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
			database.execSQL("CREATE TABLE saved (`id` INTEGER PRIMARY KEY)")
			database.execSQL("INSERT INTO saved (`id`) VALUES (1)")
		}
		try {
			val closeFailure = IOException("Destination close failed")
			var closeCount = 0
			var cancellation: CancellationException? = null
			var returned: ExportResult? = null
			val transfer = launch(start = CoroutineStart.UNDISPATCHED) {
				val caller = currentCoroutineContext()
				val output = object : ByteArrayOutputStream() {
					override fun close() {
						closeCount++
						caller.cancel()
						throw closeFailure
					}
				}
				try {
					returned = DatabaseExporter().export(context, emptySequence(), output, null)
				} catch (cancelled: CancellationException) {
					cancellation = cancelled
				}
			}
			transfer.join()
			returned.shouldBeNull()
			closeCount shouldBe 1
			requireNotNull(cancellation).suppressed.toList() shouldBe listOf(closeFailure)
			SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
				database.execSQL("INSERT INTO saved (`id`) VALUES (2)")
				database.rawQuery("SELECT COUNT(*) FROM saved", null).use { cursor ->
					cursor.moveToFirst() shouldBe true
					cursor.getInt(0) shouldBe 2
				}
			}
		} finally {
			context.deleteDatabase(name)
		}
	}

	@Test
	fun `cancellation from failing lock release escapes after physical output cleanup`() = runTest {
		withBackupFiles { files ->
			val closeFailure = IOException("Database lock close failed")
			val acquired = mutableListOf<String>()
			val closed = mutableListOf<String>()
			var outputClosed = false
			var cancellation: CancellationException? = null
			val transfer = launch(start = CoroutineStart.UNDISPATCHED) {
				val caller = currentCoroutineContext()
				val output = object : ByteArrayOutputStream() {
					override fun close() { outputClosed = true; super.close() }
				}
				try {
					DatabaseBackupArchive.write(files, output, DatabaseCopyLockProvider { file ->
						acquired += file.name
						Closeable {
							closed += file.name
							caller.cancel()
							throw closeFailure
						}
					})
				} catch (cancelled: CancellationException) {
					cancellation = cancelled
				}
			}
			transfer.join()
			outputClosed shouldBe true
			acquired shouldBe listOf(files.first().name)
			closed shouldBe acquired
			requireNotNull(cancellation).suppressed.toList() shouldBe listOf(closeFailure)
		}
	}

	@Test
	fun `active caller keeps final output IOException without cancellation conversion`() = runTest {
		withBackupFiles { files ->
			val closeFailure = IOException("Destination close failed")
			val closed = mutableListOf<String>()
			var closeCount = 0
			val output = object : ByteArrayOutputStream() {
				override fun close() { closeCount++; throw closeFailure }
			}
			val failure = assertFailsWith<IOException> {
				DatabaseBackupArchive.write(files, output, DatabaseCopyLockProvider { file ->
					Closeable { closed += file.name }
				})
			}
			(failure === closeFailure) shouldBe true
			failure.suppressed.toList() shouldBe emptyList()
			closeCount shouldBe 1
			closed shouldBe files.map { it.name }
		}
	}

	private suspend fun withBackupFiles(block: suspend (List<File>) -> Unit) {
		val root = File("build/database-backup-cancellation-test").absoluteFile.apply { mkdirs() }
		val directory = Files.createTempDirectory(root.toPath(), "case-").toFile()
		val data = ByteArray(512 * 1024).also { java.util.Random(0L).nextBytes(it) }
		val files = listOf(
			File(directory, "main_database_v27").apply { writeBytes(data) },
			File(directory, "points_database").apply { writeText("untouched sibling") },
		)
		try { block(files) }
		finally { files.forEach { it.delete() }; directory.delete() }
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

private class CancellingBackupOutputStream(private val cancel: () -> Unit) : ByteArrayOutputStream() {
	private var cancelled = false
	var closed = false
		private set

	override fun write(buffer: ByteArray, offset: Int, length: Int) {
		super.write(buffer, offset, length)
		cancelAfterData()
	}

	override fun write(value: Int) {
		super.write(value)
		cancelAfterData()
	}

	private fun cancelAfterData() {
		if (!cancelled && size() > 128) { cancelled = true; cancel() }
	}

	override fun close() {
		closed = true
		super.close()
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
