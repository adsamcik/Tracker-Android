package com.adsamcik.tracker.tracker.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.component.consumer.data.ActivityTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.CellTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.LocationTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.WifiTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.consumer.pre.LocationPreTrackerComponent
import com.adsamcik.tracker.tracker.controller.DefaultTrackerServiceController
import io.kotest.matchers.collections.shouldBeEmpty
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
			trackerSettingsRepository = mockk<TrackerSettingsRepository>(relaxed = true),
			dispatchers = testDispatcherProvider,
			enableNotifications = false,
		)
		return factory.create(
			context = context,
			isSessionUserInitiated = true,
			tier = PolicyTier.PRECISION,
			notificationComponent = NotificationComponent(),
			trackingPolicyManager = null,
			escalationEngine = DefaultPolicyEscalationEngine(),
			controller = DefaultTrackerServiceController(),
			scope = scope,
		)
	}

	@Test
	fun `location only builds location component and a location pre-tracker`() = runTest(testDispatcher) {
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
		set.dataComponents.any { it is WifiTrackerComponent } shouldBe false
		set.dataComponents.any { it is CellTrackerComponent } shouldBe false
		set.dataComponents.any { it is ActivityTrackerComponent } shouldBe false
		set.preComponents shouldHaveSize 1
		set.preComponents.all { it is LocationPreTrackerComponent } shouldBe true
	}

	@Test
	fun `wifi only builds wifi component and NO location pre-tracker`() = runTest(testDispatcher) {
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
		set.dataComponents.any { it is LocationTrackerComponent } shouldBe false
		// Critical: with location disabled there must be NO location pre-tracker, otherwise every
		// non-location cycle would be rejected for lack of a GPS fix.
		set.preComponents.shouldBeEmpty()
	}

	@Test
	fun `cell only builds cell component and NO location pre-tracker`() = runTest(testDispatcher) {
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
		set.dataComponents.any { it is LocationTrackerComponent } shouldBe false
		set.preComponents.shouldBeEmpty()
	}

	@Test
	fun `activity only builds activity component and NO location pre-tracker`() = runTest(testDispatcher) {
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
		set.dataComponents.any { it is LocationTrackerComponent } shouldBe false
		set.preComponents.shouldBeEmpty()
	}

	@Test
	fun `all sources enabled builds all four data components`() = runTest(testDispatcher) {
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
		set.preComponents shouldHaveSize 1
	}
}
