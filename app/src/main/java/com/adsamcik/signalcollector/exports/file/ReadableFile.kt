package com.adsamcik.signalcollector.exports.file

import java.io.File

data class ReadableFile(val file: File) : IReadableFile {
    override val name: String
        get() = file.name

    override fun read(): String = file.reader().readText()
}