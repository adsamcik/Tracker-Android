package com.adsamcik.signalcollector.import

import com.adsamcik.signalcollector.common.database.AppDatabase
import java.io.File

interface IImport {
	val supportedExtensions: Collection<String>

	fun import(database: AppDatabase, file: File)
}