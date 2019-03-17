package com.adsamcik.signalcollector.exports

import com.adsamcik.signalcollector.data.Location
import java.io.File

interface IExport {
	fun export(locationData: List<Location>, destinationDirectory: File, desiredName: String): ExportResult
}