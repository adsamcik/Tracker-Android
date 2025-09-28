package com.adsamcik.tracker.app.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider

private const val PREFS_NAME = "onboarding"
private const val PREF_COMPLETED = "completed"
private const val PREF_COMPLETED_TIME = "completed_time"

fun markOnboardingCompletedForTests() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_COMPLETED, true)
        .putLong(PREF_COMPLETED_TIME, System.currentTimeMillis())
        .commit()
}
