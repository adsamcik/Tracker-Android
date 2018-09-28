package com.adsamcik.signalcollector.exports

import java.io.File

data class ExportResult(val file: File?, val mime: String = "") {
    val isSuccessfull
        get() = file != null
}