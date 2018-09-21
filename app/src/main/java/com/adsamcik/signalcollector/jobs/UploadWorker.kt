package com.adsamcik.signalcollector.jobs

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.work.*
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.enums.ActionSource
import com.adsamcik.signalcollector.enums.CloudStatuses
import com.adsamcik.signalcollector.extensions.getInt
import com.adsamcik.signalcollector.file.Compress
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.file.FileStore
import com.adsamcik.signalcollector.network.Jwt
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.Constants.HOUR_IN_MILLISECONDS
import com.adsamcik.signalcollector.utility.Constants.MIN_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE
import com.adsamcik.signalcollector.utility.Constants.MIN_MAX_DIFF_BGUP_FILE_LIMIT_SIZE
import com.adsamcik.signalcollector.utility.DeviceId
import com.adsamcik.signalcollector.utility.Preferences
import com.crashlytics.android.Crashlytics
import com.squareup.moshi.Moshi
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.io.File
import java.io.IOException
import java.security.InvalidParameterException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/**
 * JobService used to handle uploading to server
 */
class UploadWorker : Worker() {
    override fun doWork(): Result {
        val scheduleSource = ActionSource.values()[inputData.getInt(KEY_SOURCE, 0)]
        if (scheduleSource == ActionSource.NONE)
            throw RuntimeException("Source cannot be NONE")

        val context = applicationContext

        if (!hasEnoughData(context, scheduleSource)) {
            removePersistence()
            return Result.FAILURE
        }

        if (isUploading.getAndSet(true)) {
            removePersistence()
            return Result.FAILURE
        }

        DataStore.onUpload(context, 0)

        val file = preUpload(scheduleSource) ?: return Result.FAILURE

        val result = upload(file)

        val collectionsToUpload = Preferences.getPref(context).getInt(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD, 0)

        postUpload(file, collectionsToUpload, result)


        return result
    }

    private fun removePersistence() = Preferences.getPref(applicationContext).edit {
        remove(Preferences.PREF_SCHEDULED_UPLOAD)
    }

    /**
     * Uploads data to server.
     *
     * @param file file to be uploaded
     */
    private fun upload(file: File?): Result {
        if (file == null)
            throw InvalidParameterException("file is null")

        val context = applicationContext

        var token: String? = null
        runBlocking {
            token = Jwt.getToken(context)
        }

        if (token == null) {
            Crashlytics.logException(Throwable("Token is null"))
            return Result.FAILURE
        }

        val userID = Signin.getUserID(context) ?: return Result.FAILURE

        val adapter = Moshi.Builder().build().adapter(DeviceId::class.java)
        val deviceId = DeviceId(Build.MANUFACTURER, Build.MODEL, userID)
        val deviceIdJson = adapter.toJson(deviceId)

        val formBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("deviceID", deviceIdJson)
                .addFormDataPart("API", Build.VERSION.SDK_INT.toString())
                .addFormDataPart("file", Network.generateVerificationString(userID, file.length()), RequestBody.create(MEDIA_TYPE_ZIP, file))
                .build()

        val request = Network.requestPOST(context, Network.URL_DATA_UPLOAD, formBody).build()
        try {
            val call = Network.clientAuth(context, token).newCall(request)
            val response = call.execute()
            val code = response.code()
            val isSuccessful = response.isSuccessful
            response.close()
            if (isSuccessful)
                return Result.SUCCESS

            if (code >= 400)
                Crashlytics.logException(Throwable("Upload failed $code"))
            return Result.RETRY
        } catch (e: IOException) {
            Crashlytics.logException(e)
            return Result.RETRY
        }

    }

    private fun preUpload(source: ActionSource): File? {
        val context = applicationContext
        val files = DataStore.getDataFiles(context, if (source == ActionSource.USER) Constants.MIN_USER_UPLOAD_FILE_SIZE else Constants.MIN_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE)
        if (files == null) {
            Crashlytics.logException(Throwable("No files found. This should not happen. Upload initiated by " + source.name))
            DataStore.onUpload(context, -1)
            return null
        } else {
            DataStore.lockData()
            DataStore.getCurrentDataFile(context)!!.close()
            val zipName = "up" + System.currentTimeMillis()
            return try {
                val compress = Compress(DataStore.file(context, zipName))
                compress += files
                compress.finish()
            } catch (e: IOException) {
                Crashlytics.logException(e)
                null
            }
        }
    }

    private fun postUpload(tempZipFile: File, collectionsToUpload: Int, result: Result) {
        val context = applicationContext
        DataStore.cleanup(context)
        DataStore.unlockData()

        if (result == Result.RETRY || result == Result.FAILURE) {
            DataStore.onUpload(context, -1)
        }

        if (!FileStore.delete(tempZipFile))
            Crashlytics.logException(IOException("Upload zip file was not deleted"))

        if (result == Result.SUCCESS) {
            var collectionCount = Preferences.getPref(context).getInt(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD, 0)
            if (collectionCount < collectionsToUpload) {
                collectionCount = 0
                Crashlytics.logException(Throwable("There are less collections than thought"))
            } else
                collectionCount -= collectionsToUpload
            DataStore.setCollections(context, collectionCount)
            DataStore.onUpload(context, 100)
            removePersistence()
        }
        isUploading.set(false)
    }

