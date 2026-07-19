package com.adsamcik.tracker.tracker.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerServiceRestartIntentTest {
	@Test
	fun `restart intent preserves durable session flags and tier`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val intent = TrackerServiceApi.createRestartIntent(
			context,
			ActiveTrackingSessionDescriptor(
				isUserInitiated = true,
				isAmbient = false,
				policyTier = PolicyTier.PRECISION,
			),
		)

		intent.component?.className shouldBe TrackerServiceContract.SERVICE_CLASS_NAME
		intent.getBooleanExtra(TrackerServiceContract.ARG_IS_USER_INITIATED, false) shouldBe true
		intent.getBooleanExtra(TrackerServiceContract.ARG_IS_AMBIENT, true) shouldBe false
		intent.getStringExtra(TrackerServiceContract.ARG_POLICY_TIER) shouldBe PolicyTier.PRECISION.name
	}
}
