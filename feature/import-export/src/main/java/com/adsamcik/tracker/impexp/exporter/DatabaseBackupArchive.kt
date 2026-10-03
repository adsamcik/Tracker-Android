package com.adsamcik.tracker.impexp.exporter

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object DatabaseBackupArchive {
	private const val DATABASE_DIRECTORY = "databases/"
	private const val MANIFEST_FILE = "manifest.json"

	suspend fun write(
		databaseFiles: List<File>,
		outputStream: OutputStream,
		copyLockProvider: DatabaseCopyLockProvider,
	) {
		val caller = currentCoroutineContext()
		caller.ensureActive()
		try {
			ZipOutputStream(outputStream).use { archive ->
				val entries = databaseFiles
					.sortedBy { it.name }
					.map { file ->
						caller.ensureActive()
						copyLockProvider.lock(file).use {
							caller.ensureActive()
							writeDatabaseEntry(archive, file, caller)
						}
					}
				writeManifest(archive, entries, caller)
			}
			caller.ensureActive()
		} catch (error: Throwable) {
			if (error !is CancellationException) {
				try {
					caller.ensureActive()
				} catch (cancelled: CancellationException) {
					cancelled.addSuppressed(error)
					throw cancelled
				}
			}
			throw error
		}
	}

	private fun writeDatabaseEntry(
		archive: ZipOutputStream,
		file: File,
		caller: CoroutineContext,
	): DatabaseBackupEntry {
		caller.ensureActive()
		val checksum = MessageDigest.getInstance("SHA-256")
		archive.putNextEntry(ZipEntry("$DATABASE_DIRECTORY${file.name}"))
		caller.ensureActive()
		file.inputStream().buffered().use { input ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				caller.ensureActive()
				val count = input.read(buffer)
				caller.ensureActive()
				if (count < 0) break
				checksum.update(buffer, 0, count)
				archive.write(buffer, 0, count)
				caller.ensureActive()
			}
		}
		archive.closeEntry()
		caller.ensureActive()
		return DatabaseBackupEntry(
			fileName = file.name,
			sizeBytes = file.length(),
			sha256 = checksum.digest().joinToString("") { "%02x".format(it) },
		)
	}

	private fun writeManifest(
		archive: ZipOutputStream,
		entries: List<DatabaseBackupEntry>,
		caller: CoroutineContext,
	) {
		caller.ensureActive()
		val manifest = buildString {
			append("{\"format\":\"tracker-database-backup\",\"version\":1,\"databases\":[")
			entries.forEachIndexed { index, entry ->
				if (index > 0) append(',')
				append(
					"""{"file":"${escapeJson(entry.fileName)}","sizeBytes":${entry.sizeBytes},"sha256":"${entry.sha256}"}"""
				)
			}
			append("]}")
		}
		archive.putNextEntry(ZipEntry(MANIFEST_FILE))
		caller.ensureActive()
		archive.write(manifest.toByteArray(Charsets.UTF_8))
		caller.ensureActive()
		archive.closeEntry()
		caller.ensureActive()
	}

	private fun escapeJson(value: String): String =
		value.replace("\\", "\\\\").replace("\"", "\\\"")
}

internal fun interface DatabaseCopyLockProvider {
	fun lock(databaseFile: File): Closeable
}

internal data class DatabaseBackupEntry(
	val fileName: String,
	val sizeBytes: Long,
	val sha256: String,
)
