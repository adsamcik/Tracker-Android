package com.adsamcik.signalcollector.jobs

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.R.string.channel_other_id
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.notifications.Notifications
import com.adsamcik.signalcollector.signin.Signin
import com.crashlytics.android.Crashlytics
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.MultipartBody
import okhttp3.internal.http2.StreamResetException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * JobService for JobScheduler which should be triggered when network is available to upload feedback
 */
class FeedbackUploadJob : Worker() {
    override fun doWork(): Result {
        val summary = inputData.getString(SUMMARY)

        return runBlocking {
            val user = Signin.getUserAsync(applicationContext)
            if (user?.token == null || user.token.isBlank()) {
                notify(R.string.notification_feedback_user_not_signed, summary!!)
                return@runBlocking Result.FAILURE
            }
            val type = inputData.getInt(TYPE, -1)
            val description = inputData.getString(DESCRIPTION)

            if (summary == null || type < 0)
                return@runBlocking Result.FAILURE

            val builder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("manufacturer", Build.MANUFACTURER)
                    .addFormDataPart("model", Build.MODEL)
                    .addFormDataPart("summary", summary)
                    .addFormDataPart("type", type.toString())
            builder.addFormDataPart("description", description ?: "")

            return@runBlocking try {
                val request = Network.requestPOST(applicationContext, Network.URL_FEEDBACK, builder.build()).build()
                val result = Network.clientAuth(applicationContext, user.token).newCall(request).execute()
                if (result.isSuccessful) {
                    notify(R.string.notification_feedback_success, summary)
                    Result.SUCCESS
                } else
                    Result.RETRY
            } catch (e: StreamResetException) {
                Result.RETRY
            } catch (e: SocketTimeoutException) {
                Result.RETRY
            } catch (e: IOException) {
                Crashlytics.logException(e)
                Result.RETRY
            }
        }
    }

    private fun notify(@StringRes stringRes: Int, summary: String) {
        val context = applicationContext
        val nBuilder = NotificationCompat.Builder(context, context.getString(channel_other_id))
        nBuilder.setContentTitle(context.getString(stringRes))
        nBuilder.setSmallIcon(R.drawable.ic_feedback_black_24dp)
        nBuilder.setContentText(summary)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(Notifications.uniqueNotificationId(), nBuilder.build())
    }

    companion object {
        const val TAG = "FEEDBACK_UPLOAD"
        const val SUMMARY = "summary"
        const val DESCRIPTION = "desc"
        const val TYPE = "type"
    }

}