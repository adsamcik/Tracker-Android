package com.adsamcik.tracker.impexp.importer.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.base.extension.openInputStream
import java.util.zip.ZipInputStream

/**
 * Extracts zip archives
 */
internal class ZipArchiveExtractor : ArchiveExtractor {
	override val supportedExtensions: Collection<String> = listOf("zip")

	override fun extract(context: Context, file: DocumentFile): Sequence<FileImportStream>? {
		require(file.isDirectory) { "Directory is not a zip file" }

		file.openInputStream(context)?.use {
			ZipInputStream(it).use { zipStream ->
				var entry = zipStream.nextEntry
				return sequence {
					while (entry != null) {
						// Security: Skip directory entries
						if (entry.isDirectory) {
							entry = zipStream.nextEntry
							continue
						}

						// Security: Prevent zip-slip path traversal attacks
						val entryName = entry.name
						if (entryName.contains("..") || entryName.startsWith("/") || entryName.startsWith("\\")) {
							entry = zipStream.nextEntry
							continue
						}

						yield(FileImportStream(zipStream, entryName))

						entry = zipStream.nextEntry
					}
				}
			}
		}
		return null
	}
}
