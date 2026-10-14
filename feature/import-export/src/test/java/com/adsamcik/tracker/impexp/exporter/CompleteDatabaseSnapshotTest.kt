package com.adsamcik.tracker.impexp.exporter

import android.database.sqlite.SQLiteDatabase
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompleteDatabaseSnapshotTest {
    @Test
    fun `one backup cannot combine an earlier main database with later points data`() = runTest {
        val directory = Files.createTempDirectory("complete-backup-snapshot").toFile()
        val main = File(directory, "main")
        val points = File(directory, "points")
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        for (file in listOf(main, points)) SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE saved(id INTEGER PRIMARY KEY)")
            it.execSQL("INSERT INTO saved VALUES(1)")
        }
        val writer = thread(start = false) {
            started.countDown()
            try {
                for (file in listOf(points, main)) SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("INSERT INTO saved VALUES(2)")
                }
            } catch (problem: Throwable) { failure.set(problem) }
            finally { finished.countDown() }
        }
        val launched = AtomicBoolean(false)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (launched.compareAndSet(false, true)) {
                    writer.start()
                    started.await(5, TimeUnit.SECONDS) shouldBe true
                    finished.await(100, TimeUnit.MILLISECONDS) shouldBe false
                }
                super.write(bytes, offset, length)
            }
            override fun write(value: Int) {
                if (launched.compareAndSet(false, true)) {
                    writer.start()
                    started.await(5, TimeUnit.SECONDS) shouldBe true
                    finished.await(100, TimeUnit.MILLISECONDS) shouldBe false
                }
                super.write(value)
            }
        }
        try {
            DatabaseBackupArchive.writeData(listOf(
                DatabaseBackupSource("databases/main", main, true),
                DatabaseBackupSource("databases/points", points, true)), output, SQLiteDatabaseCopyLockProvider)
            finished.await(5, TimeUnit.SECONDS) shouldBe true
            failure.get()?.let { throw it }
            val snapshots = mutableMapOf<String, ByteArray>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.startsWith("databases/")) snapshots[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
            snapshots.forEach { (name, bytes) ->
                val file = File(directory, "copy-${name.substringAfterLast('/')}")
                file.writeBytes(bytes)
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                    database.rawQuery("SELECT COUNT(*) FROM saved", null).use { cursor ->
                        cursor.moveToFirst(); cursor.getLong(0)
                    } shouldBe 1L
                }
            }
        } finally {
            if (writer.isAlive) writer.join(5_000L)
            directory.deleteRecursively()
        }
    }
}
