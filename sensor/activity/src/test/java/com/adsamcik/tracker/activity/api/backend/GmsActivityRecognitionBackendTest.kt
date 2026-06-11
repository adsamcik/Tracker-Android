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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@DisplayName("GmsActivityRecognitionBackend")
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class GmsActivityRecognitionBackendTest {

	private val context: Context
		get() = ApplicationProvider.getApplicationContext()

	private lateinit var backend: GmsActivityRecognitionBackend

	@BeforeEach
	fun setUp() {
		backend = GmsActivityRecognitionBackend(context)
		mockkObject(Assist)
		mockkObject(Logger)
		mockkObject(Reporter)
		every { Logger.logWithStringPreference(any(), any(), any()) } just runs
		every { Reporter.report(any<Throwable>()) } just runs
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	@Nested
	@DisplayName("isAvailable")
	inner class IsAvailable {

		@Test
		fun `returns false when Play Services unavailable`() {
			every { Assist.isPlayServicesAvailable(any<Context>()) } returns false

			backend.isAvailable shouldBe false
		}

		@Test
		fun `returns true when Play Services available`() {
			every { Assist.isPlayServicesAvailable(any<Context>()) } returns true

			backend.isAvailable shouldBe true
		}
	}

	@Nested
	@DisplayName("name")
	inner class Name {

		@Test
		fun `returns Google Play Services`() {
			backend.name shouldBe "Google Play Services"
		}
	}

	@Nested
	@DisplayName("startUpdates")
	inner class StartUpdates {

		@Test
		fun `returns false when Play Services unavailable`() {
			every { Assist.isPlayServicesAvailable(any<Context>()) } returns false

			val result = backend.startUpdates(RecognitionConfig(intervalSeconds = 10))

			result shouldBe false
			verify {
				Reporter.report(match<Throwable> {
					it.message?.contains("Google Play Services unavailable") == true
				})
			}
		}
	}

	@Nested
	@DisplayName("onActivityResult")
	inner class OnActivityResult {

		@Test
		fun `updates lastActivity`() {
			val activity = com.adsamcik.tracker.shared.base.data.ActivityInfo(
				com.adsamcik.tracker.shared.base.data.DetectedActivity.WALKING, 85,
			)

			backend.onActivityResult(activity, 5000L)

			backend.lastActivity shouldBe activity
			backend.lastActivityElapsedTimeMillis shouldBe 5000L
		}
	}
}
