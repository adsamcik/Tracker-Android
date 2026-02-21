package com.adsamcik.tracker.activity.receiver

import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
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
@DisplayName("ActivitySessionReceiver")
class ActivitySessionReceiverTest {

	private val receiver = ActivitySessionReceiver()
	private val context: Context = mockk(relaxed = true) {
		every { applicationContext } returns this@mockk
	}
	private val workManager: WorkManager = mockk(relaxed = true)

	@BeforeEach
	fun setUp() {
		mockkStatic(WorkManager::class)
		every { WorkManager.getInstance(any()) } returns workManager
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

			verify(exactly = 1) { workManager.enqueue(any<OneTimeWorkRequest>()) }
		}

		@Test
		fun `enqueues work for session id zero`() {
			receiver.onReceive(context, sessionEndedIntent(0L))

			verify(exactly = 1) { workManager.enqueue(any<OneTimeWorkRequest>()) }
		}

		@Test
		fun `enqueues exactly one work request per call`() {
			receiver.onReceive(context, sessionEndedIntent(1L))
			receiver.onReceive(context, sessionEndedIntent(2L))

			verify(exactly = 2) { workManager.enqueue(any<OneTimeWorkRequest>()) }
		}

		@Test
		fun `obtains WorkManager with provided context`() {
			receiver.onReceive(context, sessionEndedIntent(1L))

			verify { WorkManager.getInstance(context) }
		}

		@Test
		fun `does not enqueue work when session id is negative`() {
			mockkObject(com.adsamcik.tracker.shared.base.logging.ReporterFacade)
			every {
				com.adsamcik.tracker.shared.base.logging.ReporterFacade.report(any<Throwable>())
			} just runs

			val intent = sessionEndedIntent(-1L)

			receiver.onReceive(context, intent)

			verify(exactly = 0) { workManager.enqueue(any<OneTimeWorkRequest>()) }
		}

		@Test
		fun `ignores intents with unrelated action`() {
			val intent: Intent = mockk {
				every { action } returns "com.example.UNRELATED"
			}

			receiver.onReceive(context, intent)

			verify(exactly = 0) { workManager.enqueue(any<OneTimeWorkRequest>()) }
		}

		@Test
		fun `ignores intents with null action`() {
			val intent: Intent = mockk {
				every { action } returns null
			}

			receiver.onReceive(context, intent)

			verify(exactly = 0) { workManager.enqueue(any<OneTimeWorkRequest>()) }
		}

		@Test
		fun `ignores session started action`() {
			val intent: Intent = mockk {
				every { action } returns TrackerSession.ACTION_SESSION_STARTED
			}

			receiver.onReceive(context, intent)

			verify(exactly = 0) { workManager.enqueue(any<OneTimeWorkRequest>()) }
		}
	}
}
