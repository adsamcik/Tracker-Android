package com.adsamcik.tracker.activity.api.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.assist.Assist
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GmsActivityRecognitionBackendTest {

	private val context: Context
		get() = ApplicationProvider.getApplicationContext()

	private lateinit var backend: GmsActivityRecognitionBackend

	@Before
	fun setUp() {
		backend = GmsActivityRecognitionBackend(context)
		mockkObject(Assist)
		mockkObject(Logger)
		mockkObject(Reporter)
		every { Logger.logWithStringPreference(any(), any(), any()) } just runs
		every { Reporter.report(any<Throwable>()) } just runs
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	// region isAvailable
	@Test
	fun `isAvailable returns false when Play Services unavailable`() {
		every { Assist.isPlayServicesAvailable(any<Context>()) } returns false

		backend.isAvailable shouldBe false
	}

	@Test
	fun `isAvailable returns true when Play Services available`() {
		every { Assist.isPlayServicesAvailable(any<Context>()) } returns true

		backend.isAvailable shouldBe true
	}
	// endregion

	// region name
	@Test
	fun `name returns Google Play Services`() {
		backend.name shouldBe "Google Play Services"
	}
	// endregion

	// region startUpdates
	@Test
	fun `startUpdates returns false when Play Services unavailable`() {
		every { Assist.isPlayServicesAvailable(any<Context>()) } returns false

		val result = backend.startUpdates(RecognitionConfig(intervalSeconds = 10))

		result shouldBe false
		verify {
			Reporter.report(match<Throwable> {
				it.message?.contains("Google Play Services unavailable") == true
			})
		}
	}
	// endregion

	// region onActivityResult
	@Test
	fun `onActivityResult updates lastActivity`() {
		val activity = com.adsamcik.tracker.shared.base.data.ActivityInfo(
			com.adsamcik.tracker.shared.base.data.DetectedActivity.WALKING, 85,
		)

		backend.onActivityResult(activity, 5000L)

		backend.lastActivity shouldBe activity
		backend.lastActivityElapsedTimeMillis shouldBe 5000L
	}
	// endregion
}
