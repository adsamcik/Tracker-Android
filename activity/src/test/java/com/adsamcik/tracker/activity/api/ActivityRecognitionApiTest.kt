package com.adsamcik.tracker.activity.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.adsamcik.tracker.activity.ActivityRecognitionWorker
import com.adsamcik.tracker.logger.Logger
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
@DisplayName("ActivityRecognitionApi")
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class ActivityRecognitionApiTest {

    private val mockContext: Context
        get() = ApplicationProvider.getApplicationContext()

    @BeforeEach
    fun setup() {
        mockkObject(Logger)
        every { Logger.logWithPreference(any(), any(), any()) } returns Unit

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(mockContext, config)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // -----------------------------------------------------------------------
    // Object identity
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Object identity")
    inner class ObjectIdentityTests {

        @Test
        fun `is a singleton object`() {
            val ref1 = ActivityRecognitionApi
            val ref2 = ActivityRecognitionApi

            ref1 shouldBe ref2
        }
    }

    // -----------------------------------------------------------------------
    // rerunRecognitionForAll
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("rerunRecognitionForAll")
    inner class RerunTests {

        @Test
        fun `enqueues exactly one batch worker`() {
            ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

            val workInfos = WorkManager.getInstance(mockContext)
                .getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
                .get()
            workInfos shouldHaveSize 1
        }

        @Test
        fun `enqueued worker is in ENQUEUED state`() {
            ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

            val workInfos = WorkManager.getInstance(mockContext)
                .getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
                .get()
            workInfos.first().state shouldBe WorkInfo.State.ENQUEUED
        }

        @Test
        fun `obtains WorkManager from context`() {
            ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

            // Verify WorkManager was successfully obtained (no exception thrown)
            val workInfos = WorkManager.getInstance(mockContext)
                .getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
                .get()
            workInfos shouldHaveSize 1
        }
    }
}
