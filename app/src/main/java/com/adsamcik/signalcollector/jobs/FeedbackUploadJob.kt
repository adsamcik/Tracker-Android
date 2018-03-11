package com.adsamcik.signalcollector.jobs

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.AsyncTask
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.signin.Signin
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
            if (user?.token != null) {
                val summary = params.extras[SUMMARY] as String
                val type = params.extras[TYPE] as Int
                val description = params.extras[DESCRIPTION] as String
                worker = UploadTask(this@FeedbackUploadJob, user.token) {
                    jobFinished(params, !it)
                }
                worker!!.execute(summary, type.toString(), description)
            }
        }
        return true
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