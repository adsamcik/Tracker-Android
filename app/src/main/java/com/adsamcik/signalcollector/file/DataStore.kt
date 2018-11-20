package com.adsamcik.signalcollector.file

import android.content.Context
import android.os.Bundle
import android.util.MalformedJsonException
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.data.UploadStats
import com.adsamcik.signalcollector.enums.CloudStatuses
import com.adsamcik.signalcollector.file.DataFile.Companion.SEPARATOR
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.FirebaseAssist
import com.adsamcik.signalcollector.utility.Preferences
import com.crashlytics.android.Crashlytics
import com.google.firebase.analytics.FirebaseAnalytics
import com.squareup.moshi.Moshi
import java.io.File
import java.util.*
import kotlin.collections.ArrayList


/**
 * Utility class for storing data files
 * Uses [FileStore] for work with files
 *
 * Completely manages DataFiles
 */
object DataStore {
    const val TAG = "SignalsDatastore"

    const val RECENT_UPLOADS_FILE = "recentUploads"
    const val DATA_FILE = "dataStore"
    const val PREF_DATA_FILE_INDEX = "saveFileID"
    private const val PREF_COLLECTED_DATA_SIZE = "totalSize"

    private var onDataChanged: (() -> Unit)? = null
    private var onUploadProgress: ((Int) -> Unit)? = null

    const val TMP_NAME = "5GeVPiYk6J"

    @Volatile
    private var approxSize: Long = -1

    @Volatile
    private var collectionsOnDevice = -1

    @Volatile
    private var dataLocked = false

    private var moshi = Moshi.Builder().build()

    var currentDataFile: DataFile? = null
        private set

    fun getDir(context: Context): File = context.filesDir

    fun file(context: Context, fileName: String): File = FileStore.file(getDir(context), fileName)

    /**
     * Call to invoke onDataChanged callback
     */
    private fun onDataChanged(context: Context) {
        if (Network.cloudStatus == CloudStatuses.NO_SYNC_REQUIRED && sizeOfData(context) >= Constants.MIN_USER_UPLOAD_FILE_SIZE)
            Network.cloudStatus = CloudStatuses.SYNC_AVAILABLE
        else if (Network.cloudStatus == CloudStatuses.SYNC_AVAILABLE && sizeOfData(context) < Constants.MIN_USER_UPLOAD_FILE_SIZE)
            Network.cloudStatus = CloudStatuses.NO_SYNC_REQUIRED

        onDataChanged?.invoke()
    }

    /**
     * Call to invoke onUploadProgress callback
     *
     * @param progress progress as int (0-100)
     */
    fun onUpload(context: Context, progress: Int) {
        if (progress == 100)
            Network.cloudStatus = if (sizeOfData(context) >= Constants.MIN_USER_UPLOAD_FILE_SIZE) CloudStatuses.SYNC_AVAILABLE else CloudStatuses.NO_SYNC_REQUIRED
        else if (progress == -1 && sizeOfData(context) > 0)
            Network.cloudStatus = CloudStatuses.SYNC_AVAILABLE
        else
            Network.cloudStatus = CloudStatuses.SYNC_IN_PROGRESS

        onUploadProgress?.invoke(progress)
    }

    /**
     * Sets callback which is called when saved data changes (new data, delete, update)
     *
     * @param callback callback
     */
    fun setOnDataChanged(callback: (() -> Unit)?) {
        onDataChanged = callback
    }

    /**
     * Sets callback which is called on upload progress with percentage completed as integer (0-100)
     *
     * @param callback callback
     */
    fun setOnUploadProgress(callback: ((Int) -> Unit)?) {
        onUploadProgress = callback
    }

    /**
     * Locks datafile writes
     */
    fun lockData() {
        dataLocked = true
    }

    /**
     * Unlocks datafile writes
     */
    fun unlockData() {
        dataLocked = false
    }

