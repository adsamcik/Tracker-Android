package com.adsamcik.signalcollector.import.archive

import android.content.Context
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
				//todo entry name might be unsafe it's not priority to fix because
				// user chooses the file, but it might be good idea to fix it in the future
				val filename = entry.name

				if (entry.isDirectory) {
					val directory = File(outputDirectory, filename)
					directory.mkdirs()
					continue
				}

				val fileOut = File(outputDirectory, filename)
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