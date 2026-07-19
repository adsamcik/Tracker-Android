package com.adsamcik.tracker.tracker.resilience

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultActiveTrackingSessionStoreTest {
	@Test
	fun `descriptor survives repository recreation and is removed on graceful clear`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.filesDir.resolve("datastore/active_tracking_session.pb").delete()
		val dispatcher = StandardTestDispatcher(testScheduler)
		val store = DefaultActiveTrackingSessionStore(
			context,
			TestDispatchersProvider(dispatcher),
		)
		val descriptor = ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
		)

		store.save(descriptor) shouldBe ActiveTrackingSessionStoreResult.Success(descriptor)
		store.read() shouldBe ActiveTrackingSessionStoreResult.Success(descriptor)
		store.clear() shouldBe ActiveTrackingSessionStoreResult.Success(null)
		store.read() shouldBe ActiveTrackingSessionStoreResult.Success(null)
	}
}

