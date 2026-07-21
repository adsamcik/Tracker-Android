package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.base.extension.openInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
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
) : ArchiveExtractor {
	override val supportedExtensions: Collection<String> = listOf("zip")

	override suspend fun extract(
		context: Context,
		file: DocumentFile,
		shouldExtract: suspend (ArchiveEntryMetadata) -> Boolean,
		consume: suspend (FileImportStream) -> Unit,
	): Boolean {
		require(!file.isDirectory) { "Directory is not a zip file" }
		val declaredSourceSize = file.length()
		if (declaredSourceSize > MAX_COMPRESSED_INPUT_BYTES) {
			throw IOException(
				"Zip archive exceeds compressed input size limit " +
					"($MAX_COMPRESSED_INPUT_BYTES bytes)."
			)
		}

		val source = file.openInputStream(context) ?: return false
		LimitedCountingInputStream(source, MAX_COMPRESSED_INPUT_BYTES).use { countedSource ->
			ZipInputStream(countedSource).use { zipStream ->
				var totalBytesWritten = 0L
				var entryCount = 0
				while (true) {
					val entry = zipStream.nextEntry ?: break
					entryCount++
					if (entryCount > MAX_ENTRY_COUNT) {
						throw IOException(
							"Zip archive exceeds entry count limit ($MAX_ENTRY_COUNT)."
						)
					}

					try {
						if (entry.isDirectory) continue
						val entryName = entry.name
						if (!isSafeZipEntryName(entryName)) continue
						validateDeclaredEntry(entry)

						val metadata = ArchiveEntryMetadata(
							receiptKey = entryReceiptKey(entryCount, entry),
							fileName = entryName,
						)
						if (!shouldExtract(metadata)) continue

						val budgetRemaining = MAX_TOTAL_BYTES - totalBytesWritten
						if (budgetRemaining <= 0L) {
							throw IOException(
								"Zip archive exceeds total uncompressed size limit " +
									"($MAX_TOTAL_BYTES bytes); refusing to extract more."
							)
						}

						val compressedBytesBefore = countedSource.bytesRead
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
						stream.use { consume(it) }
						totalBytesWritten += written
					} finally {
						zipStream.closeEntry()
					}
				}
			}
		}
		return true
	}

	private fun validateDeclaredEntry(entry: ZipEntry) {
		if (entry.size > MAX_ENTRY_BYTES) {
			throw IOException(
				"Zip entry '${entry.name}' exceeds size limit ($MAX_ENTRY_BYTES bytes)."
			)
		}
		if (entry.compressedSize > MAX_COMPRESSED_INPUT_BYTES) {
			throw IOException(
				"Zip entry '${entry.name}' exceeds compressed size limit " +
					"($MAX_COMPRESSED_INPUT_BYTES bytes)."
			)
		}
		if (entry.size > 0L && entry.compressedSize > 0L) {
			validateCompressionRatio(entry.name, entry.size, entry.compressedSize)
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
			val declaredRatioLimit = entry.compressedSize
				.takeIf { it > 0L }
				?.let(::maximumExpandedBytes)
				?: Long.MAX_VALUE
			val limit = minOf(perEntryLimit, remainingTotalBudget, declaredRatioLimit)
			val written = tempFile.outputStream().use { output ->
				zipStream.copyToWithLimit(output, limit, metadata.fileName)
			}
			val measuredCompressedBytes = (compressedBytesRead() - compressedBytesBefore)
				.takeIf { it > 0L }
			val compressedSize = entry.compressedSize
				.takeIf { it > 0L }
				?: measuredCompressedBytes
			if (compressedSize != null && written > 0L) {
				validateCompressionRatio(metadata.fileName, written, compressedSize)
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

	private fun validateCompressionRatio(
		entryName: String,
		uncompressedBytes: Long,
		compressedBytes: Long,
	) {
		if (uncompressedBytes > maximumExpandedBytes(compressedBytes)) {
			throw IOException(
				"Zip entry '$entryName' exceeds maximum compression ratio " +
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
		entryName: String,
	): Long {
		val buffer = ByteArray(BUFFER_SIZE)
		var total = 0L
		while (true) {
			val read = read(buffer)
			if (read <= 0) break
			val nextTotal = total + read
			if (nextTotal > limit) {
				throw IOException(
					"Zip entry '$entryName' exceeds extraction limit ($limit bytes)."
				)
			}
			out.write(buffer, 0, read)
			total = nextTotal
		}
		return total
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

	private class LimitedCountingInputStream(
		delegate: InputStream,
		private val limit: Long,
	) : FilterInputStream(delegate) {
		var bytesRead: Long = 0
			private set

		override fun read(): Int {
			val value = super.read()
			if (value >= 0) addBytes(1)
			return value
		}

		override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
			val read = super.read(buffer, offset, length)
			if (read > 0) addBytes(read)
			return read
		}

		private fun addBytes(count: Int) {
			bytesRead += count
			if (bytesRead > limit) {
				throw IOException("Zip archive exceeds compressed input size limit ($limit bytes).")
			}
		}
	}

	internal companion object {
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
