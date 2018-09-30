package com.adsamcik.signalcollector.file

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

object LongTermStore {
    private const val DIRECTORY_NAME = "LongtermStorage"

    fun getDir(context: Context): File {
        val dir = File(DataStore.getDir(context), DIRECTORY_NAME)
        if (!dir.exists())
            dir.mkdir()
        return dir
    }

    fun file(context: Context, fileName: String): File = FileStore.file(getDir(context), fileName)

    fun moveToLongTermStorage(context: Context, file: File): Boolean {
        var targetFile = file(context, file.name)

        if (targetFile.exists()) {
            targetFile = file(context, "${file.nameWithoutExtension}-${System.currentTimeMillis()}.${file.extension}")
        }

        return file.renameTo(targetFile)
    }

    fun listFiles(context: Context): Array<out File> = getDir(context).listFiles()

    fun clearData(context: Context) = FileStore.clearDirectory(getDir(context))

    fun sizeOfStoredFiles(context: Context): Long {
        val files = listFiles(context)
        var size = 0L
        files.forEach {
            if (it.extension == "zip") {
                val zipFile = ZipFile(it)
                val iterator = zipFile.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    size += entry.size
                }
            }
        }

        return size
    }
}