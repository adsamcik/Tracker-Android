package com.adsamcik.signalcollector.exports

import com.adsamcik.signalcollector.exports.file.IReadableFile
import java.io.File

interface IExport {
    fun export(files: List<IReadableFile>, destinationDirectory: File): ExportResult
}