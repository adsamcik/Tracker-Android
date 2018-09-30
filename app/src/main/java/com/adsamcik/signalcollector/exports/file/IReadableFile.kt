package com.adsamcik.signalcollector.exports.file

interface IReadableFile {
    //todo use streams for the love of memory
    fun read(): String

    val name: String

    val time: Long
}