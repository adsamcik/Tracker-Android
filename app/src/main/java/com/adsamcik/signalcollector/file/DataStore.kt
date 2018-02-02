package com.adsamcik.signalcollector.file

import android.content.Context
import android.os.Bundle
import android.support.annotation.IntRange
import android.util.MalformedJsonException
import android.util.Pair
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.data.UploadStats
import com.adsamcik.signalcollector.enums.CloudStatus
import com.adsamcik.signalcollector.jobs.UploadJobService
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signals.signin.signin.Signin
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.FirebaseAssist
import com.adsamcik.signalcollector.utility.Preferences
import com.crashlytics.android.Crashlytics
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.*

object DataStore {
    val TAG = "SignalsDatastore"

    val RECENT_UPLOADS_FILE = "recentUploads"
    val DATA_FILE = "dataStore"
    val DATA_CACHE_FILE = "dataCacheFile"
    val PREF_DATA_FILE_INDEX = "saveFileID"
    val PREF_CACHE_FILE_INDEX = "saveCacheID"
    private val PREF_COLLECTED_DATA_SIZE = "totalSize"

    private var onDataChanged: (() -> Unit)? = null
    private var onUploadProgress: ((Int) -> Unit)? = null

    @Volatile private var approxSize: Long = -1
    @Volatile private var collectionsOnDevice = -1

    var currentDataFile: DataFile? = null
        private set

    private val collectionInDataFile = 0

    fun getDir(context: Context): File = context.filesDir

    fun file(context: Context, fileName: String): File = FileStore.file(getDir(context), fileName)

    /**
     * Call to invoke onDataChanged callback
     */
    private fun onDataChanged(context: Context) {
        if (Network.cloudStatus == CloudStatus.NO_SYNC_REQUIRED && sizeOfData(context) >= Constants.MIN_USER_UPLOAD_FILE_SIZE)
            Network.cloudStatus = CloudStatus.SYNC_AVAILABLE
        else if (Network.cloudStatus == CloudStatus.SYNC_AVAILABLE && sizeOfData(context) < Constants.MIN_USER_UPLOAD_FILE_SIZE)
            Network.cloudStatus = CloudStatus.NO_SYNC_REQUIRED

        onDataChanged?.invoke()
    }

    /**
     * Call to invoke onUploadProgress callback
     *
     * @param progress progress as int (0-100)
     */
    fun onUpload(context: Context, progress: Int) {
        if (progress == 100)
            Network.cloudStatus = if (sizeOfData(context) >= Constants.MIN_USER_UPLOAD_FILE_SIZE) CloudStatus.SYNC_AVAILABLE else CloudStatus.NO_SYNC_REQUIRED
        else if (progress == -1 && sizeOfData(context) > 0)
            Network.cloudStatus = CloudStatus.SYNC_AVAILABLE
        else
            Network.cloudStatus = CloudStatus.SYNC_IN_PROGRESS

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
     * Generates array of all data files
     *
     * @param context               context
     * @param lastFileSizeThreshold Include last datafile if it exceeds this size
     * @return array of datafile names
     */
    fun getDataFiles(context: Context, @IntRange(from = 0) lastFileSizeThreshold: Int): Array<File>? =
            getDir(context).listFiles { _, s -> s.startsWith(DATA_FILE) }

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
            Crashlytics.logException(RuntimeException("Failed to delete " + fileName))
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
        val tmpName = "5GeVPiYk6J"
        val files = getDir(context).listFiles()
        Arrays.sort(files) { a: File, b: File -> a.name.compareTo(b.name) }
        val renamedFiles = ArrayList<Pair<Int, String>>()
        for (file in files) {
            val name = file.name
            if (name.startsWith(DATA_FILE)) {
                val tempFileName = tmpName + DataFile.getCollectionCount(file)
                if (FileStore.rename(file, tempFileName))
                    renamedFiles.add(Pair(renamedFiles.size, tempFileName))
            }
        }

        for (item in renamedFiles)
            rename(context, item.second, DATA_FILE + item.first + DataFile.SEPARATOR + item.second.substring(tmpName.length))

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
    fun clearAllData(context: Context) {
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
        bundle.putString(FirebaseAssist.PARAM_SOURCE, "settings")
        FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.CLEARED_DATA_EVENT, bundle)
    }

    fun clearAll(context: Context) {
        currentDataFile = null
        FileStore.clearFolder(getDir(context))
        Preferences.getPref(context).edit().remove(PREF_COLLECTED_DATA_SIZE).remove(PREF_DATA_FILE_INDEX).remove(Preferences.PREF_SCHEDULED_UPLOAD).remove(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD).apply()
        approxSize = 0
        collectionsOnDevice = 0

        onDataChanged(context)
    }

