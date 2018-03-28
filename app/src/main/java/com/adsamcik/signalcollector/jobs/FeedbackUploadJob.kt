package com.adsamcik.signalcollector.jobs

import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.AsyncTask
import android.support.annotation.StringRes
import android.support.v4.app.NotificationCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.R.string.*
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.notifications.Notifications
import com.adsamcik.signalcollector.signin.Signin
import kotlinx.coroutines.experimental.launch
import okhttp3.internal.http2.StreamResetException

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
            val user = Signin.getUserAsync(this@FeedbackUploadJob)
            if (user?.token == null || user.token.isBlank())
                notify(R.string.notification_feedback_user_not_signed)
            else {
                val summary = params.extras[SUMMARY] as String
                val type = params.extras[TYPE] as Int
                val description = params.extras[DESCRIPTION] as String
                worker = UploadTask(this@FeedbackUploadJob, user.token) {
                    if (it)
                        notify(notification_feedback_success)
                    jobFinished(params, !it)
                }
                worker!!.execute(summary, type.toString(), description)
            }
        }
        return true
    }

    private fun notify(@StringRes stringRes: Int) {
        val nBuilder = NotificationCompat.Builder(this@FeedbackUploadJob, getString(channel_other_id))
        nBuilder.setContentTitle(getString(stringRes))
        nBuilder.setSmallIcon(R.drawable.ic_signals)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(Notifications.uniqueNotificationId(), nBuilder.build())
    }

    private class UploadTask(context: Context,
                             val token: String,
                             val onFinished: (Boolean) -> Unit) : AsyncTask<String, Void, Boolean>() {
        val client = Network.client(context, token)

        /**
         * Summary = param[0]
         * Type = param[1]
         * Description = param[2]
         */
        override fun doInBackground(vararg params: String): Boolean {
            if (params.size != 3) {
                return false
            }
            val builder = Network.generateAuthBody(token).addFormDataPart("summary", params[0]).addFormDataPart("type", params[1])
            builder.addFormDataPart("description", if (params[2].isNotEmpty()) params[2] else "")

            return try {
                val result = client.newCall(Network.requestPOST(Network.URL_FEEDBACK, builder.build())).execute()
                result.isSuccessful
            } catch (e: StreamResetException) {
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