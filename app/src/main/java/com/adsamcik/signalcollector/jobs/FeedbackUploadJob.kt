package com.adsamcik.signalcollector.jobs

import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.AsyncTask
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.R.string.channel_other_id
import com.adsamcik.signalcollector.R.string.notification_feedback_success
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.notifications.Notifications
import com.adsamcik.signalcollector.signin.Signin
import com.crashlytics.android.Crashlytics
import kotlinx.coroutines.experimental.launch
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.internal.http2.StreamResetException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * JobService for JobScheduler which should be triggered when network is available to upload feedback
 */
class FeedbackUploadJob : JobService() {
    private var worker: UploadTask? = null

    override fun onStopJob(params: JobParameters?): Boolean {
        if (worker != null) {
            if (worker!!.status == AsyncTask.Status.FINISHED)
                return false
            else
                worker!!.cancel(true)
        }
        return true
    }

    override fun onStartJob(params: JobParameters): Boolean {
        launch {
            val summary = params.extras[SUMMARY] as String

            val user = Signin.getUserAsync(this@FeedbackUploadJob)
            if (user?.token == null || user.token.isBlank()) {
                notify(R.string.notification_feedback_user_not_signed, summary)
                jobFinished(params, false)
            } else {
                val type = params.extras[TYPE] as Int
                val description = params.extras[DESCRIPTION] as String
                worker = UploadTask(this@FeedbackUploadJob, user.token) {
                    if (it)
                        notify(notification_feedback_success, summary)
                    jobFinished(params, !it)
                }
                worker!!.execute(summary, type.toString(), description)
            }
        }
        return true
    }

    private fun notify(@StringRes stringRes: Int, summary: String) {
        val nBuilder = NotificationCompat.Builder(this@FeedbackUploadJob, getString(channel_other_id))
        nBuilder.setContentTitle(getString(stringRes))
        nBuilder.setSmallIcon(R.drawable.ic_feedback_black_24dp)
        nBuilder.setContentText(summary)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(Notifications.uniqueNotificationId(), nBuilder.build())
    }

    private class UploadTask(context: Context,
                             val token: String,
                             val onFinished: (Boolean) -> Unit) : AsyncTask<String, Void, Boolean>() {
        val client = Network.client(token)
        val request = Network.requestPOSTAuth(context, Network.URL_FEEDBACK)

        /**
         * Summary = param[0]
         * Type = param[1]
         * Description = param[2]
         */
        override fun doInBackground(vararg params: String): Boolean {
            if (params.size != 3) {
                return false
            }
            //todo stop using auth body, it's not needed and is replaced with cookies
            val builder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("manufacturer", Build.MANUFACTURER)
                    .addFormDataPart("model", Build.MODEL)
                    .addFormDataPart("summary", params[0])
                    .addFormDataPart("type", params[1])
            builder.addFormDataPart("description", if (params[2].isNotEmpty()) params[2] else "")

            return try {
                val result = client.newCall(Network.requestPOSTAuth(Network.URL_FEEDBACK, builder.build())).execute()
                result.isSuccessful
            } catch (e: StreamResetException) {
                false
            } catch (e: SocketTimeoutException) {
                false
            } catch (e: IOException) {
                Crashlytics.logException(e)
                false
            }
        }

        override fun onCancelled(result: Boolean?) {
            super.onCancelled(result)
            onFinished.invoke(result ?: false)
        }

        override fun onPostExecute(result: Boolean) {
            super.onPostExecute(result)
            onFinished.invoke(result)
        }
    }

    companion object {
        const val SUMMARY = "summary"
        const val DESCRIPTION = "desc"
        const val TYPE = "type"
    }

}