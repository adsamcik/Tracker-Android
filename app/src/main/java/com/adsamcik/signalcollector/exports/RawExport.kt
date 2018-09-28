package com.adsamcik.signalcollector.exports

import com.adsamcik.signalcollector.file.Compress
import com.adsamcik.signalcollector.file.DataFile
import com.crashlytics.android.Crashlytics
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class RawExport : IExport {
    override fun export(files: ArrayList<File>, destinationDirectory: File): ExportResult {
        val compress: Compress
        try {
            compress = Compress(File(destinationDirectory, "Signals-${System.currentTimeMillis()}"))
        } catch (e: FileNotFoundException) {
            Crashlytics.logException(e)
            return ExportResult(null)
        }

        for (file in files)
            if (!compress.add(file)) {
                return ExportResult(null)
            } else
                DataFile(file, 0).close()

        return try {
            ExportResult(compress.finish(), "application/zip")
        } catch (e: IOException) {
            Crashlytics.logException(e)
            ExportResult(null)
        }
    }

}