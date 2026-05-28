package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.base.extension.openInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extracts zip archives.
 *
 * Enforces decompression-bomb limits ([MAX_ENTRY_BYTES] per entry, [MAX_TOTAL_BYTES]
 * cumulative) so a hostile or accidentally huge archive cannot fill device storage or
 * OOM the parser downstream.
 */
internal class ZipArchiveExtractor(
		private val tempFileFactory: (File) -> File = { importCacheDir ->
			File.createTempFile("zip-entry-", ".tmp", importCacheDir)
		},
		private val tempInputStreamFactory: (File) -> InputStream = { it.inputStream() },
) : ArchiveExtractor {
	override val supportedExtensions: Collection<String> = listOf("zip")

	override fun extract(context: Context, file: DocumentFile): Sequence<FileImportStream>? {
		require(!file.isDirectory) { "Directory is not a zip file" }

		return file.openInputStream(context)?.use {
			ZipInputStream(it).use { zipStream ->
				val extractedEntries = mutableListOf<FileImportStream>()
				var totalBytesWritten = 0L
				try {
					while (true) {
						val entry = zipStream.nextEntry ?: break
						try {
							if (entry.isDirectory) continue

							val entryName = entry.name
							if (!isSafeZipEntryName(entryName)) continue

							val budgetRemaining = MAX_TOTAL_BYTES - totalBytesWritten
							if (budgetRemaining <= 0L) {
								throw IOException(
									"Zip archive exceeds total uncompressed size limit " +
										"($MAX_TOTAL_BYTES bytes); refusing to extract more."
								)
							}
							val (stream, written) = materializeEntry(
								context = context,
								zipStream = zipStream,
								entryName = entryName,
								perEntryLimit = MAX_ENTRY_BYTES,
								remainingTotalBudget = budgetRemaining,
							)
							extractedEntries.add(stream)
							totalBytesWritten += written
						} finally {
							zipStream.closeEntry()
						}
					}
					extractedEntries.asSequence()
				} catch (throwable: Throwable) {
					closeMaterializedEntries(extractedEntries, throwable)
					throw throwable
				}
			}
		}
	}

	private fun materializeEntry(
		context: Context,
		zipStream: ZipInputStream,
		entryName: String,
		perEntryLimit: Long,
		remainingTotalBudget: Long,
	): Pair<FileImportStream, Long> {
		val importCacheDir = File(context.cacheDir, ZIP_IMPORT_CACHE_DIR).apply { mkdirs() }
		val tempFile = tempFileFactory(importCacheDir)
		try {
			val limit = minOf(perEntryLimit, remainingTotalBudget)
			val written = tempFile.outputStream().use { output ->
				zipStream.copyToWithLimit(output, limit, entryName)
			}
			return FileImportStream(entryName, { tempInputStreamFactory(tempFile) }) {
				tempFile.delete()
			} to written
		} catch (throwable: Throwable) {
			tempFile.delete()
			throw throwable
		}
	}

	/**
	 * Copies bytes from this stream to [out] until EOF, refusing to write more than
	 * [limit] bytes. Throws [IOException] when the limit is exceeded so a zip bomb
	 * cannot exhaust device storage. Returns total bytes written.
	 */
	private fun ZipInputStream.copyToWithLimit(
		out: java.io.OutputStream,
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
					"Zip entry '$entryName' exceeds size limit ($limit bytes); refusing to extract."
				)
			}
			out.write(buffer, 0, read)
			total = nextTotal
		}
		return total
	}

	private fun closeMaterializedEntries(
			extractedEntries: Collection<FileImportStream>,
			failure: Throwable
	) {
		extractedEntries.forEach { stream ->
			try {
				stream.close()
			} catch (closeFailure: Throwable) {
				failure.addSuppressed(closeFailure)
			}
		}
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

	private companion object {
		const val ZIP_IMPORT_CACHE_DIR = "zip-import"
		val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/.*")

		/** Per-entry uncompressed size cap. 512 MiB covers very large legitimate exports. */
		const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L

		/** Total uncompressed size cap across all entries. 2 GiB. */
		const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L

		const val BUFFER_SIZE = 8 * 1024
	}
}
