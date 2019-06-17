package com.adsamcik.signalcollector.export

import android.content.Context
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import java.io.File

interface IExport {
	fun export(context: Context, locationData: List<DatabaseLocation>, destinationDirectory: File, desiredName: String): ExportResult
}