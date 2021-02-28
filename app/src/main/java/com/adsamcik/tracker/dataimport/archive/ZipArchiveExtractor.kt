package com.adsamcik.tracker.dataimport.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.openInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extracts zip archives
 */
class ZipArchiveExtractor : ArchiveExtractor {
	override val supportedExtensions: Collection<String> = listOf("zip")

	override fun extract(context: Context, file: DocumentFile): Sequence<InputStream>? {
		require(file.isDirectory) { "Directory is not a zip file" }

		file.openInputStream(context)?.use {
			ZipInputStream(it).use { zipStream ->
				var entry = zipStream.nextEntry
				return sequence {
					while (entry != null) {
						if (entry.isDirectory) {
							//todo add support for directories
							//val directory = File(outputDirectory, filename)
							//directory.mkdirs()
							continue
						}

						yield(zipStream)

						entry = zipStream.nextEntry
					}
				}
			}
		}
		return null
	}
}
