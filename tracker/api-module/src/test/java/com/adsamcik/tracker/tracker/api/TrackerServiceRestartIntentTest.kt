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
				logicalTrackingId = "logical-session-42",
				serviceRunId = "service-run-ignored-on-restart",
				lifecycleRevision = 7L,
				lifecycleChangedAtEpochMs = 1_234L,
			),
		)

		intent.component?.className shouldBe TrackerServiceContract.SERVICE_CLASS_NAME
		intent.getBooleanExtra(TrackerServiceContract.ARG_IS_USER_INITIATED, false) shouldBe true
		intent.getBooleanExtra(TrackerServiceContract.ARG_IS_AMBIENT, true) shouldBe false
		intent.getStringExtra(TrackerServiceContract.ARG_POLICY_TIER) shouldBe PolicyTier.PRECISION.name
		intent.getStringExtra(TrackerServiceContract.ARG_LOGICAL_TRACKING_ID) shouldBe
			"logical-session-42"
		intent.getStringExtra(TrackerServiceContract.ARG_LIFECYCLE_STATE) shouldBe "ACTIVE"
		intent.getLongExtra(TrackerServiceContract.ARG_LIFECYCLE_REVISION, -1L) shouldBe 7L
		intent.getLongExtra(TrackerServiceContract.ARG_LIFECYCLE_CHANGED_AT_EPOCH_MS, -1L) shouldBe
			1_234L
	}
}
