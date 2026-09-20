package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

internal data class FileBackedExportSpoolLimits(
	val maximumFileCount: Int = 8,
	val maximumTotalBytes: Long = 256L * 1_024L * 1_024L,
	val maximumAgeMs: Long = 24L * 60L * 60L * 1_000L,
) {
	init {
		require(maximumFileCount > 0)
		require(maximumTotalBytes > 0L)
		require(maximumAgeMs > 0L)
	}
}

/** Bounded cache-backed staging whose committed contents alone reach the caller destination. */
internal class FileBackedExportSpool(
	context: Context,
	prefix: String,
	private val maximumBytes: Long,
	private val limits: FileBackedExportSpoolLimits = FileBackedExportSpoolLimits(),
	private val nowMs: () -> Long = System::currentTimeMillis,
	private val uniqueId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) : Closeable {
	internal val backingFile: File
	private var writtenBytes = 0L
	private var writeCompleted = false
	private var closed = false

	init {
		require(PURPOSE_PREFIX.matches(prefix))
		require(maximumBytes in 1..limits.maximumTotalBytes)
		val cacheDirectory = context.cacheDir.canonicalFile
		val requestedDirectory = File(cacheDirectory, DIRECTORY_NAME)
		if (!requestedDirectory.isDirectory && !requestedDirectory.mkdirs()) {
			throw IOException("Unable to create portable export spool directory")
		}
		val directory = requestedDirectory.canonicalFile
		if (directory.parentFile != cacheDirectory || !directory.isDirectory) {
			throw IOException("Portable export spool directory is not owned cache storage")
		}
		val createdAtMs = nowMs()
		if (createdAtMs < 0L) throw IOException("Portable export spool clock is invalid")
		backingFile = synchronized(ACTIVE_LOCK) {
			sweepAndReserve(directory, maximumBytes, limits, createdAtMs)
			val name = OWNED_FILE_PREFIX +
				prefix.removeSuffix("-") +
				"-$createdAtMs-${uniqueId()}$FILE_SUFFIX"
			if (!OWNED_FILE.matches(name)) {
				throw IOException("Portable export spool identity is invalid")
			}
			val candidate = File(directory, name)
			if (candidate.canonicalFile.parentFile != directory || !candidate.createNewFile()) {
				throw IOException("Unable to create portable export spool file")
			}
			ACTIVE_FILES[candidate.canonicalPath] = ActiveSpool(directory.canonicalPath, maximumBytes)
			candidate
		}
	}

	internal val byteCount: Long
		get() = writtenBytes

	suspend fun <T> write(block: suspend (OutputStream) -> T): T {
		check(!closed)
		check(!writeCompleted)
		val result = FileOutputStream(backingFile).buffered().use { output ->
			block(BoundedSpoolOutputStream(output))
		}
		writeCompleted = true
		return result
	}

	fun copyTo(destination: OutputStream) {
		check(!closed)
		check(writeCompleted)
		check(backingFile.length() == writtenBytes)
		backingFile.inputStream().buffered().use { input ->
			val buffer = ByteArray(COPY_BUFFER_SIZE)
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				if (read == 0) continue
				destination.write(buffer, 0, read)
			}
		}
	}

	override fun close() {
		synchronized(ACTIVE_LOCK) {
			if (closed) return
			closed = true
			ACTIVE_FILES.remove(backingFile.canonicalPath)
			if (backingFile.exists() && !backingFile.delete()) {
				throw IOException("Unable to delete portable export spool file")
			}
		}
	}

	private inner class BoundedSpoolOutputStream(
		output: OutputStream,
	) : FilterOutputStream(output) {
		override fun write(value: Int) {
			claim(1)
			out.write(value)
		}

		override fun write(buffer: ByteArray, offset: Int, length: Int) {
			claim(length)
			out.write(buffer, offset, length)
		}

		private fun claim(count: Int) {
			val next = try {
				Math.addExact(writtenBytes, count.toLong())
			} catch (_: ArithmeticException) {
				throw IOException("Portable export spool byte count overflow")
			}
			if (next > maximumBytes) throw IOException("Portable export spool exceeds its byte bound")
			writtenBytes = next
		}
	}

	private data class ActiveSpool(
		val directoryPath: String,
		val reservedBytes: Long,
	)

	private companion object {
		const val DIRECTORY_NAME = "portable-export-spool"
		const val OWNED_FILE_PREFIX = "tracker-portable-export-spool-v1-"
		const val FILE_SUFFIX = ".tmp"
		const val COPY_BUFFER_SIZE = 8 * 1_024
		const val FUTURE_CLOCK_TOLERANCE_MS = 5L * 60L * 1_000L
		val PURPOSE_PREFIX = Regex("[a-z0-9]+(?:-[a-z0-9]+)*-")
		val OWNED_FILE = Regex(
			"tracker-portable-export-spool-v1-[a-z0-9]+(?:-[a-z0-9]+)*-" +
				"(\\d{1,19})-([0-9a-f]{32})\\.tmp",
		)
		val LEGACY_OWNED_FILE = Regex(
			"(?:portable-steps-v1-|portable-ambient-steps-v1-)\\d+\\.tmp",
		)
		val ACTIVE_LOCK = Any()
		val ACTIVE_FILES = mutableMapOf<String, ActiveSpool>()

		fun sweepAndReserve(
			directory: File,
			requestedBytes: Long,
			limits: FileBackedExportSpoolLimits,
			nowMs: Long,
		) {
			val active = ACTIVE_FILES.values.filter { it.directoryPath == directory.canonicalPath }
			if (active.size >= limits.maximumFileCount) {
				throw IOException("Portable export spool active-file bound is exhausted")
			}
			val activeBytes = active.fold(0L) { total, spool ->
				try {
					Math.addExact(total, spool.reservedBytes)
				} catch (_: ArithmeticException) {
					throw IOException("Portable export spool active-byte count overflow")
				}
			}
			if (activeBytes > limits.maximumTotalBytes - requestedBytes) {
				throw IOException("Portable export spool active-byte bound is exhausted")
			}
			val inactive = directory.listFiles()?.mapNotNull { candidate ->
				ownedInactiveFile(directory, candidate, nowMs, limits.maximumAgeMs)
			} ?: throw IOException("Unable to inspect portable export spool directory")
			val retained = inactive.filterNot(OwnedSpoolFile::expired).toMutableList()
			inactive.filter(OwnedSpoolFile::expired).forEach { deleteOwned(it.file) }
			retained.sortWith(compareBy(OwnedSpoolFile::createdAtMs).thenBy { it.file.name })
			var retainedBytes = retained.fold(0L) { total, owned ->
				try {
					Math.addExact(total, owned.sizeBytes)
				} catch (_: ArithmeticException) {
					throw IOException("Portable export spool byte count overflow")
				}
			}
			while (
				active.size + retained.size + 1 > limits.maximumFileCount ||
				retainedBytes > limits.maximumTotalBytes - activeBytes - requestedBytes
			) {
				if (retained.isEmpty()) {
					throw IOException("Portable export spool bounds cannot be satisfied")
				}
				val oldest = retained.removeAt(0)
				deleteOwned(oldest.file)
				retainedBytes -= oldest.sizeBytes
			}
		}

		fun ownedInactiveFile(
			directory: File,
			candidate: File,
			nowMs: Long,
			maximumAgeMs: Long,
		): OwnedSpoolFile? {
			val match = OWNED_FILE.matchEntire(candidate.name)
			if (match == null && !LEGACY_OWNED_FILE.matches(candidate.name)) return null
			val canonical = try {
				candidate.canonicalFile
			} catch (_: IOException) {
				return null
			}
			if (canonical.parentFile != directory || !canonical.isFile ||
				ACTIVE_FILES.containsKey(canonical.path)
			) return null
			val modifiedAtMs = canonical.lastModified()
			val createdAtMs = match?.groupValues?.get(1)?.toLongOrNull()
				?: modifiedAtMs.takeIf { it > 0L }
				?: return null
			val invalidFuture = (createdAtMs > nowMs &&
				createdAtMs - nowMs > FUTURE_CLOCK_TOLERANCE_MS ||
				modifiedAtMs > nowMs &&
				modifiedAtMs - nowMs > FUTURE_CLOCK_TOLERANCE_MS)
			val oldestTimestamp = minOf(createdAtMs, modifiedAtMs.takeIf { it > 0L } ?: createdAtMs)
			return OwnedSpoolFile(
				canonical,
				createdAtMs,
				canonical.length(),
				invalidFuture || nowMs - oldestTimestamp > maximumAgeMs,
			)
		}

		fun deleteOwned(file: File) {
			if (file.exists() && !file.delete()) {
				throw IOException("Unable to clean abandoned portable export spool file")
			}
		}
	}

	private data class OwnedSpoolFile(
		val file: File,
		val createdAtMs: Long,
		val sizeBytes: Long,
		val expired: Boolean,
	)
}
