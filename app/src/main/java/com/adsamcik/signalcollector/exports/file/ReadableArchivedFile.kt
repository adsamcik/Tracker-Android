package com.adsamcik.signalcollector.exports.file

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ReadableArchivedFile(val zipFile: ZipFile, val entry: ZipEntry) : IReadableFile {
    override val name: String
        get() = "${zipFile.name}/${entry.name}"

    override fun read(): String {
        val stream = zipFile.getInputStream(entry)
        return stream.bufferedReader().readText()
    }
}