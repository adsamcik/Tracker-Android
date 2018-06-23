package com.adsamcik.signalcollector.jobs

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.AsyncTask
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.edit
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.enums.ActionSource
import com.adsamcik.signalcollector.enums.CloudStatuses
import com.adsamcik.signalcollector.extensions.getInt
import com.adsamcik.signalcollector.extensions.jobScheduler
import com.adsamcik.signalcollector.file.Compress
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.file.FileStore
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.network.NetworkInterface
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.Constants.HOUR_IN_MILLISECONDS
import com.adsamcik.signalcollector.utility.Constants.MIN_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE
import com.adsamcik.signalcollector.utility.Constants.MIN_MAX_DIFF_BGUP_FILE_LIMIT_SIZE
import com.adsamcik.signalcollector.utility.Preferences
import com.crashlytics.android.Crashlytics
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.InvalidParameterException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/**
 * JobService used to handle uploading to server
 */
class UploadJobService : JobService() {
    private var worker: JobWorker? = null

    private fun removePersistence() = Preferences.getPref(this).edit {
        remove(Preferences.PREF_SCHEDULED_UPLOAD)
    }

    override fun onStartJob(jobParameters: JobParameters): Boolean {
        val scheduleSource = ActionSource.values()[jobParameters.extras.getInt(KEY_SOURCE)]
        if (scheduleSource == ActionSource.NONE)
            throw RuntimeException("Source cannot be NONE")

        if (!hasEnoughData(this, scheduleSource)) {
            removePersistence()
            return false
        }

        if (isUploading.getAndSet(true)) {
            removePersistence()
            return false
        }

        DataStore.onUpload(this, 0)
        val context = applicationContext

        val collectionsToUpload = Preferences.getPref(context).getInt(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD, 0)
        worker = JobWorker(context) { success ->
            if (success) {
                var collectionCount = Preferences.getPref(context).getInt(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD, 0)
                if (collectionCount < collectionsToUpload) {
                    collectionCount = 0
                    Crashlytics.logException(Throwable("There are less collections than thought"))
                } else
                    collectionCount -= collectionsToUpload
                DataStore.setCollections(this, collectionCount)
                DataStore.onUpload(this, 100)
                removePersistence()
            }
            isUploading.set(false)
            jobFinished(jobParameters, !success)
        }
        worker!!.execute(jobParameters)
        return true
    }

    override fun onStopJob(jobParameters: JobParameters): Boolean {
        isUploading.set(false)
        return if (worker?.status == AsyncTask.Status.FINISHED)
            false
        else {
            worker?.cancel(true)
            true
        }
    }

    private class JobWorker internal constructor(context: Context, private val callback: ((Boolean) -> Unit)?) : AsyncTask<JobParameters, Void, Boolean>() {
        private val context: WeakReference<Context> = WeakReference(context.applicationContext)

        private var tempZipFile: File? = null
        private var response: Response<Boolean>? = null
        private var call: Call<Boolean>? = null

        /**
         * Uploads data to server.
         *
         * @param file file to be uploaded
         */
        private fun upload(file: File?, token: String?, userID: String?): Boolean {
            if (file == null)
                throw InvalidParameterException("file is null")
            else if (token == null) {
                Crashlytics.logException(Throwable("Token is null"))
                return false
            }

            val formBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", Network.generateVerificationString(userID!!, file.length()), RequestBody.create(MEDIA_TYPE_ZIP, file))
                    .build()
            try {
                val retroClient = Retrofit.Builder().client(Network.client(token)).build()
                val networkInterface = retroClient.create(NetworkInterface::class.java)
                call = networkInterface.dataUpload(formBody)
                response = call!!.execute()
                val isSuccessful = response!!.isSuccessful
                if (isSuccessful)
                    return true

                val code = response!!.code()
                if (code >= 400)
                    Crashlytics.logException(Throwable("Upload failed $code"))
                return false
            } catch (e: IOException) {
                Crashlytics.logException(e)
                return false
            }

        }

        override fun doInBackground(vararg params: JobParameters): Boolean? {
            val source = ActionSource.values()[params[0].extras.getInt(KEY_SOURCE)]
            if (source == ActionSource.NONE) {
                Crashlytics.logException(RuntimeException("Source is none"))
                return false
            }

            val context = this.context.get()!!
            val files = DataStore.getDataFiles(context, if (source == ActionSource.USER) Constants.MIN_USER_UPLOAD_FILE_SIZE else Constants.MIN_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE)
            if (files == null) {
                Crashlytics.logException(Throwable("No files found. This should not happen. Upload initiated by " + source.name))
                DataStore.onUpload(context, -1)
                return false
            } else {
                DataStore.lockData()
                DataStore.getCurrentDataFile(context)!!.close()
                val zipName = "up" + System.currentTimeMillis()
                try {
                    val compress = Compress(DataStore.file(context, zipName))
                    compress += files
                    tempZipFile = compress.finish()
                } catch (e: IOException) {
                    Crashlytics.logException(e)
                    return false
                }

                return runBlocking {
                    val user = Signin.getUserAsync(context)

                    if (user != null) {
                        if (upload(tempZipFile, user.token, user.id)) {
                            for (file in files) {
                                FileStore.delete(file)
                            }

                            if (!tempZipFile!!.delete())
                                tempZipFile!!.deleteOnExit()

                            DataStore.recountData(context)
                            return@runBlocking true
                        } else {
                            return@runBlocking false
                        }
                    } else {
                        return@runBlocking false
                    }
                }
            }
        }

