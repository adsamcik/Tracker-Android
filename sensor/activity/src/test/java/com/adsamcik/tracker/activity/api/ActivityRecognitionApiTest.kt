package com.adsamcik.tracker.activity.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.adsamcik.tracker.activity.ActivityRecognitionWorker
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityRecognitionApiTest {

    private val mockContext: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(mockContext, config)
    }

    // -----------------------------------------------------------------------
    // Object identity
    // -----------------------------------------------------------------------

    @Test
    fun `is a singleton object`() {
        val ref1 = ActivityRecognitionApi
        val ref2 = ActivityRecognitionApi

        ref1 shouldBe ref2
    }

    // -----------------------------------------------------------------------
    // rerunRecognitionForAll
    // -----------------------------------------------------------------------

    @Test
    fun `rerunRecognitionForAll enqueues exactly one batch worker`() {
        ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

        val workInfos = WorkManager.getInstance(mockContext)
            .getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
            .get()
        workInfos shouldHaveSize 1
    }

    @Test
    fun `rerunRecognitionForAll enqueued worker is in ENQUEUED state`() {
        ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

        val workInfos = WorkManager.getInstance(mockContext)
            .getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
            .get()
        workInfos.first().state shouldBe WorkInfo.State.ENQUEUED
    }

    @Test
    fun `rerunRecognitionForAll obtains WorkManager from context`() {
        ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

        // Verify WorkManager was successfully obtained (no exception thrown)
        val workInfos = WorkManager.getInstance(mockContext)
            .getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
            .get()
        workInfos shouldHaveSize 1
    }
}
