package com.adsamcik.signalcollector.jobs

import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.AsyncTask
import android.support.v4.app.NotificationCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.R.string.*
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.signin.Signin
import com.crashlytics.android.Crashlytics
import kotlinx.coroutines.experimental.launch

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
                throw RuntimeException("User token is null")
            else {
                val summary = params.extras[SUMMARY] as String
                val type = params.extras[TYPE] as Int
                val description = params.extras[DESCRIPTION] as String
                worker = UploadTask(this@FeedbackUploadJob, user.token) {
                    notify(it)
                    jobFinished(params, !it)
                }
                worker!!.execute(summary, type.toString(), description)
            }
        }
        return true
    }

    private fun notify(status: Boolean) {
        val nBuilder = NotificationCompat.Builder(this@FeedbackUploadJob, getString(channel_other_id))
        if (status)
            nBuilder.setContentTitle(getString(notification_feedback_success))
        else
            nBuilder.setContentTitle(getString(notification_feedback_failed))
        nBuilder.setSmallIcon(R.drawable.ic_signals)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(33332225, nBuilder.build())
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

            val result = client.newCall(Network.requestPOST(Network.URL_FEEDBACK, builder.build())).execute()
            if (!result.isSuccessful)
                Crashlytics.logException(Throwable(result.message()))
            return result.isSuccessful
        }

        override fun onCancelled(result: Boolean) {
            super.onCancelled(result)
            onFinished.invoke(result)
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