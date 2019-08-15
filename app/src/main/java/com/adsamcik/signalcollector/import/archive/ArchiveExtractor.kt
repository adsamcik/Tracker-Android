package com.adsamcik.signalcollector.import.archive

import android.content.Context
import java.io.File

interface ArchiveExtractor {
	val supportedExtensions: Collection<String>

	fun extract(context: Context, file: File): File?
}
