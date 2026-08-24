package com.adsamcik.tracker.tracker.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerServiceRestartIntentTest {
	@Test
	fun `prepared delivery intent carries only exact identity and foreground deadline hints`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val token = PreparedTrackingStartToken("prepared-token-7")
		val prepared = TrackingStartPreparationResult.Prepared(
			token = token,
			startupGeneration = 3L,
			preparedSourceMaskHint = 5L,
			preparedStartIsUserInitiatedHint = true,
		)
		val intent = TrackerServiceApi.createPreparedStartIntent(
			context,
			prepared,
			commandGeneration = 42L,
		)

		intent.component?.className shouldBe TrackerServiceContract.SERVICE_CLASS_NAME
		intent.extras?.keySet() shouldBe setOf(
			TrackerServiceContract.ARG_PREPARED_START_TOKEN,
			TrackerServiceContract.ARG_LIFECYCLE_COMMAND_GENERATION,
			TrackerServiceContract.ARG_PREPARED_SOURCE_MASK_HINT,
			TrackerServiceContract.ARG_PREPARED_USER_INITIATED_HINT,
		)
		intent.getStringExtra(TrackerServiceContract.ARG_PREPARED_START_TOKEN) shouldBe token.value
		intent.getLongExtra(TrackerServiceContract.ARG_LIFECYCLE_COMMAND_GENERATION, -1L) shouldBe 42L
		intent.getLongExtra(TrackerServiceContract.ARG_PREPARED_SOURCE_MASK_HINT, -1L) shouldBe 5L
		intent.getBooleanExtra(
			TrackerServiceContract.ARG_PREPARED_USER_INITIATED_HINT,
			false,
		) shouldBe true
	}

	@Test(expected = IllegalArgumentException::class)
	fun `empty prepared source hint is rejected before Android enqueue`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		TrackerServiceApi.createPreparedStartIntent(
			context,
			TrackingStartPreparationResult.Prepared(
				PreparedTrackingStartToken("empty-source-hint"),
			),
			commandGeneration = 1L,
		)
	}

	@Test
	fun `ambient and unspecified non-user service starts fail closed before dependency lookup`() {
		val context = ApplicationProvider.getApplicationContext<Context>()

		@Suppress("DEPRECATION")
		val ambient = TrackerServiceApi.startAmbientService(context)
		ambient shouldBe false
		TrackerServiceApi.startService(
			context = context,
			isUserInitiated = false,
			automaticTrigger = null,
		) shouldBe false
	}
}