    /**
     * Generates array of all data files
     *
     * @param context               context
     * @param lastFileSizeThreshold Include last datafile if it exceeds this size
     * @return array of datafile names
     */
    fun getDataFiles(context: Context, @androidx.annotation.IntRange(from = 0) lastFileSizeThreshold: Int): Array<File>? {
        val list = getDir(context).listFiles { _, s -> s.startsWith(DATA_FILE) }
        return if (list.isNotEmpty() && list.last().length() < lastFileSizeThreshold)
            list.dropLast(1).toTypedArray()
        else
            list
    }

    /**
     * Move file
     *
     * @param fileName    original file name
     * @param newFileName new file name
     * @return success
     */
    fun rename(context: Context, fileName: String, newFileName: String): Boolean =
            FileStore.rename(file(context, fileName), newFileName)

    /**
     * Delete file
     *
     * @param fileName file name
     */
    fun delete(context: Context, fileName: String) {
        if (!FileStore.delete(file(context, fileName)))
            Crashlytics.logException(RuntimeException("Failed to delete $fileName"))
    }

    /**
     * Checks if file exists
     *
     * @param fileName file name
     * @return existence of file
     */
    fun exists(context: Context, fileName: String): Boolean = file(context, fileName).exists()

    /**
     * Handles any leftover files that could have been corrupted by some issue and reorders existing files
     */
    @Synchronized
    fun cleanup(context: Context) {
        currentDataFile?.lock()
        val files = getDir(context).listFiles()
        Arrays.sort(files) { a: File, b: File -> a.name.compareTo(b.name) }
        val renamedFiles = ArrayList<Pair<Int, String>>()
        val random = Random()
        for (file in files) {
            val name = file.name
            if (name.startsWith(DATA_FILE)) {
                val tempFileName = "$TMP_NAME${DataFile.getCollectionCount(file)}-${random.nextInt()}"
                if (FileStore.rename(file, tempFileName))
                    renamedFiles.add(Pair(renamedFiles.size, tempFileName))
            } else if (name.startsWith(TMP_NAME))
                renamedFiles.add(Pair(renamedFiles.size, name))
            else if (name.length == 15 && name.startsWith("up"))
                delete(context, name)
        }

        for (item in renamedFiles) {
            val substr = item.second.substring(TMP_NAME.length)
            val separatorIndex = substr.indexOf(SEPARATOR)
            val collections = Integer.parseInt(substr.substring(0, separatorIndex))
            val name = DataFile.generateFileName(collections, item.first)
            if (!rename(context, item.second, name)) {
                Crashlytics.logException(Throwable("Failed to rename $"))
            }
        }

        Preferences.getPref(context).edit().putInt(PREF_DATA_FILE_INDEX, if (renamedFiles.size == 0) 0 else renamedFiles.size - 1).apply()
        currentDataFile = null
    }

    /**
     * Inspects all data files and returns the total size
     *
     * @return total size of data
     */
    fun recountData(context: Context): Long {
        val files = getDataFiles(context, 0) ?: return 0
        var size: Long = 0
        collectionsOnDevice = 0
        for (file in files) {
            size += file.length()
            collectionsOnDevice += DataFile.getCollectionCount(file)
        }

        Preferences.getPref(context).edit().putLong(PREF_COLLECTED_DATA_SIZE, size).apply()
        if (onDataChanged != null && approxSize != size)
            onDataChanged(context)
        approxSize = size
        return size
    }

    /**
     * Initializes approximate data size variable
     */
    private fun initSizeOfData(context: Context) {
        if (approxSize == -1L) {
            approxSize = Preferences.getPref(context).getLong(PREF_COLLECTED_DATA_SIZE, 0)
            collectionsOnDevice = Preferences.getPref(context).getInt(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD, 0)
        }
    }

    /**
     * Sets collection count
     * This method needs to be called with caution. Incorrect calling can cause smart uploader to malfunction.
     * Since issues would not affect tracking, it is public, because there needs to be some way to update collection count from upload function.
     *
     * @param context context
     * @param count   count of collections
     */
    fun setCollections(context: Context, count: Int) {
        Preferences.getPref(context).edit().putInt(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD, count).apply()
        collectionsOnDevice = count
    }

    /**
     * Gets saved size of data.
     *
     * @return returns saved data size from shared preferences.
     */
    fun sizeOfData(context: Context): Long {
        initSizeOfData(context)
        return approxSize
    }

    /**
     * Gets saved size of data.
     *
     * @return returns saved data size from shared preferences.
     */
    fun collectionCount(context: Context): Int {
        initSizeOfData(context)
        return collectionsOnDevice
    }

    /**
     * Increments approx size by value
     *
     * @param value value
     */
    fun incData(context: Context, value: Long, count: Int) {
        initSizeOfData(context)
        approxSize += value
        collectionsOnDevice += count
    }

    /**
     * @param fileName Name of file
     * @return Size of file
     */
    private fun sizeOf(context: Context, fileName: String): Long = file(context, fileName).length()


    /**
     * Clears all data files
     */
    fun deleteTrackedData(context: Context) {
        lockData()
        currentDataFile = null
        val sp = Preferences.getPref(context)
        sp.edit().remove(PREF_COLLECTED_DATA_SIZE).remove(PREF_DATA_FILE_INDEX).remove(Preferences.PREF_SCHEDULED_UPLOAD).remove(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD).apply()
        approxSize = 0
        collectionsOnDevice = 0
        val files = getDir(context).listFiles()

        for (file in files) {
            val name = file.name
            if (name.startsWith(DATA_FILE))
                if (!FileStore.delete(file))
                    Crashlytics.logException(RuntimeException("Failed to delete " + file.name))
        }
        onDataChanged(context)

        val bundle = Bundle()
        bundle.putString(
                FirebaseAssist.PARAM_SOURCE,
                "settings"
        )
        FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.CLEARED_DATA_EVENT, bundle)
        unlockData()
    }

    fun clearAll(context: Context) {
        currentDataFile = null
        FileStore.clearDirectory(getDir(context))
        Preferences.getPref(context).edit().remove(PREF_COLLECTED_DATA_SIZE).remove(PREF_DATA_FILE_INDEX).remove(Preferences.PREF_SCHEDULED_UPLOAD).remove(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD).apply()
        approxSize = 0
        collectionsOnDevice = 0

        onDataChanged(context)
    }

    fun clear(context: Context, predicate: (File) -> Boolean) {
        FileStore.clearDirectory(getDir(context), predicate)

        recountData(context)

        if (currentDataFile?.file?.exists() != true)
            currentDataFile = null
    }

    /**
     * Recursively deletes all files in a directory
     *
     * @param file File or directory
     * @return True if successful
     */
    fun recursiveDelete(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()
                    .filterNot { recursiveDelete(it) }
                    .forEach { return false }
        }
        return file.delete()
    }

    enum class SaveStatus {
        FILE_LOCKED,
        SAVE_FAILED,
        SAVE_SUCCESS,
        SAVE_SUCCESS_FILE_DONE
    }

    fun getCurrentDataFile(context: Context): DataFile? {
        if (currentDataFile == null)
            updateCurrentData(context)
        return currentDataFile
    }

    @Synchronized
    private fun updateCurrentData(context: Context) {
        //true or null
        if (currentDataFile?.isFull != false) {
            val index = Preferences.getPref(context).getInt(PREF_DATA_FILE_INDEX, 0)

            val files = getDir(context).listFiles { x -> x.name.startsWith("$DATA_FILE$index$SEPARATOR") }

            val fileName: String
            fileName = when {
                files.size > 1 -> throw java.lang.Exception("Something is wrong, crashing. Found ${files.size} based on \"$DATA_FILE$index$SEPARATOR\"")
                files.size == 1 -> files[0].name
                else -> DataFile.generateFileName(0, index)
            }
            currentDataFile = DataFile(FileStore.dataFile(getDir(context), fileName), index)
        }
    }

    /**
     * Saves rawData to file. File is determined automatically.
     *
     * @param rawData json array to be saved, without [ at the beginning
     * @return returns state value 2 - new file, saved successfully, 1 - error during saving, 0 - no new file, saved successfully
     */
    fun saveData(context: Context, rawData: Array<RawData>): SaveStatus {
        if (dataLocked)
            return SaveStatus.FILE_LOCKED

        //true or null
        if (currentDataFile?.isFull != false)
            updateCurrentData(context)

        return saveData(context, currentDataFile, rawData)
    }

    @Synchronized
    private fun saveData(context: Context, file: DataFile?, rawData: Array<RawData>): SaveStatus {
        val prevSize = file!!.size()
        if (file.addData(rawData)) {
            val currentSize = file.size()
            val editor = Preferences.getPref(context).edit()
            editor.putLong(PREF_COLLECTED_DATA_SIZE, Preferences.getPref(context).getLong(PREF_COLLECTED_DATA_SIZE, 0) + currentSize - prevSize)

            if (currentSize > Constants.MAX_DATA_FILE_SIZE) {
                file.close()
                editor.putInt(file.preference, Preferences.getPref(context).getInt(file.preference, 0) + 1).apply()
                updateCurrentData(context)
                return SaveStatus.SAVE_SUCCESS_FILE_DONE
            }

            editor.apply()
            return SaveStatus.SAVE_SUCCESS
        }
        return SaveStatus.SAVE_FAILED
    }

    /**
     * Removes all old recent uploads that are saved.
     */
    @Synchronized
    fun removeOldRecentUploads(context: Context) {
        val sp = Preferences.getPref(context)
        val oldestUpload = sp.getLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, -1)
        if (oldestUpload != -1L) {
            val days = Assist.getAgeInDays(oldestUpload).toLong()
            if (days > 30) {
                val adapter = moshi.adapter<List<UploadStats>>(List::class.java)
                val data = FileStore.loadAppendableJsonArray(file(context, RECENT_UPLOADS_FILE))
                        ?: return

                val stats = adapter.fromJson(data)?.toMutableList() ?: return
                val it = stats.iterator()

                while (it.hasNext()) {
                    val stat = it.next()
                    if (Assist.getAgeInDays(stat.time) > 30)
                        it.remove()
                }

                if (stats.isNotEmpty())
                    sp.edit().putLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, stats[0].time).apply()
                else
                    sp.edit().remove(Preferences.PREF_OLDEST_RECENT_UPLOAD).apply()

                try {
                    FileStore.saveAppendableJsonArray(file(context, RECENT_UPLOADS_FILE), adapter.toJson(stats), false)
                } catch (e: Exception) {
                    Crashlytics.logException(e)
                }

            }
        }
    }

    fun saveString(context: Context, fileName: String, data: String, append: Boolean): Boolean =
            FileStore.saveString(file(context, fileName), data, append)

    fun <T> saveAppendableJsonArray(context: Context, fileName: String, data: T, tClass: Class<T>, append: Boolean): Boolean =
            saveAppendableJsonArray(context, fileName, moshi.adapter(tClass).toJson(data), append)

    fun saveAppendableJsonArray(context: Context, fileName: String, data: String, append: Boolean): Boolean {
        return try {
            FileStore.saveAppendableJsonArray(file(context, fileName), data, append)
        } catch (e: MalformedJsonException) {
            Crashlytics.logException(e)
            false
        }

    }

    fun loadString(context: Context, fileName: String): String? =
            FileStore.loadString(file(context, fileName))

    fun loadAppendableJsonArray(context: Context, fileName: String): String? =
            FileStore.loadAppendableJsonArray(file(context, fileName))

    fun <T> loadLastFromAppendableJsonArray(context: Context, fileName: String, tClass: Class<T>): T? =
            FileStore.loadLastFromAppendableJsonArray(file(context, fileName), tClass)
}