    companion object {
        private const val TAG = "SignalsUploadService"
        private const val KEY_SOURCE = "source"
        private val MEDIA_TYPE_ZIP = MediaType.parse("application/zip")
        private const val MIN_NO_ACTIVITY_DELAY = HOUR_IN_MILLISECONDS

        /**
         * Id of the schedule job
         */
        const val SCHEDULE_UPLOAD_JOB_TAG = "uploadSchedule"

        /**
         * Id of the upload job
         */
        const val UPLOAD_TAG = "upload"

        /**
         * Returns true if upload is currently in progress
         */
        var isUploading = AtomicBoolean(false)
            private set

        /**
         * Returns current upload schedule source from persistent storage
         */
        fun getUploadScheduled(context: Context): ActionSource {
            val preferences = Preferences.getPref(context)
            val value = preferences.getInt(Preferences.PREF_SCHEDULED_UPLOAD, -1)
            return if (value >= 0)
                ActionSource.values()[value]
            else
                ActionSource.NONE

        }

        /**
         * Requests upload
         * Call this when you want to auto-upload
         *
         * @param context Non-null context
         * @param source  Source that started the upload
         * @return Success
         */
        fun requestUpload(context: Context, source: ActionSource): Boolean {
            if (source == ActionSource.NONE)
                throw InvalidParameterException("Upload source can't be NONE.")
            else if (isUploading.get())
                return false

            if (hasEnoughData(context, source)) {
                if (canUpload(context, source)) {
                    val work = prepareBuilder(context, source)

                    work.addTag(UPLOAD_TAG)

                    WorkManager.getInstance().enqueue(work.build())

                    updateUploadScheduleSource(context, source)
                    Network.cloudStatus = CloudStatuses.SYNC_SCHEDULED

                    return true
                }
                return false
            }
            return false
        }

        /**
         * Request upload to be scheduled based on current settings
         *
         * @param context context
         */
        fun requestUploadSchedule(context: Context) {
            val dataSize = DataStore.sizeOfData(context)
            if (canUpload(context, ActionSource.BACKGROUND) &&
                    hasEnoughData(dataSize, ActionSource.BACKGROUND)) {
                if (!hasUploadJob()) {
                    val work = prepareBuilder(context, ActionSource.BACKGROUND)
                    work.addTag(SCHEDULE_UPLOAD_JOB_TAG)
                    work.setInitialDelay(calculateScheduleDelay(dataSize), TimeUnit.SECONDS)

                    updateUploadScheduleSource(context, ActionSource.BACKGROUND)

                    WorkManager.getInstance().enqueue(work.build())
                }
            }
        }

        /**
         * Calculates delay the schedule should have before triggering the upload
         */
        private fun calculateScheduleDelay(dataSize: Long): Long {
            if (dataSize < Constants.MIN_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE)
                throw RuntimeException("Data size is less than minimum allowed value")

            if (dataSize >= Constants.MAX_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE)
                return MIN_NO_ACTIVITY_DELAY

            //12 hours max delay is target
            val dist = MIN_MAX_DIFF_BGUP_FILE_LIMIT_SIZE / (dataSize - MIN_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE).toDouble() * 1.5

            return (MIN_NO_ACTIVITY_DELAY * dist).roundToLong()
        }

        private fun hasUploadJob(): Boolean {
            val list = WorkManager.getInstance().getStatusesByTag(UPLOAD_TAG).value
            return list != null && list.size > 0
        }

        private fun canUpload(context: Context, source: ActionSource): Boolean {
            val autoUpload = Preferences.getPref(context).getInt(context, R.string.settings_uploading_network_key, R.string.settings_uploading_network_default)
            return (autoUpload > 0 || source == ActionSource.USER) && Assist.hasAgreedToPrivacyPolicy(context)
        }

        private fun prepareBuilder(context: Context, source: ActionSource): OneTimeWorkRequest.Builder {

            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true)

            addNetworkTypeRequest(context, source, constraints)

            val workBuilder = OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(constraints.build())

            val inputData = workDataOf(Pair(KEY_SOURCE, source.ordinal))

            workBuilder.setInputData(inputData)
            return workBuilder
        }

        private fun addNetworkTypeRequest(context: Context, source: ActionSource, constraints: Constraints.Builder) {
            if (source == ActionSource.USER) {
                constraints.setRequiredNetworkType(NetworkType.CONNECTED)
            } else {
                if (Preferences.getPref(context).getInt(context, R.string.settings_uploading_network_key, R.string.settings_uploading_network_default) == 2)
                    constraints.setRequiredNetworkType(NetworkType.NOT_ROAMING)
                else
                    constraints.setRequiredNetworkType(NetworkType.UNMETERED)
            }
        }

        private fun hasEnoughData(context: Context, source: ActionSource): Boolean = hasEnoughData(DataStore.sizeOfData(context), source)

        private fun hasEnoughData(dataSize: Long, source: ActionSource): Boolean =
                when (source) {
                    ActionSource.BACKGROUND -> dataSize >= Constants.MIN_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE
                    ActionSource.USER -> dataSize >= Constants.MIN_USER_UPLOAD_FILE_SIZE
                    else -> false
                }

        private fun updateUploadScheduleSource(context: Context, uss: ActionSource) {
            Preferences.getPref(context).edit {
                putInt(Preferences.PREF_SCHEDULED_UPLOAD, uss.ordinal)
            }
        }

        /**
         * Cancels any job tak could be scheduled
         */
        fun cancelUploadSchedule(context: Context) {
            WorkManager.getInstance().cancelAllWorkByTag(SCHEDULE_UPLOAD_JOB_TAG)
            updateUploadScheduleSource(context, ActionSource.NONE)
        }
    }
}