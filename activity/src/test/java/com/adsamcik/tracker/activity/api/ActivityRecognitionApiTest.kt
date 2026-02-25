package com.adsamcik.tracker.activity.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.adsamcik.tracker.activity.ActivityRecognitionWorker
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
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
    private lateinit var mockDatabase: AppDatabase
    private lateinit var mockSessionDao: SessionDataDao

    @BeforeEach
    fun setup() {
        mockkObject(Logger)
        every { Logger.logWithPreference(any(), any(), any()) } returns Unit

        mockDatabase = mockk(relaxed = true)
        mockSessionDao = mockk(relaxed = true)

        mockkObject(AppDatabase.Companion)
        every { AppDatabase.database(any()) } returns mockDatabase
        every { mockDatabase.sessionDao() } returns mockSessionDao

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
        fun `accesses session DAO from database`() {
            coEvery { mockSessionDao.getAll() } returns emptyList()

            ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

            // Give coroutine time to execute
            Thread.sleep(200)

            verify { AppDatabase.database(mockContext) }
            verify { mockDatabase.sessionDao() }
        }

        @Test
        fun `does not enqueue work when sessions list is empty`() {
            coEvery { mockSessionDao.getAll() } returns emptyList()

            ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

            Thread.sleep(200)

            val workInfos = WorkManager.getInstance(mockContext)
                .getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
                .get()
            workInfos.shouldBeEmpty()
        }

        @Test
        fun `filters sessions with negative ids`() {
            val positiveSessions = listOf(
                mockk<TrackerSession>(relaxed = true) { every { id } returns 1L },
                mockk<TrackerSession>(relaxed = true) { every { id } returns 2L }
            )
            coEvery { mockSessionDao.getAll() } returns positiveSessions

            ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

            Thread.sleep(200)

            // Positive IDs are filtered out (filter { it.id < 0 })
            val workInfos = WorkManager.getInstance(mockContext)
                .getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
                .get()
            workInfos.shouldBeEmpty()
        }

        @Test
        fun `enqueues work for sessions with negative ids`() {
            val sessions = listOf(
                mockk<TrackerSession>(relaxed = true) { every { id } returns -1L },
                mockk<TrackerSession>(relaxed = true) { every { id } returns -5L }
            )
            coEvery { mockSessionDao.getAll() } returns sessions

            ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

            Thread.sleep(300)

            val workInfos = WorkManager.getInstance(mockContext)
                .getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
                .get()
            workInfos shouldHaveSize 2
        }

        @Test
        fun `enqueues only negative id sessions from mixed list`() {
            val sessions = listOf(
                mockk<TrackerSession>(relaxed = true) { every { id } returns 10L },
                mockk<TrackerSession>(relaxed = true) { every { id } returns -3L },
                mockk<TrackerSession>(relaxed = true) { every { id } returns 20L },
                mockk<TrackerSession>(relaxed = true) { every { id } returns -7L }
            )
            coEvery { mockSessionDao.getAll() } returns sessions

            ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

            Thread.sleep(300)

            val workInfos = WorkManager.getInstance(mockContext)
                .getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
                .get()
            workInfos shouldHaveSize 2
        }

        @Test
        fun `obtains WorkManager from context`() {
            coEvery { mockSessionDao.getAll() } returns emptyList()

            ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

            Thread.sleep(200)

            // Verify WorkManager was successfully obtained (no exception thrown)
            val workInfos = WorkManager.getInstance(mockContext)
                .getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
                .get()
            workInfos.shouldBeEmpty()
        }
    }
}
