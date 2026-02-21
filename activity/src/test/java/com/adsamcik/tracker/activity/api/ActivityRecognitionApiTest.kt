package com.adsamcik.tracker.activity.api

import android.content.Context
import androidx.work.WorkManager
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
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

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("ActivityRecognitionApi")
class ActivityRecognitionApiTest {

    private lateinit var mockContext: Context
    private lateinit var mockDatabase: AppDatabase
    private lateinit var mockSessionDao: SessionDataDao
    private lateinit var mockWorkManager: WorkManager

    @BeforeEach
    fun setup() {
        mockkObject(Logger)
        every { Logger.logWithPreference(any(), any(), any()) } returns Unit

        mockDatabase = mockk(relaxed = true)
        mockSessionDao = mockk(relaxed = true)
        mockWorkManager = mockk(relaxed = true)

        mockkObject(AppDatabase.Companion)
        every { AppDatabase.database(any()) } returns mockDatabase
        every { mockDatabase.sessionDao() } returns mockSessionDao

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockWorkManager

        mockContext = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockContext
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

            verify(exactly = 0) { mockWorkManager.enqueue(any<androidx.work.WorkRequest>()) }
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
            verify(exactly = 0) { mockWorkManager.enqueue(any<androidx.work.WorkRequest>()) }
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

            verify(exactly = 2) { mockWorkManager.enqueue(any<androidx.work.WorkRequest>()) }
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

            verify(exactly = 2) { mockWorkManager.enqueue(any<androidx.work.WorkRequest>()) }
        }

        @Test
        fun `obtains WorkManager from context`() {
            coEvery { mockSessionDao.getAll() } returns emptyList()

            ActivityRecognitionApi.rerunRecognitionForAll(mockContext)

            Thread.sleep(200)

            verify { WorkManager.getInstance(mockContext) }
        }
    }
}
