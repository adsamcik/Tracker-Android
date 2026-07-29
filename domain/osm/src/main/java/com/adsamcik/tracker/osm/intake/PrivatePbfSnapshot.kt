package com.adsamcik.tracker.osm.intake

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Creates a completed, byte-counted PBF snapshot in app-private storage.
 *
 * The user-selected stream is opened exactly once. Once this method returns,
 * both future parser passes must use [PrivatePbfSnapshot.openInputStream] and
 * cannot reopen the provider URI. The source byte and on-disk snapshot charges
 * are admitted together before every output write; the only read buffer is a
 * fixed, pre-reserved allocation.
 *
 * This is intentionally not wired to the legacy Worker or parser. It supplies
 * only the acquisition boundary required by a future strict reader, durable
 * job coordinator, and generation publication design. Creating a snapshot does
 * not validate a PBF or re-enable offline import.
 */
class PrivatePbfSnapshotStore(
	private val privateDirectory: File,
	private val ledger: PbfResourceLedger,
	private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES,
) {
	init {
		require(bufferSizeBytes in MIN_BUFFER_SIZE_BYTES..MAX_BUFFER_SIZE_BYTES) {
			"PBF snapshot buffer must be between $MIN_BUFFER_SIZE_BYTES and $MAX_BUFFER_SIZE_BYTES bytes"
		}
	}

	/**
	 * Copies one source stream into a completed private snapshot.
	 *
	 * There is intentionally no caller-supplied file-size argument. Provider
	 * metadata is not a safety bound; the ledger is charged from bytes actually
	 * read, and one byte beyond either source/disk limit is read only into the
	 * fixed buffer, rejected before it can be written, and then cleaned up.
	 */
	fun create(openSource: () -> InputStream): PrivatePbfSnapshot {
		val lease = ledger.openLease()
		var temporaryFile: File? = null

		try {
			// Reserve before allocating the one fixed buffer. No PBF-controlled
			// length participates in this allocation.
			lease.reserve(PbfResource.MANAGED_MEMORY_BYTES, bufferSizeBytes.toLong())
			val buffer = ByteArray(bufferSizeBytes)
			val directory = ensurePrivateDirectory()
			val temporary = createTemporaryFile(directory)
			temporaryFile = temporary

			val byteCount = copyOnce(
				openSource = openSource,
				temporaryFile = temporary,
				buffer = buffer,
				lease = lease,
			)
			lease.release(PbfResource.MANAGED_MEMORY_BYTES, bufferSizeBytes.toLong())

			val finalFile = File(directory, "$FINAL_FILE_PREFIX${UUID.randomUUID()}$FINAL_FILE_SUFFIX")
			if (finalFile.exists() || !temporary.renameTo(finalFile)) {
				throw PbfIntakeFailure.SnapshotFinalizationFailed
			}
			temporaryFile = null
			return PrivatePbfSnapshot(
				snapshotFile = finalFile,
				byteCount = byteCount,
				lease = lease,
			)
		} catch (failure: PbfIntakeFailure) {
			cleanupFailedSnapshot(temporaryFile, lease)
			throw failure
		} catch (_: IOException) {
			cleanupFailedSnapshot(temporaryFile, lease)
			throw PbfIntakeFailure.SnapshotIoFailed
		} catch (_: SecurityException) {
			cleanupFailedSnapshot(temporaryFile, lease)
			throw PbfIntakeFailure.SnapshotIoFailed
		}
	}

	private fun ensurePrivateDirectory(): File {
		if (privateDirectory.exists()) {
			if (!privateDirectory.isDirectory) {
				throw PbfIntakeFailure.InvalidPrivateSnapshotDirectory
			}
		} else if (!privateDirectory.mkdirs() && !privateDirectory.isDirectory) {
			throw PbfIntakeFailure.InvalidPrivateSnapshotDirectory
		}
		return try {
			privateDirectory.canonicalFile
		} catch (_: IOException) {
			throw PbfIntakeFailure.InvalidPrivateSnapshotDirectory
		}
	}

	private fun createTemporaryFile(directory: File): File = try {
		File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, directory)
	} catch (_: IOException) {
		throw PbfIntakeFailure.InvalidPrivateSnapshotDirectory
	} catch (_: SecurityException) {
		throw PbfIntakeFailure.InvalidPrivateSnapshotDirectory
	}

	private fun copyOnce(
		openSource: () -> InputStream,
		temporaryFile: File,
		buffer: ByteArray,
		lease: PbfResourceLease,
	): Long {
		val source = try {
			openSource()
		} catch (_: IOException) {
			throw PbfIntakeFailure.SourceUnavailable
		} catch (_: SecurityException) {
			throw PbfIntakeFailure.SourceUnavailable
		} catch (_: IllegalArgumentException) {
			throw PbfIntakeFailure.SourceUnavailable
		}

		return source.use { input ->
			FileOutputStream(temporaryFile).use { output ->
				var copied = 0L
				while (true) {
					val readLimit = nextReadLimit(buffer.size)
					val read = readAtLeastOneOrEof(input, buffer, readLimit)
					if (read < 0) break

					// This combined reservation is the admission point immediately
					// before the copy can add bytes to private storage.
					lease.reserveAll(
						mapOf(
							PbfResource.SOURCE_BYTES to read.toLong(),
							PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES to read.toLong(),
						),
					)
					output.write(buffer, 0, read)
					copied = checkedAddCopiedBytes(copied, read)
				}
				output.fd.sync()
				copied
			}
		}
	}

	/**
	 * Read at most one byte beyond the tighter of the source and snapshot-disk
	 * bounds. That proves an exact-boundary source is accepted while the first
	 * oversized byte is rejected before output.write.
	 */
	private fun nextReadLimit(bufferSize: Int): Int {
		val remaining = minOf(
			ledger.remaining(PbfResource.SOURCE_BYTES),
			ledger.remaining(PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES),
		)
		return if (remaining >= bufferSize.toLong()) {
			bufferSize
		} else {
			// `remaining < bufferSize`, so the narrowing is checked by the
			// branch and `+ 1` cannot overflow an Int.
			remaining.toInt() + 1
		}
	}

	private fun readAtLeastOneOrEof(input: InputStream, buffer: ByteArray, limit: Int): Int {
		val count = input.read(buffer, 0, limit)
		if (count != 0) return count

		// InputStream is allowed (though discouraged) to return zero for a
		// non-empty request. Avoid a busy loop while preserving the same fixed
		// buffer and one-byte-over-limit boundary behavior.
		val singleByte = input.read()
		if (singleByte < 0) return -1
		buffer[0] = singleByte.toByte()
		return 1
	}

	private fun checkedAddCopiedBytes(copied: Long, read: Int): Long = try {
		Math.addExact(copied, read.toLong())
	} catch (_: ArithmeticException) {
		throw PbfIntakeFailure.ResourceLimitExceeded(
			resource = PbfResource.SOURCE_BYTES,
			limit = ledger.limitFor(PbfResource.SOURCE_BYTES),
			requested = read.toLong(),
		)
	}

	private fun cleanupFailedSnapshot(file: File?, lease: PbfResourceLease) {
		if (file == null || deleteQuietly(file)) {
			lease.close()
			return
		}

		// If cleanup cannot remove a partial private file, retain its disk
		// charge rather than letting a subsequent job over-book storage. The
		// future durable reconciler must revisit this artifact by job authority.
		PbfResource.entries
			.filterNot { it == PbfResource.PRIVATE_SNAPSHOT_DISK_BYTES }
			.forEach { resource ->
				val reserved = lease.reserved(resource)
				if (reserved > 0L) lease.release(resource, reserved)
			}
	}

	private fun deleteQuietly(file: File): Boolean = try {
		!file.exists() || file.delete()
	} catch (_: SecurityException) {
		false
	}

	companion object {
		const val DEFAULT_BUFFER_SIZE_BYTES: Int = 16 * 1024
		const val MIN_BUFFER_SIZE_BYTES: Int = 1
		const val MAX_BUFFER_SIZE_BYTES: Int = 64 * 1024

		private const val TEMP_FILE_PREFIX = "pbf-"
		private const val TEMP_FILE_SUFFIX = ".partial"
		private const val FINAL_FILE_PREFIX = "pbf-"
		private const val FINAL_FILE_SUFFIX = ".snapshot"
	}
}

