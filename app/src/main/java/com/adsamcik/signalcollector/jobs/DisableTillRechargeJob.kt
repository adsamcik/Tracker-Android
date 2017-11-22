package com.adsamcik.signalcollector.jobs

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobScheduler.RESULT_SUCCESS
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.adsamcik.signalcollector.services.TrackerService
import com.adsamcik.signalcollector.utility.Preferences

class DisableTillRechargeJob : JobService() {
    override fun onStartJob(jobParameters: JobParameters): Boolean {
        Log.d("Signals", "plugged")
        updatePreference(this, false)
        return false
    }

    override fun onStopJob(jobParameters: JobParameters): Boolean = false

    companion object {
        private const val JOB_DISABLE_TILL_RECHARGE_ID = 58946

        private fun updatePreference(context: Context, value: Boolean) {
            Preferences.getPref(context).edit().putBoolean(Preferences.PREF_STOP_TILL_RECHARGE, value).apply()
        }

        fun stopTillRecharge(context: Context) : Boolean {
            val scheduler = scheduler(context)
            val jobBuilder = JobInfo.Builder(JOB_DISABLE_TILL_RECHARGE_ID, ComponentName(context, DisableTillRechargeJob::class.java))
            jobBuilder.setPersisted(true).setRequiresCharging(true).setRequiresDeviceIdle(false)
            if(scheduler.schedule(jobBuilder.build()) == RESULT_SUCCESS) {
                updatePreference(context, true)
                if (TrackerService.isRunning)
                    context.stopService(Intent(context, TrackerService::class.java))
                return true
            } else
                Log.e("Signals", "failed to schedule job")
            return false
        }

        fun enableTracking(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_DISABLE_TILL_RECHARGE_ID)
            updatePreference(context, false)
        }
    }
}
