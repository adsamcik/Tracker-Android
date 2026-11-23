package com.adsamcik.tracker.app.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.preferences.onboarding.DefaultOnboardingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

fun markOnboardingCompletedForTests() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    runBlocking {
        DefaultOnboardingRepository(context, Dispatchers.IO).markCompleted()
    }
}