    /**
     * Recursively deletes all files in a directory
     *
     * @param file File or directory
     * @return True if successfull
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
        SAVE_FAILED,
        SAVE_SUCCESS,
        SAVE_SUCCESS_FILE_DONE
    }

    fun getCurrentDataFile(context: Context): DataFile? {
        if (currentDataFile == null) {
            val userID = Signin.getUserID(context)
            updateCurrentData(context, if (userID == null) DataFile.CACHE else DataFile.STANDARD, userID)
        }
        return currentDataFile
    }

    @Synchronized private fun updateCurrentData(context: Context, @DataFile.FileType type: Long, userID: String?) {
        val dataFile: String
        val preference: String

        if (type == DataFile.STANDARD && userID == null)
            throw RuntimeException("Type should be cache")

        when (type) {
            DataFile.CACHE -> {
                dataFile = DATA_CACHE_FILE
                preference = PREF_CACHE_FILE_INDEX
            }
            DataFile.STANDARD -> {
                dataFile = DATA_FILE
                preference = PREF_DATA_FILE_INDEX
            }
            else -> {
                Crashlytics.logException(Throwable("Unknown type " + type))
                return
            }
        }

        if (currentDataFile?.type != type || currentDataFile!!.isFull) {
            val template = dataFile + Preferences.getPref(context).getInt(preference, 0)
            currentDataFile = DataFile(FileStore.dataFile(getDir(context), template), template, userID, type)
        }
    }

    /**
     * Saves rawData to file. File is determined automatically.
     *
     * @param rawData json array to be saved, without [ at the beginning
     * @return returns state value 2 - new file, saved succesfully, 1 - error during saving, 0 - no new file, saved successfully
     */
    fun saveData(context: Context, rawData: Array<RawData>): SaveStatus {
        val userID = Signin.getUserID(context)
        if (UploadJobService.isUploading || userID == null)
            updateCurrentData(context, DataFile.CACHE, userID)
        else
            updateCurrentData(context, DataFile.STANDARD, userID)
        return saveData(context, currentDataFile, rawData)
    }

    @Synchronized private fun writeTempData(context: Context) {
        val userId = Signin.getUserID(context)
        if (currentDataFile!!.type != DataFile.STANDARD || userId == null)
            return

        val files = getDir(context).listFiles { _, s -> s.startsWith(DATA_CACHE_FILE) }
        if (files.isNotEmpty()) {
            var newFileCount = files.size
            var i = Preferences.getPref(context).getInt(PREF_DATA_FILE_INDEX, 0)

            if (files[0].length() + currentDataFile!!.size() <= 1.25 * Constants.MAX_DATA_FILE_SIZE) {
                val tempFileName = files[0].name
                val indexOf = tempFileName.indexOf(" - ")
                var collectionCount = 0
                if (indexOf > 0) {
                    collectionCount = Integer.parseInt(tempFileName.substring(indexOf + 3))
                }

                val data = FileStore.loadString(files[0])!!
                if (!currentDataFile!!.addData(data, collectionCount))
                    return

                newFileCount--
                i++
                if (currentDataFile!!.isFull)
                    currentDataFile!!.close()
            } else {
                currentDataFile!!.close()
            }

            if (files.size > 1) {
                val currentDataIndex = Preferences.getPref(context).getInt(PREF_DATA_FILE_INDEX, 0)
                Preferences.getPref(context).edit().putInt(PREF_DATA_FILE_INDEX, i + newFileCount).putInt(PREF_CACHE_FILE_INDEX, 0).apply()
                var dataFile: DataFile
                while (i < files.size) {
                    val data = FileStore.loadString(files[0])!!
                    val nameTemplate = DATA_FILE + (currentDataIndex + i)
                    dataFile = DataFile(FileStore.dataFile(getDir(context), nameTemplate), nameTemplate, userId, DataFile.STANDARD)
                    if (!dataFile.addData(data, DataFile.getCollectionCount(files[0])))
                        throw RuntimeException()

                    if (i < files.size - 1)
                        dataFile.close()
                    i++
                }
            }

            files
                    .filterNot { FileStore.delete(it) }
                    .forEach { Crashlytics.logException(RuntimeException("Failed to delete " + it.name)) }
        }
    }

    @Synchronized private fun saveData(context: Context, file: DataFile?, rawData: Array<RawData>): SaveStatus {
        val prevSize = file!!.size()

        if (file.type == DataFile.STANDARD)
            writeTempData(context)

        if (file.addData(rawData)) {
            val currentSize = file.size()
            val editor = Preferences.getPref(context).edit()
            editor.putLong(PREF_COLLECTED_DATA_SIZE, Preferences.getPref(context).getLong(PREF_COLLECTED_DATA_SIZE, 0) + currentSize - prevSize)

            if (currentSize > Constants.MAX_DATA_FILE_SIZE) {
                file.close()
                editor.putInt(file.preference, Preferences.getPref(context).getInt(file.preference, 0) + 1).apply()
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
                val gson = Gson()
                val stats = gson.fromJson<ArrayList<UploadStats>>(FileStore.loadAppendableJsonArray(file(context, RECENT_UPLOADS_FILE)), object : TypeToken<List<UploadStats>>() {

                }.type) ?: return
                var i = 0
                while (i < stats.size) {
                    if (Assist.getAgeInDays(stats[i].time) > 30)
                        stats.removeAt(i--)
                    i++
                }

                if (stats.size > 0)
                    sp.edit().putLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, stats[0].time).apply()
                else
                    sp.edit().remove(Preferences.PREF_OLDEST_RECENT_UPLOAD).apply()

                try {
                    FileStore.saveAppendableJsonArray(file(context, RECENT_UPLOADS_FILE), gson.toJson(stats), false)
                } catch (e: Exception) {
                    Crashlytics.logException(e)
                }

            }
        }
    }

    fun saveString(context: Context, fileName: String, data: String, append: Boolean): Boolean =
            FileStore.saveString(file(context, fileName), data, append)

    fun <T> saveAppendableJsonArray(context: Context, fileName: String, data: T, append: Boolean): Boolean =
            saveAppendableJsonArray(context, fileName, Gson().toJson(data), append)

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
