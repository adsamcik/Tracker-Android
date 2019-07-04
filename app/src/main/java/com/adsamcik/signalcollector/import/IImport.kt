package com.adsamcik.signalcollector.import

import com.adsamcik.signalcollector.common.database.AppDatabase
import java.io.File

interface IImport {
	fun import(database: AppDatabase, file: File)
}