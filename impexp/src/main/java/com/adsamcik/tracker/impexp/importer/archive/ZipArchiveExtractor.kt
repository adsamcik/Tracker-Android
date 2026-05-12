package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.base.extension.openInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Extracts zip archives
 */
internal class ZipArchiveExtractor : ArchiveExtractor {
	override val supportedExtensions: Collection<String> = listOf("zip")

	override fun extract(context: Context, file: DocumentFile): Sequence<FileImportStream>? {
		require(!file.isDirectory) { "Directory is not a zip file" }

		return file.openInputStream(context)?.use {
			ZipInputStream(it).use { zipStream ->
				val extractedEntries = mutableListOf<FileImportStream>()
				while (true) {
					val entry = zipStream.nextEntry ?: break
					try {
						if (entry.isDirectory) continue

						val entryName = entry.name
						if (!isSafeZipEntryName(entryName)) continue

						extractedEntries.add(materializeEntry(context, zipStream, entryName))
					} finally {
						zipStream.closeEntry()
					}
				}
				extractedEntries.asSequence()
			}
		}
	}

	private fun materializeEntry(
		context: Context,
		zipStream: ZipInputStream,
		entryName: String
	): FileImportStream {
		val importCacheDir = File(context.cacheDir, ZIP_IMPORT_CACHE_DIR).apply { mkdirs() }
		val tempFile = File.createTempFile("zip-entry-", ".tmp", importCacheDir)
		try {
			tempFile.outputStream().use { output ->
				zipStream.copyTo(output)
			}
			return FileImportStream(tempFile.inputStream(), entryName) {
				tempFile.delete()
			}
		} catch (e: Exception) {
			tempFile.delete()
			throw e
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
	}
}
