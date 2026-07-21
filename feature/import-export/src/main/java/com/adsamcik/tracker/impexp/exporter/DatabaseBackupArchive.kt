package com.adsamcik.tracker.impexp.exporter

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DatabaseBackupArchive {
	private const val DATABASE_DIRECTORY = "databases/"
	private const val MANIFEST_FILE = "manifest.json"

	fun write(
		databaseFiles: List<File>,
		outputStream: OutputStream,
		copyLockProvider: DatabaseCopyLockProvider,
	) {
		ZipOutputStream(outputStream).use { archive ->
			val entries = databaseFiles
				.sortedBy { it.name }
				.map { file ->
					copyLockProvider.lock(file).use {
						writeDatabaseEntry(archive, file)
					}
				}
			writeManifest(archive, entries)
		}
	}

	private fun writeDatabaseEntry(archive: ZipOutputStream, file: File): DatabaseBackupEntry {
		val checksum = MessageDigest.getInstance("SHA-256")
		archive.putNextEntry(ZipEntry("$DATABASE_DIRECTORY${file.name}"))
		file.inputStream().buffered().use { input ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				checksum.update(buffer, 0, count)
				archive.write(buffer, 0, count)
			}
		}
		archive.closeEntry()
		return DatabaseBackupEntry(
			fileName = file.name,
			sizeBytes = file.length(),
			sha256 = checksum.digest().joinToString("") { "%02x".format(it) },
		)
	}

	private fun writeManifest(archive: ZipOutputStream, entries: List<DatabaseBackupEntry>) {
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
		archive.write(manifest.toByteArray(Charsets.UTF_8))
		archive.closeEntry()
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
