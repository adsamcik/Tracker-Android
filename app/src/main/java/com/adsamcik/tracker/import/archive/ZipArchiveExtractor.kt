package com.adsamcik.tracker.import.archive

import android.content.Context
import com.adsamcik.tracker.common.debug.Reporter
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class ZipArchiveExtractor : ArchiveExtractor {
	override val supportedExtensions: Collection<String> = listOf("zip")

	override fun extract(context: Context, file: File): File? {
		if (file.isDirectory) throw IllegalArgumentException("Directory is not a zip file")

		val outputDirectory = File(context.cacheDir, file.nameWithoutExtension)

		outputDirectory.mkdir()

		ZipInputStream(BufferedInputStream(FileInputStream(file))).use {
			val buffer = ByteArray(1024)

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
					//todo remove the report once out of beta
					Reporter.report(SecurityException("File has invalid file name $filename"))
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
