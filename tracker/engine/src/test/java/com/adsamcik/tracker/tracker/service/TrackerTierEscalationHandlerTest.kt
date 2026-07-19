package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.DynamicIntervalCollectionTrigger
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.policy.TrackingPolicy
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerTierEscalationHandlerTest {

	@Test
	fun `GPS to ambient transition disables GPS trigger and enables ambient trigger`() = runTest {
		val events = mutableListOf<String>()
		val gpsTrigger = RecordingTrigger(
			isLocationTrigger = true,
			onDisable = { events += "gps-disabled" },
		)
		val ambientTrigger = RecordingTrigger(onEnable = { events += "ambient-enabled" })
		val accessor = RecordingTimerAccessor(gpsTrigger)
		val controller = mockk<TrackerServiceController>(relaxed = true)
		val handler = newHandler(
			controller = controller,
			params = trackingParams(locationEnabled = true, activityEnabled = true),
			ambientTriggerFactory = { ambientTrigger },
			foregroundServiceTypeUpdater = { requiresLocation, requiresHealth, stopServiceOnFailure ->
				events += "fgs:$requiresLocation:$requiresHealth:$stopServiceOnFailure"
				true
			},
		).apply {
			currentTier = PolicyTier.ACTIVE
			timerAccessor = accessor
		}

		handler.onPolicyChanged(
			policy = TrackingPolicy.PASSIVE_LOW,
			context = mockk(relaxed = true),
			timerReceiver = mockk(relaxed = true),
			scope = this,
		)
		advanceUntilIdle()

		gpsTrigger.disableCount shouldBe 1
		ambientTrigger.enableCount shouldBe 1
		accessor.timer shouldBe ambientTrigger
		events shouldBe listOf("ambient-enabled", "gps-disabled", "fgs:false:true:true")
		verify(exactly = 1) { controller.updatePolicyTier(PolicyTier.AMBIENT) }
	}

	@Test
	fun `ambient to GPS transition declares location before enabling GPS trigger`() = runTest {
		val events = mutableListOf<String>()
		val gpsTrigger = RecordingTrigger(
			isLocationTrigger = true,
			onEnable = { events += "gps-enabled" },
		)
		val handler = newHandler(
			controller = mockk(relaxed = true),
			params = trackingParams(locationEnabled = true, activityEnabled = true),
			gpsTriggerFactory = { gpsTrigger },
			foregroundServiceTypeUpdater = { requiresLocation, requiresHealth, stopServiceOnFailure ->
				events += "fgs:$requiresLocation:$requiresHealth:$stopServiceOnFailure"
				true
			},
		).apply {
			currentTier = PolicyTier.AMBIENT
			timerAccessor = RecordingTimerAccessor(RecordingTrigger())
		}

		handler.onPolicyChanged(
			policy = TrackingPolicy.ACTIVE_ELEVATED,
			context = mockk(relaxed = true),
			timerReceiver = mockk(relaxed = true),
			scope = this,
		)
		advanceUntilIdle()

		events shouldBe listOf("fgs:true:true:false", "gps-enabled")
		handler.currentTier shouldBe PolicyTier.PRECISION
	}

	@Test
	fun `failed location declaration prevents GPS transition`() = runTest {
		var gpsFactoryCalls = 0
		val controller = mockk<TrackerServiceController>(relaxed = true)
		val handler = newHandler(
			controller = controller,
			params = trackingParams(locationEnabled = true),
			gpsTriggerFactory = {
				gpsFactoryCalls++
				RecordingTrigger(isLocationTrigger = true)
			},
			foregroundServiceTypeUpdater = { _, _, _ -> false },
		).apply {
			currentTier = PolicyTier.AMBIENT
			timerAccessor = RecordingTimerAccessor(RecordingTrigger())
		}

		handler.onPolicyChanged(
			policy = TrackingPolicy.ACTIVE_ELEVATED,
			context = mockk(relaxed = true),
			timerReceiver = mockk(relaxed = true),
			scope = this,
		)
		advanceUntilIdle()

		gpsFactoryCalls shouldBe 0
		handler.currentTier shouldBe PolicyTier.AMBIENT
		verify(exactly = 0) { controller.updatePolicyTier(any()) }
	}

	@Test
	fun `battery cap prevents later escalation from switching to GPS`() = runTest {
		val ambientTrigger = RecordingTrigger()
		var gpsFactoryCalls = 0
		val accessor = RecordingTimerAccessor(ambientTrigger)
		val controller = mockk<TrackerServiceController>(relaxed = true)
		val handler = newHandler(
			controller = controller,
			tierAdjuster = { PolicyTier.AMBIENT },
			gpsTriggerFactory = {
				gpsFactoryCalls++
				RecordingTrigger()
			},
		).apply {
			currentTier = PolicyTier.AMBIENT
			timerAccessor = accessor
		}

		handler.onPolicyChanged(
			policy = TrackingPolicy.ACTIVE_ELEVATED,
			context = mockk(relaxed = true),
			timerReceiver = mockk(relaxed = true),
			scope = this,
		)
		advanceUntilIdle()

		gpsFactoryCalls shouldBe 0
		accessor.timer shouldBe ambientTrigger
		verify(exactly = 0) { controller.updatePolicyTier(PolicyTier.AMBIENT) }
		verify(exactly = 0) { controller.updatePolicyTier(PolicyTier.ACTIVE) }
		verify(exactly = 0) { controller.updatePolicyTier(PolicyTier.PRECISION) }
	}

	@Test
	fun `failed GPS transition does not publish an unapplied tier`() = runTest {
		val ambientTrigger = RecordingTrigger()
		val foregroundUpdates = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
		val controller = mockk<TrackerServiceController>(relaxed = true)
		val handler = newHandler(
			controller = controller,
			gpsTriggerFactory = { RecordingTrigger(hasPermissions = false) },
			foregroundServiceTypeUpdater = { requiresLocation, requiresHealth, stopServiceOnFailure ->
				foregroundUpdates += Triple(
					requiresLocation,
					requiresHealth,
					stopServiceOnFailure,
				)
				true
			},
		).apply {
			currentTier = PolicyTier.AMBIENT
			timerAccessor = RecordingTimerAccessor(ambientTrigger)
		}

		handler.onPolicyChanged(
			policy = TrackingPolicy.ACTIVE_ELEVATED,
			context = mockk(relaxed = true),
			timerReceiver = mockk(relaxed = true),
			scope = this,
		)
		advanceUntilIdle()

		handler.currentTier shouldBe PolicyTier.AMBIENT
		foregroundUpdates shouldBe listOf(
			Triple(true, false, false),
			Triple(false, false, true),
		)
		verify(exactly = 0) { controller.updatePolicyTier(PolicyTier.PRECISION) }
	}

	private fun newHandler(
		controller: TrackerServiceController,
		params: com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState =
			trackingParams(locationEnabled = true),
		tierAdjuster: (PolicyTier) -> PolicyTier = { it },
		gpsTriggerFactory: suspend (Context) -> CollectionTriggerComponent = { RecordingTrigger() },
		ambientTriggerFactory: () -> CollectionTriggerComponent = { RecordingTrigger() },
		foregroundServiceTypeUpdater: (Boolean, Boolean, Boolean) -> Boolean =
			{ _, _, _ -> true },
	): TrackerTierEscalationHandler {
		val trackingParams = mockk<com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository>(relaxed = true) {
			every { data } returns MutableStateFlow(params)
		}
		return TrackerTierEscalationHandler(
			componentMutex = Mutex(),
			controller = controller,
			trackingParamsRepository = trackingParams,
			tierAdjuster = tierAdjuster,
			gpsTriggerFactory = gpsTriggerFactory,
			ambientTriggerFactory = ambientTriggerFactory,
			foregroundServiceTypeUpdater = foregroundServiceTypeUpdater,
		)
	}

	private companion object {
		fun trackingParams(
			locationEnabled: Boolean,
			activityEnabled: Boolean = false,
			stepsEnabled: Boolean = false,
		) = com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState(
			locationEnabled = locationEnabled,
			activityEnabled = activityEnabled,
			stepsEnabled = stepsEnabled,
			wifiEnabled = false,
			cellEnabled = false,
			skiDetectionEnabled = false,
		)
	}

	private class RecordingTimerAccessor(
		initialTimer: CollectionTriggerComponent,
	) : TrackerTierEscalationHandler.TimerAccessor {
		var timer: CollectionTriggerComponent = initialTimer
			private set

		override fun get(): CollectionTriggerComponent = timer

		override fun set(timer: CollectionTriggerComponent) {
			this.timer = timer
		}
	}

	private class RecordingTrigger(
		private val hasPermissions: Boolean = true,
		override val isLocationTrigger: Boolean = false,
		private val onEnable: () -> Unit = {},
		private val onDisable: () -> Unit = {},
	) : DynamicIntervalCollectionTrigger {
		override val titleRes: Int = 0
		override val requiredPermissions: Collection<String> = emptyList()
		var enableCount = 0
			private set
		var disableCount = 0
			private set
		var intervalUpdates = 0
			private set

		override fun hasRequiredPermissions(context: Context): Boolean = hasPermissions

		override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
			enableCount++
			onEnable()
		}

		override fun onDisable(context: Context) {
			disableCount++
			onDisable()
		}

		override fun updateInterval(context: Context, intervalSeconds: Int, minDistanceMeters: Int) {
			intervalUpdates++
		}
	}
}
