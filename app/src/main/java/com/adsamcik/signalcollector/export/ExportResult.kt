package com.adsamcik.signalcollector.export

import java.io.File

data class ExportResult(val file: File?, val mime: String = "") {
    val isSuccessful
        get() = file != null
}