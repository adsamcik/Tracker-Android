package com.adsamcik.tracker.tracker.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.component.consumer.data.ActivityTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.CellTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.LocationTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.WifiTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.controller.DefaultTrackerServiceController
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the source-separation contract of [TrackerComponentFactory]: the set of data and
 * pre-validation components is derived purely from the user's per-source toggles, independent of
 * the battery tier. This lets the user track any combination of {location, wifi, cell, activity}.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TrackerComponentFactorySeparationTest {

	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var testDispatcherProvider: DispatchersProvider
	private val testDispatcher = StandardTestDispatcher()

	@Before
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		testDispatcherProvider = TestDispatchersProvider(testDispatcher)
	}

	@After
	fun tearDown() {
		database.close()
		Dispatchers.resetMain()
	}

	private fun fakeParams(state: TrackingParamsState): TrackingParamsRepository = mockk {
		every { data } returns MutableStateFlow(state)
	}

	private suspend fun createComponentSet(
		state: TrackingParamsState,
		scope: CoroutineScope,
	): ComponentSet {
		val factory = TrackerComponentFactory(
			appDatabase = database,
			trackingParamsRepository = fakeParams(state),
			dispatchers = testDispatcherProvider,
			enableNotifications = false,
		)
		return factory.create(
			context = context,
			isSessionUserInitiated = true,
			notificationComponent = NotificationComponent(),
			escalationEngine = DefaultPolicyEscalationEngine(),
			controller = DefaultTrackerServiceController(),
			scope = scope,
		)
	}

	@Test
	fun `location toggle does not remove live-toggle consumers or add a cycle-wide gate`() = runTest(testDispatcher) {
		val set = createComponentSet(
			TrackingParamsState(
				locationEnabled = true,
				activityEnabled = false,
				stepsEnabled = false,
				wifiEnabled = false,
				cellEnabled = false,
				skiDetectionEnabled = false,
			),
			backgroundScope,
		)
		advanceUntilIdle()

		set.dataComponents.count { it is LocationTrackerComponent } shouldBe 1
		set.dataComponents.any { it is WifiTrackerComponent } shouldBe true
		set.dataComponents.any { it is CellTrackerComponent } shouldBe true
		set.dataComponents.any { it is ActivityTrackerComponent } shouldBe true
		set.dataComponents shouldHaveSize 4
	}

	@Test
	fun `wifi toggle does not remove live-toggle consumers or add a cycle-wide gate`() = runTest(testDispatcher) {
		val set = createComponentSet(
			TrackingParamsState(
				locationEnabled = false,
				activityEnabled = false,
				stepsEnabled = false,
				wifiEnabled = true,
				cellEnabled = false,
				skiDetectionEnabled = false,
			),
			backgroundScope,
		)
		advanceUntilIdle()

		set.dataComponents.count { it is WifiTrackerComponent } shouldBe 1
		set.dataComponents.any { it is LocationTrackerComponent } shouldBe true
		// Critical: with location disabled there must be NO location pre-tracker, otherwise every
		// non-location cycle would be rejected for lack of a GPS fix.
		set.dataComponents shouldHaveSize 4
	}

	@Test
	fun `cell toggle does not remove live-toggle consumers or add a cycle-wide gate`() = runTest(testDispatcher) {
		val set = createComponentSet(
			TrackingParamsState(
				locationEnabled = false,
				activityEnabled = false,
				stepsEnabled = false,
				wifiEnabled = false,
				cellEnabled = true,
				skiDetectionEnabled = false,
			),
			backgroundScope,
		)
		advanceUntilIdle()

		set.dataComponents.count { it is CellTrackerComponent } shouldBe 1
		set.dataComponents.any { it is LocationTrackerComponent } shouldBe true
		set.dataComponents shouldHaveSize 4
	}

	@Test
	fun `activity toggle does not remove live-toggle consumers or add a cycle-wide gate`() = runTest(testDispatcher) {
		val set = createComponentSet(
			TrackingParamsState(
				locationEnabled = false,
				activityEnabled = true,
				stepsEnabled = false,
				wifiEnabled = false,
				cellEnabled = false,
				skiDetectionEnabled = false,
			),
			backgroundScope,
		)
		advanceUntilIdle()

		set.dataComponents.count { it is ActivityTrackerComponent } shouldBe 1
		set.dataComponents.any { it is LocationTrackerComponent } shouldBe true
		set.dataComponents shouldHaveSize 4
	}

	@Test
	fun `all sources enabled keeps all consumers without a cycle-wide gate`() = runTest(testDispatcher) {
		val set = createComponentSet(
			TrackingParamsState(
				locationEnabled = true,
				activityEnabled = true,
				stepsEnabled = true,
				wifiEnabled = true,
				cellEnabled = true,
				skiDetectionEnabled = false,
			),
			backgroundScope,
		)
		advanceUntilIdle()

		set.dataComponents.any { it is LocationTrackerComponent } shouldBe true
		set.dataComponents.any { it is WifiTrackerComponent } shouldBe true
		set.dataComponents.any { it is CellTrackerComponent } shouldBe true
		set.dataComponents.any { it is ActivityTrackerComponent } shouldBe true
		set.dataComponents shouldHaveSize 4
	}
}
