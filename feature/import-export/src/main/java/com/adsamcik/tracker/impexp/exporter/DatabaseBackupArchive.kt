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

	/** V2 explicitly declares the data-only scope and optional app-owned imported data files. */
	suspend fun writeData(sources: List<DatabaseBackupSource>, outputStream: OutputStream,
		copyLockProvider: DatabaseCopyLockProvider) {
		val caller = currentCoroutineContext()
		try {
		ZipOutputStream(outputStream).use { archive ->
			val databaseSources = sources.filter { it.requiresDatabaseLock }.sortedBy { it.archivePath }
			val databaseEntries = withDatabaseCopyLocks(databaseSources, copyLockProvider) {
				databaseSources.map { source ->
					caller.ensureActive()
					writeDatabaseEntry(archive, source.file, caller, source.archivePath).copy(fileName = source.archivePath)
				}
			}
			// The entire database set represents the instant after the last lock was acquired.
			// Release those locks before large immutable map/imported-file copies.
			val entries = databaseEntries + sources.filterNot { it.requiresDatabaseLock }.sortedBy { it.archivePath }.map { source ->
				caller.ensureActive()
				writeDatabaseEntry(archive, source.file, caller, source.archivePath).copy(fileName = source.archivePath)
			}
			val manifest = buildString {
				append("{\"format\":\"tracker-database-backup\",\"version\":2,\"scope\":\"data\",\"files\":[")
				entries.forEachIndexed { index, entry ->
					if (index > 0) append(',')
					append("{\"path\":\"${escapeJson(entry.fileName)}\",\"sizeBytes\":${entry.sizeBytes},\"sha256\":\"${entry.sha256}\"}")
				}
				append("]}")
			}
			archive.putNextEntry(ZipEntry(MANIFEST_FILE))
			caller.ensureActive()
			archive.write(manifest.toByteArray(Charsets.UTF_8))
			archive.closeEntry()
		}
		caller.ensureActive()
		} catch (failure: Throwable) {
			if (failure !is CancellationException) {
				try { caller.ensureActive() } catch (cancelled: CancellationException) {
					cancelled.addSuppressed(failure)
					throw cancelled
				}
			}
			throw failure
		}
	}

	private suspend fun <T> withDatabaseCopyLocks(sources: List<DatabaseBackupSource>, provider: DatabaseCopyLockProvider,
		operation: suspend () -> T): T {
		val locks = mutableListOf<Closeable>()
		var primary: Throwable? = null
		try {
			for (source in sources) {
				currentCoroutineContext().ensureActive()
				locks += provider.lock(source.file)
			}
			return operation()
		} catch (failure: Throwable) { primary = failure; throw failure }
		finally {
			var cleanup: Throwable? = null
			locks.asReversed().forEach { lock ->
				try { lock.close() } catch (failure: Throwable) {
					val original = primary
					if (original != null) original.addSuppressed(failure)
					else if (cleanup == null) cleanup = failure else cleanup!!.addSuppressed(failure)
				}
			}
			if (primary == null && cleanup != null) throw cleanup!!
		}
	}

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
		archivePath: String = "$DATABASE_DIRECTORY${file.name}",
	): DatabaseBackupEntry {
		caller.ensureActive()
		val checksum = MessageDigest.getInstance("SHA-256")
		var sizeBytes = 0L
		archive.putNextEntry(ZipEntry(archivePath))
		caller.ensureActive()
		file.inputStream().buffered().use { input ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				caller.ensureActive()
				val count = input.read(buffer)
				caller.ensureActive()
				if (count < 0) break
				checksum.update(buffer, 0, count)
				sizeBytes = Math.addExact(sizeBytes, count.toLong())
				archive.write(buffer, 0, count)
				caller.ensureActive()
			}
		}
		archive.closeEntry()
		caller.ensureActive()
		return DatabaseBackupEntry(
			fileName = if (archivePath.startsWith(DATABASE_DIRECTORY) && archivePath == "$DATABASE_DIRECTORY${file.name}") file.name else archivePath,
			sizeBytes = sizeBytes,
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
