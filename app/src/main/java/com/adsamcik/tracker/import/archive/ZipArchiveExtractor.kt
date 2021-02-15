package com.adsamcik.tracker.import.archive

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts zip archives
 */
class ZipArchiveExtractor : ArchiveExtractor {
	companion object {
		private const val BUFFER_SIZE = 1024
	}

	override val supportedExtensions: Collection<String> = listOf("zip")

	override fun extract(context: Context, file: File): File? {
		require(file.isDirectory) { "Directory is not a zip file" }

		val outputDirectory = File(context.cacheDir, file.nameWithoutExtension)

		outputDirectory.mkdir()

		ZipInputStream(BufferedInputStream(FileInputStream(file))).use {
			val buffer = ByteArray(BUFFER_SIZE)

			var entry = it.nextEntry
			while (entry != null) {
				val filename = entry.name

				if (entry.isDirectory) {
					//todo add support for directories
					//val directory = File(outputDirectory, filename)
					//directory.mkdirs()
					continue
				}

				val fileOut = File(outputDirectory, filename)

				val canonicalPath = fileOut.canonicalPath
				if (!canonicalPath.startsWith(outputDirectory.path)) {
					//todo report this to the user
					com.adsamcik.tracker.logger.Reporter.report(SecurityException("File has invalid file name '$filename'"))
					continue
				}

				val fileOutStream = FileOutputStream(fileOut, false)

				var count = it.read(buffer)
				while (count != -1) {
					fileOutStream.write(buffer, 0, count)

					count = it.read(buffer)
				}

				entry = it.nextEntry
			}

			return outputDirectory
		}
	}

}
