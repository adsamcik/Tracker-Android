package com.adsamcik.signalcollector.file

import android.content.Context
import android.os.Build
import android.support.annotation.IntDef
import android.util.MalformedJsonException
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.file.DataStore.PREF_CACHE_FILE_INDEX
import com.adsamcik.signalcollector.file.DataStore.PREF_DATA_FILE_INDEX
import com.adsamcik.signalcollector.utility.Constants
import com.crashlytics.android.Crashlytics
import com.google.gson.Gson
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

class DataFile(file: File, private val fileNameTemplate: String?, userID: String?, @FileType type: Long, context: Context) {
    var file: File = file
        private set

    private val gson = Gson()
    private var collectionCount: Int = 0
    /**
     * Returns whether the DataFile is writeable
     *
     * @return True if is writeable
     */
    var isWriteable: Boolean = false
        private set

    private var empty: Boolean = false

    /**
     * Returns FileType
     *
     * @return FileType
     */
    @FileType
    @get:FileType
    val type: Long

    /**
     * Returns preference string for index
     *
     * @return Preference string for index
     */
    val preference: String?
        get() = when (type) {
            CACHE -> PREF_CACHE_FILE_INDEX
            STANDARD -> PREF_DATA_FILE_INDEX
            else -> null
        }

    /**
     * Checks if DataFile is larger or equal than maximum DataFile size
     *
     * @return True if is larger or equal than maximum DataFile size
     */
    val isFull: Boolean
        @Synchronized get() = size() > Constants.MAX_DATA_FILE_SIZE

    init {
        this.type = if (userID == null) CACHE else type
        if (!file.exists() || file.length() == 0L) {
            if (this.type == STANDARD)
                FileStore.saveString(file, "{\"userID\":\"" + userID + "\"," +
                        "\"model\":\"" + Build.MODEL +
                        "\",\"manufacturer\":\"" + Build.MANUFACTURER +
                        "\",\"api\":" + Build.VERSION.SDK_INT +
                        ",\"version\":" + context.packageManager.getPackageInfo(context.packageName, 0).versionCode + "," +
                        "\"data\":", false)
            empty = true
            isWriteable = true
            collectionCount = 0
        } else {
            var ascii: String? = null
            try {
                ascii = FileStore.loadLastAscii(file, 2)
            } catch (e: FileNotFoundException) {
                Crashlytics.logException(e)
            }

            isWriteable = ascii == null || ascii != "]}"
            empty = ascii == null || ascii.endsWith(":")
            collectionCount = getCollectionCount(file)
        }
    }

    @Synchronized private fun updateCollectionCount(collectionCount: Int) {
        this.collectionCount += collectionCount
        val newFile: File = if (fileNameTemplate != null)
            File(file.parentFile, fileNameTemplate + SEPARATOR + this.collectionCount)
        else
            File(file.parentFile, getTemplate(file) + SEPARATOR + this.collectionCount)

        if (!file.renameTo(newFile))
            Crashlytics.logException(Throwable("Failed to rename file"))
        else
            file = newFile
    }

    @IntDef(STANDARD, CACHE)
    @Retention(AnnotationRetention.SOURCE)
    annotation class FileType

    /**
     * Add json array data to file
     *
     * @param jsonArray       Json array
     * @param collectionCount Number of collections (items in array)
     * @return true if adding was success, false otherwise
     */
    @Synchronized
    fun addData(jsonArray: String, collectionCount: Int): Boolean {
        if (jsonArray[0] != '[')
            throw IllegalArgumentException("Given string is not json array!")
        return if (saveData(jsonArray)) {
            updateCollectionCount(collectionCount)
            true
        } else
            false
    }

    /**
     * Add RawData array to file
     *
     * @param data RawData array
     * @return true if adding was success, false otherwise
     */
    @Synchronized
    fun addData(data: Array<RawData>): Boolean {
        if (!isWriteable) {
            try {
                FileOutputStream(file, true).channel.truncate(file.length() - 2).close()
            } catch (e: IOException) {
                Crashlytics.logException(e)
                return false
            }

            isWriteable = true
        }

        return if (saveData(gson.toJson(data))) {
            updateCollectionCount(data.size)
            true
        } else
            false
    }

    @Synchronized private fun saveData(jsonArray: String): Boolean {
        return try {
            val status = FileStore.saveAppendableJsonArray(file, jsonArray, true, empty)
            if (status)
                empty = false
            status
        } catch (e: MalformedJsonException) {
            //Should never happen, but w/e
            Crashlytics.logException(e)
            false
        }

    }

    /**
     * Closes DataFile
     * File will be automatically reopened when saveData is called
     *
     * @return True if close was successful
     */
    @Synchronized
    fun close(): Boolean {
        return try {
            val last2 = FileStore.loadLastAscii(file, 2)!!
            isWriteable = false
            last2 == "]}" || FileStore.saveString(file, "]}", true)
        } catch (e: FileNotFoundException) {
            Crashlytics.logException(e)
            isWriteable = true
            false
        }

    }

    /**
     * Returns size of DataFile
     *
     * @return Size
     */
    fun size(): Long = file.length()

    companion object {
        const val STANDARD = 0L
        const val CACHE = 1L
        const val SEPARATOR = " "

        /**
         * Returns number of collection in given file
         *
         * @param file File
         * @return Number of collections
         */
        fun getCollectionCount(file: File): Int {
            val fileName = file.name
            val indexOf = fileName.indexOf(SEPARATOR) + SEPARATOR.length
            return if (indexOf > 2)
                Integer.parseInt(fileName.substring(indexOf))
            else
                0
        }

        /**
         * Returns file's template
         * File's template is common part shared by all files of the same type
         *
         * @param file File
         * @return File template
         */
        private fun getTemplate(file: File): String {
            val fileName = file.name
            val indexOf = fileName.indexOf(SEPARATOR)
            return if (indexOf > 2)
                fileName.substring(0, indexOf)
            else
                fileName
        }
    }
}