        override fun onPostExecute(result: Boolean) {
            super.onPostExecute(result)
            val ctx = context.get()!!
            DataStore.cleanup(ctx)
            DataStore.unlockData()

            if (!result) {
                DataStore.onUpload(ctx, -1)
            }

            if (tempZipFile != null && !FileStore.delete(tempZipFile))
                Crashlytics.logException(IOException("Upload zip file was not deleted"))

            callback?.invoke(result)
        }

        override fun onCancelled() {
            val context = this.context.get()!!
            DataStore.cleanup(context)
            DataStore.recountData(context)
            DataStore.unlockData()

            if (tempZipFile != null)
                FileStore.delete(tempZipFile)

            call?.cancel()

            callback?.invoke(call != null && call!!.isExecuted && !call!!.isCanceled)

            super.onCancelled()
        }
    }

    companion object {
        private const val TAG = "SignalsUploadService"
        private const val KEY_SOURCE = "source"
        private val MEDIA_TYPE_ZIP = MediaType.parse("application/zip")
        private const val MIN_NO_ACTIVITY_DELAY = HOUR_IN_MILLISECONDS

        /**
         * Id of the schedule job
         */
        const val SCHEDULE_UPLOAD_JOB_ID = 1921109

        /**
         * Id of the upload job
         */
        const val UPLOAD_JOB_ID = 2110

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
                    val jb = prepareBuilder(UPLOAD_JOB_ID, context, source)
                    addNetworkTypeRequest(context, source, jb)

                    val scheduler = context.jobScheduler
                    if (scheduler.schedule(jb.build()) == JobScheduler.RESULT_FAILURE)
                        return false
                    updateUploadScheduleSource(context, source)
                    Network.cloudStatus = CloudStatuses.SYNC_SCHEDULED

                    scheduler.cancel(SCHEDULE_UPLOAD_JOB_ID)

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
                val scheduler = context.jobScheduler
                if (!hasJobWithID(scheduler, UPLOAD_JOB_ID)) {
                    val jb = prepareBuilder(SCHEDULE_UPLOAD_JOB_ID, context, ActionSource.BACKGROUND)

                    jb.setMinimumLatency(calculateScheduleDelay(dataSize))

                    addNetworkTypeRequest(context, ActionSource.BACKGROUND, jb)
                    updateUploadScheduleSource(context, ActionSource.BACKGROUND)

                    scheduler.schedule(jb.build())
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

        private fun hasJobWithID(jobScheduler: JobScheduler, id: Int): Boolean {
            return if (Build.VERSION.SDK_INT >= 24)
                jobScheduler.getPendingJob(id) != null
            else
                jobScheduler.allPendingJobs.any { it.id == id }

        }

        private fun canUpload(context: Context, source: ActionSource): Boolean {
            val autoUpload = Preferences.getPref(context).getInt(context, R.string.settings_uploading_network_key, R.string.settings_uploading_network_default)
            return (autoUpload > 0 || source == ActionSource.USER) && Assist.hasAgreedToPrivacyPolicy(context)
        }

        private fun prepareBuilder(id: Int, context: Context, source: ActionSource): JobInfo.Builder {
            val jobBuilder = JobInfo.Builder(id, ComponentName(context, UploadJobService::class.java))
            jobBuilder.setPersisted(true)

            if (Build.VERSION.SDK_INT >= 26)
                jobBuilder.setRequiresBatteryNotLow(true)

            jobBuilder.setRequiresDeviceIdle(false)

            val pb = PersistableBundle(1)
            pb.putInt(KEY_SOURCE, source.ordinal)
            jobBuilder.setExtras(pb)
            return jobBuilder
        }

        private fun addNetworkTypeRequest(context: Context, source: ActionSource, jobBuilder: JobInfo.Builder) {
            if (source == ActionSource.USER) {
                jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            } else {
                if (Preferences.getPref(context).getInt(context, R.string.settings_uploading_network_key, R.string.settings_uploading_network_default) == 2) {
                    if (Build.VERSION.SDK_INT >= 24)
                        jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NOT_ROAMING)
                    else
                        jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                } else
                    jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
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
            context.jobScheduler.cancel(SCHEDULE_UPLOAD_JOB_ID)
            updateUploadScheduleSource(context, ActionSource.NONE)
        }
    }
}