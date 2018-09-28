package com.adsamcik.signalcollector.exports

import java.io.File

interface IExport {
    fun export(files: ArrayList<File>, destinationDirectory: File): ExportResult
}