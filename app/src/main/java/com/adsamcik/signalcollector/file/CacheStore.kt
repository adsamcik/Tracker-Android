package com.adsamcik.signalcollector.file

import android.content.Context

import java.io.File

/**
 * Utility class for storing cache files
 * Uses [FileStore] for work with files
 */
object CacheStore {

    fun file(context: Context, fileName: String): File = FileStore.file(context.cacheDir, fileName)

    /**
     * Saves string to file
     *
     * @param fileName file name
     * @param data     string data
     */
    fun saveString(context: Context, fileName: String, data: String, append: Boolean): Boolean =
            FileStore.saveString(file(context, fileName), data, append)


    /**
     * Converts loadStringAsBuilder to string and handles nulls
     *
     * @param fileName file name
     * @return content of file (empty if file has no content or does not exists)
     */
    fun loadString(context: Context, fileName: String): String? =
            FileStore.loadString(file(context, fileName))

    /**
     * Checks if file exists
     *
     * @param fileName file name
     * @return existence of file
     */
    fun exists(context: Context, fileName: String): Boolean = file(context, fileName).exists()

    fun clearAll(context: Context) {
        FileStore.clearDirectory(context.cacheDir)
    }
}
