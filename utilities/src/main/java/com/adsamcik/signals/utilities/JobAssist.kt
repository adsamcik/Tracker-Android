package com.adsamcik.signals.utilities

import android.app.job.JobScheduler
import android.content.Context

fun scheduler(context: Context) : JobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler