package com.adsamcik.tracker.impexp.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.adsamcik.tracker.impexp.exporter.DatabaseBackupScope
import com.adsamcik.tracker.shared.base.database.legacy.ACTIVE_DATABASE_NAME
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Stages one immutable archive and validates every declared data file before confirmation. */
class DataBackupArchiveReader(private val context: Context) {
    private val root get() = File(context.noBackupFilesDir, "data-restore").also {
        require(it.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile) { "Restore staging is outside app storage" }
    }

    suspend fun inspect(uri: Uri): DataBackupInspection {
        val source = context.contentResolver.openInputStream(uri) ?: return DataBackupInspection.Invalid
        val input = source.buffered()
        input.mark(4)
        val prefix = ByteArray(4)
        val count = input.read(prefix)
        input.reset()
        if (count != 4 || prefix[0] != 0x50.toByte() || prefix[1] != 0x4b.toByte()) {
            input.close()
            return DataBackupInspection.NotBackup
        }
        val token = UUID.randomUUID().toString()
        val directory = try { File(root, token) } catch (_: Exception) {
            input.close()
            return DataBackupInspection.Invalid
        }
        try {
            check(directory.mkdirs()) { "Restore staging is unavailable" }
            val archive = File(directory, "source.zip")
            input.use { stream -> archive.outputStream().use { copyBounded(stream, it, MAX_TOTAL_BYTES) } }
            return ZipFile(archive).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: return@use DataBackupInspection.NotBackup
                val bytes = zip.getInputStream(manifestEntry).use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    copyBounded(stream, output, MAX_MANIFEST_BYTES)
                    output.toByteArray()
                }
                if (!Regex("\"format\"\\s*:\\s*\"tracker-database-backup\"").containsMatchIn(bytes.toString(Charsets.UTF_8))) {
                    return@use DataBackupInspection.NotBackup
                }
                val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                if (json.get("format")?.asString != "tracker-database-backup") return@use DataBackupInspection.NotBackup
                val version = json.get("version")?.asInt ?: throw IOException("Backup version is missing")
                if (version !in 1..2 || version == 2 && json.get("scope")?.asString != "data") {
                    throw IOException("Backup format is unsupported")
                }
                val entries = (if (version == 1) json.getAsJsonArray("databases") else json.getAsJsonArray("files"))
                    ?: throw IOException("Backup file list is missing")
                val declared = entries.map { element ->
                    val entry = element.asJsonObject
                    val path = if (version == 1) "databases/${entry.get("file").asString}" else entry.get("path").asString
                    BackupFile(path, entry.get("sizeBytes").asLong, entry.get("sha256").asString)
                }
                if (declared.size > MAX_FILES || declared.map { it.path }.distinct().size != declared.size) {
                    throw IOException("Backup file list is invalid")
                }
                // V1 also exported settings and framework databases. They are deliberately not restored.
                val files = declared.filter { it.path in DatabaseBackupScope.allowedPaths }
                if (version == 2 && files.size != declared.size || files.none { it.path == "databases/$ACTIVE_DATABASE_NAME" ||
                        it.path == "databases/${com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME}" }) {
                    throw IOException("Backup has no supported Tracker database")
                }
                val zipNames = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
                if (zipNames.size > MAX_FILES + 1 || zipNames.distinct().size != zipNames.size ||
                    zipNames.toSet() != declared.map { it.path }.toSet() + "manifest.json") {
                    throw IOException("Backup entries do not match its manifest")
                }
                var remaining = MAX_TOTAL_BYTES
                files.forEach { file ->
                    if (file.size !in 0..remaining || !SHA256.matches(file.sha256)) throw IOException("Backup size or checksum is invalid")
                    val entry = zip.getEntry(file.path) ?: throw IOException("Backup data is missing")
                    val destination = File(directory, file.path)
                    check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val copied = zip.getInputStream(entry).use { stream ->
                        destination.outputStream().use { output -> copyBounded(stream, output, file.size, digest) }
                    }
                    if (copied != file.size || digest.digest().hex() != file.sha256) throw IOException("Backup data is incomplete")
                    remaining -= copied
                }
                File(directory, "manifest.json").writeBytes(bytes)
                archive.delete()
                val trackerCopy = File(directory, "databases/$ACTIVE_DATABASE_NAME")
                if (!trackerCopy.isFile) {
                    val database = com.adsamcik.tracker.shared.base.database.AppDatabase.releasedBackupCopyBuilder(context, trackerCopy.path,
                        File(directory, "databases/${com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME}")).build()
                    try {
                        database.openHelper.writableDatabase
                        database.sourceEvidenceStateDao().ensure()
                    } finally { database.close() }
                }
                SQLiteDatabase.openDatabase(trackerCopy.path,
                    null, SQLiteDatabase.OPEN_READONLY).use { database ->
                    database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                        if (!cursor.moveToFirst() || cursor.getString(0) != "ok") throw IOException("Backup database is damaged")
                    }
                }
                DataBackupInspection.Ready(DataBackupPreview(token))
            }.also { if (it !is DataBackupInspection.Ready) deleteOwnedTree(directory, root) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            try { input.close() } catch (cleanup: Exception) { cancelled.addSuppressed(cleanup) }
            try { deleteOwnedTree(directory, root) } catch (cleanup: Exception) { cancelled.addSuppressed(cleanup) }
            throw cancelled
        } catch (_: Exception) {
            input.close()
            deleteOwnedTree(directory, root)
            return DataBackupInspection.Invalid
        }
    }

    fun directory(token: String): File {
        require(UUID.fromString(token).toString() == token)
        return File(root, token).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    }

    fun discard(token: String) { deleteOwnedTree(directory(token), root) }

    fun clearAllOwned() { deleteOwnedTree(root, context.noBackupFilesDir) }

    companion object {
        const val MAX_TOTAL_BYTES = 16L * 1024L * 1024L * 1024L
        private const val MAX_MANIFEST_BYTES = 64L * 1024L
        private const val MAX_FILES = 32
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

private fun deleteOwnedTree(file: File, boundary: File) {
    val target = file.canonicalFile
    val owner = boundary.canonicalFile
    require(target.toPath().startsWith(owner.toPath()) && target != owner) { "Restore cleanup target is not app owned" }
    if (!file.exists()) return
    if (file.isDirectory) (file.listFiles() ?: throw IOException("Restore files cannot be listed")).forEach {
        deleteOwnedTree(it, boundary)
    }
    if (!file.delete()) throw IOException("Restore files could not be removed")
}

private data class BackupFile(val path: String, val size: Long, val sha256: String)

private suspend fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream,
    limit: Long, digest: MessageDigest? = null): Long {
    var size = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) return size
        size = Math.addExact(size, count.toLong())
        if (size > limit) throw IOException("Backup exceeds available import bounds")
        digest?.update(buffer, 0, count)
        output.write(buffer, 0, count)
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
