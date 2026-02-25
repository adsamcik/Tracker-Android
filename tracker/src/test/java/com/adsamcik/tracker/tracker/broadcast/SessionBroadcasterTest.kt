package com.adsamcik.tracker.tracker.broadcast

import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class SessionBroadcasterTest {

	private lateinit var context: Context
	private lateinit var workManager: WorkManager

	@BeforeEach
	fun setup() {
		context = mockk(relaxed = true)
		every { context.packageName } returns "com.adsamcik.tracker.test"
		every { context.applicationContext } returns context

		workManager = mockk(relaxed = true)

		mockkObject(WorkManager.Companion)
		every { WorkManager.getInstance(any()) } returns workManager

		mockkObject(com.adsamcik.tracker.logger.Logger)
		every { com.adsamcik.tracker.logger.Logger.log(any()) } just Runs
	}

	@AfterEach
	fun teardown() {
		unmockkObject(WorkManager.Companion)
		unmockkObject(com.adsamcik.tracker.logger.Logger)
	}

	private fun createSession(
		id: Long = 1L,
		isUserInitiated: Boolean = true,
		end: Long = System.currentTimeMillis()
	): TrackerSession {
		val session = mockk<TrackerSession>(relaxed = true)
		every { session.id } returns id
		every { session.isUserInitiated } returns isUserInitiated
		every { session.end } returns end
		return session
	}

	@Nested
	inner class `broadcastSessionStart` {

		@Test
		fun `sends broadcast with correct action`() {
			val session = createSession(id = 10L)
			val intentSlot = slot<Intent>()

			every { context.sendBroadcast(capture(intentSlot), any<String>()) } just Runs

			SessionBroadcaster.broadcastSessionStart(context, session, isNew = true)

			intentSlot.captured.action shouldBe TrackerSession.ACTION_SESSION_STARTED
		}

		@Test
		fun `includes session id in intent`() {
			val session = createSession(id = 42L)
			val intentSlot = slot<Intent>()

			every { context.sendBroadcast(capture(intentSlot), any<String>()) } just Runs

			SessionBroadcaster.broadcastSessionStart(context, session, isNew = true)

			intentSlot.captured.getLongExtra(TrackerSession.RECEIVER_SESSION_ID, -1) shouldBe 42L
		}

		@Test
		fun `includes isNew flag in intent`() {
			val session = createSession(id = 1L)
			val intentSlot = slot<Intent>()

			every { context.sendBroadcast(capture(intentSlot), any<String>()) } just Runs

			SessionBroadcaster.broadcastSessionStart(context, session, isNew = false)

			intentSlot.captured.getBooleanExtra(TrackerSession.RECEIVER_SESSION_IS_NEW, true) shouldBe false
		}

		@Test
		fun `cancels pending session finalization`() {
			val session = createSession(id = 7L)

			SessionBroadcaster.broadcastSessionStart(context, session, isNew = true)

			verify { workManager.cancelUniqueWork("7finalSession") }
		}

		@Test
		fun `sends broadcast with permission`() {
			val session = createSession(id = 1L)

			SessionBroadcaster.broadcastSessionStart(context, session, isNew = true)

			verify {
				context.sendBroadcast(any(), eq("com.adsamcik.tracker.test.permission.TRACKER"))
			}
		}
	}

	@Nested
	inner class `broadcastSessionEnd` {

		@Test
		fun `sends broadcast with correct action`() {
			val session = createSession(id = 1L, isUserInitiated = true)
			val intents = mutableListOf<Intent>()

			every { context.sendBroadcast(capture(intents), any<String>()) } just Runs

			SessionBroadcaster.broadcastSessionEnd(context, session)

			intents[0].action shouldBe TrackerSession.ACTION_SESSION_ENDED
		}

		@Test
		fun `user-initiated session triggers immediate finalization`() {
			val session = createSession(id = 5L, isUserInitiated = true)
			val intents = mutableListOf<Intent>()

			every { context.sendBroadcast(capture(intents), any<String>()) } just Runs

			SessionBroadcaster.broadcastSessionEnd(context, session)

			// Should have 2 broadcasts: SESSION_ENDED + SESSION_FINAL
			val actions = intents.map { it.action }
			actions.size shouldBe 2
			actions[0] shouldBe TrackerSession.ACTION_SESSION_ENDED
			actions[1] shouldBe TrackerSession.ACTION_SESSION_FINAL
		}

		@Test
		fun `auto session schedules deferred finalization`() {
			val session = createSession(id = 3L, isUserInitiated = false)

			SessionBroadcaster.broadcastSessionEnd(context, session)

			verify {
				workManager.enqueueUniqueWork(
					eq("3finalSession"),
					eq(ExistingWorkPolicy.REPLACE),
					any<OneTimeWorkRequest>()
				)
			}
		}

		@Test
		fun `auto session does not send immediate final broadcast`() {
			val session = createSession(id = 3L, isUserInitiated = false)
			val intents = mutableListOf<Intent>()

			every { context.sendBroadcast(capture(intents), any<String>()) } just Runs

			SessionBroadcaster.broadcastSessionEnd(context, session)

			// Only SESSION_ENDED, no SESSION_FINAL
			val actions = intents.map { it.action }
			actions.size shouldBe 1
			actions[0] shouldBe TrackerSession.ACTION_SESSION_ENDED
		}
	}

	@Nested
	inner class `broadcastSessionFinal` {

		@Test
		fun `sends broadcast with correct action`() {
			val session = createSession(id = 1L)
			val intentSlot = slot<Intent>()

			every { context.sendBroadcast(capture(intentSlot), any<String>()) } just Runs

			SessionBroadcaster.broadcastSessionFinal(context, session)

			intentSlot.captured.action shouldBe TrackerSession.ACTION_SESSION_FINAL
		}

		@Test
		fun `includes session id in final broadcast`() {
			val session = createSession(id = 99L)
			val intentSlot = slot<Intent>()

			every { context.sendBroadcast(capture(intentSlot), any<String>()) } just Runs

			SessionBroadcaster.broadcastSessionFinal(context, session)

			intentSlot.captured.getLongExtra(TrackerSession.RECEIVER_SESSION_ID, -1) shouldBe 99L
		}
	}

	@Nested
	inner class `session lifecycle flow` {

		@Test
		fun `start then end for user session produces three broadcasts`() {
			val session = createSession(id = 1L, isUserInitiated = true)
			val intents = mutableListOf<Intent>()

			every { context.sendBroadcast(capture(intents), any<String>()) } just Runs

			SessionBroadcaster.broadcastSessionStart(context, session, isNew = true)
			SessionBroadcaster.broadcastSessionEnd(context, session)

			val actions = intents.map { it.action }
			actions.size shouldBe 3
			actions[0] shouldBe TrackerSession.ACTION_SESSION_STARTED
			actions[1] shouldBe TrackerSession.ACTION_SESSION_ENDED
			actions[2] shouldBe TrackerSession.ACTION_SESSION_FINAL
		}

		@Test
		fun `resumed session uses isNew false`() {
			val session = createSession(id = 1L)
			val intentSlot = slot<Intent>()

			every { context.sendBroadcast(capture(intentSlot), any<String>()) } just Runs

			SessionBroadcaster.broadcastSessionStart(context, session, isNew = false)

			intentSlot.captured.getBooleanExtra(
				TrackerSession.RECEIVER_SESSION_IS_NEW, true
			) shouldBe false
		}

		@Test
		fun `multiple sessions each get unique work ids`() {
			val session1 = createSession(id = 1L, isUserInitiated = false)
			val session2 = createSession(id = 2L, isUserInitiated = false)

			SessionBroadcaster.broadcastSessionEnd(context, session1)
			SessionBroadcaster.broadcastSessionEnd(context, session2)

			verify { workManager.enqueueUniqueWork(eq("1finalSession"), any(), any<OneTimeWorkRequest>()) }
			verify { workManager.enqueueUniqueWork(eq("2finalSession"), any(), any<OneTimeWorkRequest>()) }
		}

		@Test
		fun `starting session cancels previous finalization for same session`() {
			val session = createSession(id = 5L, isUserInitiated = false)

			// End triggers scheduled finalization
			SessionBroadcaster.broadcastSessionEnd(context, session)

			// Start (resume) cancels it
			SessionBroadcaster.broadcastSessionStart(context, session, isNew = false)

			verify { workManager.cancelUniqueWork("5finalSession") }
		}
	}
}
