package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.PermanentImportInputException
import com.adsamcik.tracker.shared.base.extension.openInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Extracts zip archives one entry at a time.
 *
 * The limits are deliberately generous for personal GPX/JSON backups while bounding every
 * resource dimension attackers can amplify: compressed input, entry count, per-entry expansion,
 * cumulative output, temporary files, and path traversal.
 */
internal class ZipArchiveExtractor(
	private val tempFileFactory: (File) -> File = { importCacheDir ->
		File.createTempFile("zip-entry-", ".tmp", importCacheDir)
	},
	private val tempInputStreamFactory: (File) -> InputStream = { it.inputStream() },
	private val zipInputStreamFactory: (InputStream) -> ZipInputStream = ::ZipInputStream,
) : ArchiveExtractor {
	override val supportedExtensions: Collection<String> = listOf("zip")

	/**
	 * Identifies full-database backups before any entry is handed to a merge importer. The
	 * exporter writes its manifest last, so classification must inspect the complete archive.
	 */
	@Suppress("LongMethod", "NestedBlockDepth")
	fun classifyForMergeImport(
		context: Context,
		file: DocumentFile,
	): ZipArchiveClassification {
		if (file.isDirectory) {
			throw PermanentImportInputException("Import source is not a ZIP archive.")
		}
		val declaredSourceSize = file.length()
		if (declaredSourceSize > MAX_COMPRESSED_INPUT_BYTES) {
			throw PermanentImportInputException(
				"Zip archive exceeds compressed input size limit " +
					"($MAX_COMPRESSED_INPUT_BYTES bytes)."
			)
		}

		val source = file.openInputStream(context) ?: return ZipArchiveClassification.GENERAL_IMPORT
		try {
			LimitedCountingInputStream(source, MAX_COMPRESSED_INPUT_BYTES).use { countedSource ->
				validatedZipInputStream(countedSource).use { zipStream ->
				var totalBytesRead = 0L
				var entryCount = 0
				while (true) {
					val entry = nextZipEntry(zipStream) ?: break
					entryCount++
					if (entryCount > MAX_ENTRY_COUNT) {
						throw PermanentImportInputException(
							"Zip archive exceeds entry count limit ($MAX_ENTRY_COUNT)."
						)
					}

					var entryReadSuccessfully = false
					var entryFailure: Throwable? = null
					try {
						validateDeclaredEntry(entry)
						val budgetRemaining = MAX_TOTAL_BYTES - totalBytesRead
						if (budgetRemaining <= 0L) {
							throw PermanentImportInputException(
								"Zip archive exceeds total uncompressed size limit " +
									"($MAX_TOTAL_BYTES bytes); refusing to inspect more."
							)
						}

						val compressedBytesBefore = countedSource.bytesRead
						val output = if (entry.name == BACKUP_MANIFEST_FILE) {
							ByteArrayOutputStream()
						} else {
							DISCARDING_OUTPUT
						}
						val read = readEntryWithLimits(
							zipStream = zipStream,
							entry = entry,
							output = output,
							perEntryLimit = minOf(
								MAX_ENTRY_BYTES,
								if (output is ByteArrayOutputStream) {
									MAX_MANIFEST_BYTES
								} else {
									Long.MAX_VALUE
								},
							),
							remainingTotalBudget = budgetRemaining,
							compressedBytesBefore = compressedBytesBefore,
							compressedBytesRead = { countedSource.bytesRead },
						)
						entryReadSuccessfully = true
						totalBytesRead += read
						if (
							output is ByteArrayOutputStream &&
							isTrackerDatabaseBackupManifest(output.toByteArray())
						) {
							return ZipArchiveClassification.TRACKER_DATABASE_BACKUP
						}
					} catch (failure: Throwable) {
						entryFailure = failure
						throw failure
					} finally {
						closeCompletedZipEntry(zipStream, entryReadSuccessfully, entryFailure)
					}
				}
			}
			}
		} catch (failure: ArchiveSourceReadFailure) {
			throw failure.original
		} catch (failure: PermanentImportInputException) {
			throw failure
		}
		return ZipArchiveClassification.GENERAL_IMPORT
	}

	@Suppress("LongMethod", "NestedBlockDepth")
	override suspend fun extract(
		context: Context,
		file: DocumentFile,
		shouldExtract: suspend (ArchiveEntryMetadata) -> Boolean,
		consume: suspend (FileImportStream) -> Unit,
	): Boolean {
		if (file.isDirectory) {
			throw PermanentImportInputException("Import source is not a ZIP archive.")
		}
		val declaredSourceSize = file.length()
		if (declaredSourceSize > MAX_COMPRESSED_INPUT_BYTES) {
			throw PermanentImportInputException(
				"Zip archive exceeds compressed input size limit " +
					"($MAX_COMPRESSED_INPUT_BYTES bytes)."
			)
		}

		val source = file.openInputStream(context) ?: return false
		try {
			LimitedCountingInputStream(source, MAX_COMPRESSED_INPUT_BYTES).use { countedSource ->
				validatedZipInputStream(countedSource).use { zipStream ->
				var totalBytesWritten = 0L
				var entryCount = 0
				while (true) {
					val entry = nextZipEntry(zipStream) ?: break
					entryCount++
					if (entryCount > MAX_ENTRY_COUNT) {
						throw PermanentImportInputException(
							"Zip archive exceeds entry count limit ($MAX_ENTRY_COUNT)."
						)
					}

					var entryReadSuccessfully = false
					var entryFailure: Throwable? = null
					try {
						val entryName = entry.name
						validateDeclaredEntry(entry)
						val budgetRemaining = MAX_TOTAL_BYTES - totalBytesWritten
						if (budgetRemaining <= 0L) {
							throw PermanentImportInputException(
								"Zip archive exceeds total uncompressed size limit " +
									"($MAX_TOTAL_BYTES bytes); refusing to extract more."
							)
						}

						val compressedBytesBefore = countedSource.bytesRead
						if (entry.isDirectory || !isSafeZipEntryName(entryName)) {
							val discarded = readEntryWithLimits(
								zipStream = zipStream,
								entry = entry,
								output = DISCARDING_OUTPUT,
								perEntryLimit = MAX_ENTRY_BYTES,
								remainingTotalBudget = budgetRemaining,
								compressedBytesBefore = compressedBytesBefore,
								compressedBytesRead = { countedSource.bytesRead },
							)
							entryReadSuccessfully = true
							totalBytesWritten += discarded
							continue
						}

						val metadata = ArchiveEntryMetadata(
							receiptKey = entryReceiptKey(entryCount, entry),
							fileName = entryName,
						)
						if (!shouldExtract(metadata)) {
							val discarded = readEntryWithLimits(
								zipStream = zipStream,
								entry = entry,
								output = DISCARDING_OUTPUT,
								perEntryLimit = MAX_ENTRY_BYTES,
								remainingTotalBudget = budgetRemaining,
								compressedBytesBefore = compressedBytesBefore,
								compressedBytesRead = { countedSource.bytesRead },
							)
							entryReadSuccessfully = true
							totalBytesWritten += discarded
							continue
						}

						val (stream, written) = materializeEntry(
							context = context,
							zipStream = zipStream,
							entry = entry,
							metadata = metadata,
							perEntryLimit = MAX_ENTRY_BYTES,
							remainingTotalBudget = budgetRemaining,
							compressedBytesBefore = compressedBytesBefore,
							compressedBytesRead = { countedSource.bytesRead },
						)
						entryReadSuccessfully = true
						stream.use { consume(it) }
						totalBytesWritten += written
					} catch (failure: Throwable) {
						entryFailure = failure
						throw failure
					} finally {
						closeCompletedZipEntry(zipStream, entryReadSuccessfully, entryFailure)
					}
				}
			}
			}
		} catch (failure: ArchiveSourceReadFailure) {
			throw failure.original
		} catch (failure: PermanentImportInputException) {
			throw failure
		}
		return true
	}

	private fun validateDeclaredEntry(entry: ZipEntry) {
		if (entry.size > MAX_ENTRY_BYTES) {
			throw PermanentImportInputException(
				"Zip entry exceeds size limit ($MAX_ENTRY_BYTES bytes)."
			)
		}
		if (entry.compressedSize > MAX_COMPRESSED_INPUT_BYTES) {
			throw PermanentImportInputException(
				"Zip entry exceeds compressed size limit " +
					"($MAX_COMPRESSED_INPUT_BYTES bytes)."
			)
		}
		if (entry.size > 0L && entry.compressedSize > 0L) {
			validateCompressionRatio(entry.size, entry.compressedSize)
		}
	}

	private fun materializeEntry(
		context: Context,
		zipStream: ZipInputStream,
		entry: ZipEntry,
		metadata: ArchiveEntryMetadata,
		perEntryLimit: Long,
		remainingTotalBudget: Long,
		compressedBytesBefore: Long,
		compressedBytesRead: () -> Long,
	): Pair<FileImportStream, Long> {
		val importCacheDir = File(context.cacheDir, ZIP_IMPORT_CACHE_DIR).apply { mkdirs() }
		val tempFile = tempFileFactory(importCacheDir)
		try {
			val written = tempFile.outputStream().use { output ->
				readEntryWithLimits(
					zipStream = zipStream,
					entry = entry,
					output = output,
					perEntryLimit = perEntryLimit,
					remainingTotalBudget = remainingTotalBudget,
					compressedBytesBefore = compressedBytesBefore,
					compressedBytesRead = compressedBytesRead,
				)
			}
			return FileImportStream(
				fileName = metadata.fileName,
				receiptKey = metadata.receiptKey,
				streamProvider = { tempInputStreamFactory(tempFile) },
				onClose = { tempFile.delete() },
			) to written
		} catch (throwable: Throwable) {
			tempFile.delete()
			throw throwable
		}
	}

	private fun readEntryWithLimits(
		zipStream: ZipInputStream,
		entry: ZipEntry,
		output: OutputStream,
		perEntryLimit: Long,
		remainingTotalBudget: Long,
		compressedBytesBefore: Long,
		compressedBytesRead: () -> Long,
	): Long {
		val declaredRatioLimit = entry.compressedSize
			.takeIf { it > 0L }
			?.let(::maximumExpandedBytes)
			?: Long.MAX_VALUE
		val limit = minOf(perEntryLimit, remainingTotalBudget, declaredRatioLimit)
		val written = zipStream.copyToWithLimit(output, limit)
		val measuredCompressedBytes = (compressedBytesRead() - compressedBytesBefore)
			.takeIf { it > 0L }
		val compressedSize = entry.compressedSize
			.takeIf { it > 0L }
			?: measuredCompressedBytes
		if (compressedSize != null && written > 0L) {
			validateCompressionRatio(written, compressedSize)
		}
		return written
	}

	private fun validateCompressionRatio(
		uncompressedBytes: Long,
		compressedBytes: Long,
	) {
		if (uncompressedBytes > maximumExpandedBytes(compressedBytes)) {
			throw PermanentImportInputException(
				"Zip entry exceeds maximum compression ratio " +
					"($MAX_COMPRESSION_RATIO:1)."
			)
		}
	}

	private fun maximumExpandedBytes(compressedBytes: Long): Long =
		try {
			Math.multiplyExact(compressedBytes, MAX_COMPRESSION_RATIO)
		} catch (_: ArithmeticException) {
			Long.MAX_VALUE
		}

	private fun ZipInputStream.copyToWithLimit(
		out: OutputStream,
		limit: Long,
	): Long {
		val buffer = ByteArray(BUFFER_SIZE)
		var total = 0L
		while (true) {
			val read = readZip { this.read(buffer) }
			if (read <= 0) break
			val nextTotal = total + read
			if (nextTotal > limit) {
				throw PermanentImportInputException(
					"Zip entry exceeds extraction limit ($limit bytes)."
				)
			}
			out.write(buffer, 0, read)
			total = nextTotal
		}
		return total
	}

	private fun nextZipEntry(zipStream: ZipInputStream): ZipEntry? =
		readZip { zipStream.nextEntry }

	private fun closeZipEntry(zipStream: ZipInputStream) {
		readZip { zipStream.closeEntry() }
	}

	private fun closeCompletedZipEntry(
		zipStream: ZipInputStream,
		entryReadSuccessfully: Boolean,
		entryFailure: Throwable?,
	) {
		if (!entryReadSuccessfully) return
		try {
			closeZipEntry(zipStream)
		} catch (closeFailure: Throwable) {
			if (entryFailure == null) {
				throw closeFailure
			}
			entryFailure.addSuppressed(closeFailure)
		}
	}

	private inline fun <T> readZip(block: () -> T): T = try {
		block()
	} catch (failure: ArchiveSourceReadFailure) {
		throw failure
	} catch (failure: PermanentImportInputException) {
		throw failure
	} catch (failure: ZipException) {
		failure.archiveSourceReadFailure()?.let { throw it }
		throw PermanentImportInputException("ZIP archive structure is invalid.", failure)
	} catch (failure: java.io.EOFException) {
		failure.archiveSourceReadFailure()?.let { throw it }
		throw PermanentImportInputException("ZIP archive is truncated.", failure)
	} catch (failure: IOException) {
		failure.archiveSourceReadFailure()?.let { throw it }
		throw PermanentImportInputException("ZIP archive structure is invalid.", failure)
	}

	private fun entryReceiptKey(index: Int, entry: ZipEntry): String {
		val identity = buildString {
			append("zip-entry-v1")
			append('\u0000')
			append(index)
			append('\u0000')
			append(entry.name)
			append('\u0000')
			append(entry.size)
			append('\u0000')
			append(entry.crc)
			append('\u0000')
			append(entry.compressedSize)
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(identity.toByteArray())
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	private fun isSafeZipEntryName(entryName: String): Boolean {
		val normalized = entryName.replace('\\', '/')
		return normalized.isNotBlank() &&
			!normalized.startsWith("/") &&
			!normalized.startsWith("../") &&
			normalized != ".." &&
			!normalized.contains("/../") &&
			!WINDOWS_ABSOLUTE_PATH.matches(normalized)
	}

	private fun isTrackerDatabaseBackupManifest(bytes: ByteArray): Boolean =
		TRACKER_BACKUP_MANIFEST_PREFIX.containsMatchIn(bytes.toString(Charsets.UTF_8))

	private fun validatedZipInputStream(
		countedSource: LimitedCountingInputStream,
	): ZipInputStream {
		val source = PushbackInputStream(countedSource, ZIP_SIGNATURE_BYTES)
		val signature = ByteArray(ZIP_SIGNATURE_BYTES)
		var offset = 0
		while (offset < signature.size) {
			val read = source.read(signature, offset, signature.size - offset)
			if (read < 0) {
				throw PermanentImportInputException("ZIP archive header is truncated.")
			}
			offset += read
		}
		source.unread(signature)
		if (!isSupportedZipSignature(signature)) {
			throw PermanentImportInputException("Import source is not a ZIP archive.")
		}
		return zipInputStreamFactory(source)
	}

	private fun isSupportedZipSignature(signature: ByteArray): Boolean =
		signature.contentEquals(ZIP_LOCAL_FILE_HEADER) ||
			signature.contentEquals(ZIP_EMPTY_ARCHIVE_HEADER)

	private class LimitedCountingInputStream(
		delegate: InputStream,
		private val limit: Long,
	) : FilterInputStream(delegate) {
		var bytesRead: Long = 0
			private set

		override fun read(): Int {
			val value = readSource { super.read() }
			if (value >= 0) addBytes(1)
			return value
		}

		override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
			val read = readSource { super.read(buffer, offset, length) }
			if (read > 0) addBytes(read)
			return read
		}

		override fun close() {
			readSource { super.close() }
		}

		private inline fun <T> readSource(block: () -> T): T = try {
			block()
		} catch (failure: PermanentImportInputException) {
			throw failure
		} catch (failure: IOException) {
			throw ArchiveSourceReadFailure(failure)
		}

		private fun addBytes(count: Int) {
			bytesRead += count
			if (bytesRead > limit) {
				throw PermanentImportInputException(
					"Zip archive exceeds compressed input size limit ($limit bytes).",
				)
			}
		}
	}

	internal companion object {
		private const val ZIP_SIGNATURE_BYTES = 4
		private const val BACKUP_MANIFEST_FILE = "manifest.json"
		private const val MAX_MANIFEST_BYTES = 64L * 1024L
		private val TRACKER_BACKUP_MANIFEST_PREFIX = Regex(
			"""^\uFEFF?\s*\{\s*"format"\s*:\s*"tracker-database-backup"\s*[,}]"""
		)
		private val ZIP_LOCAL_FILE_HEADER = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
		private val ZIP_EMPTY_ARCHIVE_HEADER = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
		private val DISCARDING_OUTPUT = object : OutputStream() {
			override fun write(value: Int) = Unit

			override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
		}
		const val ZIP_IMPORT_CACHE_DIR = "zip-import"
		val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/.*")

		/** Personal backups normally contain a handful of files; 2,048 leaves ample headroom. */
		const val MAX_ENTRY_COUNT = 2_048

		/** 512 MiB bounds source reads while still accepting unusually large local backups. */
		const val MAX_COMPRESSED_INPUT_BYTES = 512L * 1024L * 1024L

		/** Per-entry uncompressed size cap. 512 MiB covers very large legitimate exports. */
		const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L

		/** Total uncompressed size cap across all entries. 2 GiB. */
		const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L

		/** Text exports commonly compress 10-50:1; 200:1 is generous but rejects ~1000:1 bombs. */
		const val MAX_COMPRESSION_RATIO = 200L

		const val BUFFER_SIZE = 8 * 1024
	}
}

private class ArchiveSourceReadFailure(
	val original: IOException,
) : RuntimeException(null, null, false, false)

private fun Throwable.archiveSourceReadFailure(): ArchiveSourceReadFailure? {
	var current: Throwable? = this
	while (current != null) {
		if (current is ArchiveSourceReadFailure) return current
		current = current.cause
	}
	return null
}

internal enum class ZipArchiveClassification {
	GENERAL_IMPORT,
	TRACKER_DATABASE_BACKUP,
}