/**
 * A completed immutable snapshot whose input streams always originate from
 * app-private storage rather than the original content provider.
 */
class PrivatePbfSnapshot internal constructor(
	private val snapshotFile: File,
	val byteCount: Long,
	private val lease: PbfResourceLease,
) : AutoCloseable {
	private val lock = Any()
	private var closed = false

	fun openInputStream(): InputStream = synchronized(lock) {
		check(!closed) { "PBF private snapshot is already closed" }
		val expectedBytesPresent = try {
			snapshotFile.isFile && snapshotFile.length() == byteCount
		} catch (_: SecurityException) {
			false
		}
		if (!expectedBytesPresent) throw PbfIntakeFailure.SnapshotIntegrityFailed
		try {
			FileInputStream(snapshotFile)
		} catch (_: IOException) {
			throw PbfIntakeFailure.SnapshotIntegrityFailed
		} catch (_: SecurityException) {
			throw PbfIntakeFailure.SnapshotIntegrityFailed
		}
	}

	override fun close() {
		synchronized(lock) {
			if (closed) return
			closed = true
			if (deleteQuietly(snapshotFile)) {
				lease.close()
				return
			}

			// Keep the disk reservation if the private file remains. Releasing it
			// would make later work assume bytes are available when they are not.
			val sourceBytes = lease.reserved(PbfResource.SOURCE_BYTES)
			if (sourceBytes > 0L) lease.release(PbfResource.SOURCE_BYTES, sourceBytes)
			throw PbfIntakeFailure.SnapshotDeleteFailed
		}
	}

	private fun deleteQuietly(file: File): Boolean = try {
		!file.exists() || file.delete()
	} catch (_: SecurityException) {
		false
	}
}
