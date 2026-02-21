package com.adsamcik.tracker.activity.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.adsamcik.tracker.activity.ActivityRecognitionWorker
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
@DisplayName("ActivitySessionReceiver")
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class ActivitySessionReceiverTest {

	private val receiver = ActivitySessionReceiver()
	private val context: Context
		get() = ApplicationProvider.getApplicationContext()

	@BeforeEach
	fun setUp() {
		val config = Configuration.Builder()
			.setMinimumLoggingLevel(android.util.Log.DEBUG)
			.build()
		WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	private fun sessionEndedIntent(sessionId: Long): Intent = mockk {
		every { action } returns TrackerSession.ACTION_SESSION_ENDED
		every { getLongExtra(TrackerSession.RECEIVER_SESSION_ID, -1) } returns sessionId
		every { hasExtra(TrackerSession.RECEIVER_SESSION_ID) } returns (sessionId >= 0)
	}

	@Nested
	@DisplayName("onReceive")
	inner class OnReceive {

		@Test
		fun `enqueues recognition work when session ended with valid id`() {
			receiver.onReceive(context, sessionEndedIntent(42L))

			val workInfos = WorkManager.getInstance(context)
				.getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
				.get()
			workInfos shouldHaveSize 1
		}

		@Test
		fun `enqueues work for session id zero`() {
			receiver.onReceive(context, sessionEndedIntent(0L))

			val workInfos = WorkManager.getInstance(context)
				.getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
				.get()
			workInfos shouldHaveSize 1
		}

		@Test
		fun `enqueues exactly one work request per call`() {
			receiver.onReceive(context, sessionEndedIntent(1L))
			receiver.onReceive(context, sessionEndedIntent(2L))

			val workInfos = WorkManager.getInstance(context)
				.getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
				.get()
			workInfos shouldHaveSize 2
		}

		@Test
		fun `obtains WorkManager with provided context`() {
			receiver.onReceive(context, sessionEndedIntent(1L))

			// Verify WorkManager was successfully obtained (no exception thrown)
			val workInfos = WorkManager.getInstance(context)
				.getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
				.get()
			workInfos shouldHaveSize 1
		}

		@Test
		fun `does not enqueue work when session id is negative`() {
			mockkObject(com.adsamcik.tracker.shared.base.logging.ReporterFacade)
			every {
				com.adsamcik.tracker.shared.base.logging.ReporterFacade.report(any<Throwable>())
			} just runs

			val intent = sessionEndedIntent(-1L)

			receiver.onReceive(context, intent)

			val workInfos = WorkManager.getInstance(context)
				.getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
				.get()
			workInfos.shouldBeEmpty()
		}

		@Test
		fun `ignores intents with unrelated action`() {
			val intent: Intent = mockk {
				every { action } returns "com.example.UNRELATED"
			}

			receiver.onReceive(context, intent)

			val workInfos = WorkManager.getInstance(context)
				.getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
				.get()
			workInfos.shouldBeEmpty()
		}

		@Test
		fun `ignores intents with null action`() {
			val intent: Intent = mockk {
				every { action } returns null
			}

			receiver.onReceive(context, intent)

			val workInfos = WorkManager.getInstance(context)
				.getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
				.get()
			workInfos.shouldBeEmpty()
		}

		@Test
		fun `ignores session started action`() {
			val intent: Intent = mockk {
				every { action } returns TrackerSession.ACTION_SESSION_STARTED
			}

			receiver.onReceive(context, intent)

			val workInfos = WorkManager.getInstance(context)
				.getWorkInfosByTag(ActivityRecognitionWorker.WORK_TAG)
				.get()
			workInfos.shouldBeEmpty()
		}
	}
}
