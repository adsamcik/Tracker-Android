package com.adsamcik.signalcollector.file

import android.os.Build
import android.util.MalformedJsonException
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.file.DataStore.DATA_FILE
import com.adsamcik.signalcollector.file.DataStore.PREF_DATA_FILE_INDEX
import com.adsamcik.signalcollector.utility.Constants
import com.crashlytics.android.Crashlytics
import com.squareup.moshi.Moshi
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

class DataFile(file: File, val index: Int) {
    var file: File = file
        private set

    private val adapter = Moshi.Builder().build().adapter(Array<RawData>::class.java)
    private var collectionCount: Int = 0
    /**
     * Returns whether the DataFile is writable
     *
     * @return True if is writable
     */
    var isWritable: Boolean = false
        private set

    private var empty: Boolean = false

    /**
     * Returns preference string for index
     *
     * @return Preference string for index
     */
    val preference: String
        get() = PREF_DATA_FILE_INDEX

    /**
     * Checks if DataFile is larger or equal than maximum DataFile size
     *
     * @return True if is larger or equal than maximum DataFile size
     */
    val isFull: Boolean
        get() = size() > Constants.MAX_DATA_FILE_SIZE

    init {
        if (!file.exists() || file.length() == 0L) {
            empty = true
            isWritable = true
            collectionCount = 0
        } else {
            var ascii: String? = null
            try {
                ascii = FileStore.loadLastAscii(file, 2)
            } catch (e: FileNotFoundException) {
                Crashlytics.logException(e)
            }

            isWritable = ascii == null || ascii != "]}"
            empty = ascii == null || ascii.endsWith(":")
            collectionCount = getCollectionCount(file)
        }
    }

    private fun updateCollectionCount(collectionCount: Int) {
        synchronized(isWritable) {
            this.collectionCount += collectionCount
            val newFile = File(file.parentFile, generateFileName(this.collectionCount, index))

            if (!file.renameTo(newFile))
                Crashlytics.logException(Throwable("Failed to rename file"))
            else
                file = newFile
        }
    }

    /**
     * Add json array data to file
     *
     * @param jsonArray       Json array
     * @param collectionCount Number of collections (items in array)
     * @return true if adding was success, false otherwise
     */
    fun addData(jsonArray: String, collectionCount: Int): Boolean {
        synchronized(isWritable) {
            if (jsonArray[0] != '[')
                throw IllegalArgumentException("Given string is not json array!")
            return if (saveData(jsonArray)) {
                updateCollectionCount(collectionCount)
                true
            } else
                false
        }
    }

    /**
     * Add RawData array to file
     *
     * @param data RawData array
     * @return true if adding was success, false otherwise
     */
    fun addData(data: Array<RawData>): Boolean {
        synchronized(isWritable) {
            if (!isWritable) {
                try {
                    FileOutputStream(file, true).channel.truncate(file.length() - 2).close()
                } catch (e: IOException) {
                    Crashlytics.logException(e)
                    return false
                }

                isWritable = true
            }

            return if (saveData(adapter.toJson(data))) {
                updateCollectionCount(data.size)
                true
            } else
                false
        }
    }

    private fun saveData(jsonArray: String): Boolean {
        synchronized(isWritable) {
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
    }

    /**
     * Closes DataFile
     * File will be automatically reopened when saveData is called
     *
     * @return True if close was successful
     */
    fun close(): Boolean {
        synchronized(isWritable) {
            return try {
                val lastChar = FileStore.loadLastAscii(file, 1)!!
                isWritable = false
                lastChar == "]" || FileStore.saveString(file, "]", true)
            } catch (e: FileNotFoundException) {
                Crashlytics.logException(e)
                isWritable = true
                false
            }
        }
    }

    fun lock() {
        synchronized(isWritable) {
            isWritable = false
        }
    }

    fun unlock() {
        synchronized(isWritable) {
            isWritable = true
        }
    }

    /**
     * Returns size of DataFile
     *
     * @return Size
     */
    fun size(): Long = file.length()

    companion object {
        const val SEPARATOR = "-"

        /**
         * Returns number of collection in given file
         *
         * @param file File
         * @return Number of collections
         */
        fun getCollectionCount(file: File): Int {
            val fileName = file.name
            val indexOf = fileName.indexOf(SEPARATOR) + SEPARATOR.length
            val length = fileName.indexOf(SEPARATOR, indexOf)
            return if (indexOf > 2)
                Integer.parseInt(fileName.substring(indexOf, length))
            else
                0
        }

        fun generateFileName(collectionCount: Int, index: Int): String {
            return "$DATA_FILE$index$SEPARATOR$collectionCount${SEPARATOR}API${Build.VERSION.SDK_INT}${SEPARATOR}V${BuildConfig.VERSION_CODE}"
        }
    }
}