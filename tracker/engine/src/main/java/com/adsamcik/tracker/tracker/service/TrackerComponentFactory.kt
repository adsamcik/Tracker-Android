package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.result.runCatchingCancellable
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.ActivityTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.CellTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.LocationTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.WifiTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.PlaneTrackingComponent
import com.adsamcik.tracker.tracker.component.consumer.post.SailingTrackingComponent
import com.adsamcik.tracker.tracker.component.consumer.post.SkiSegmentWriter
import com.adsamcik.tracker.tracker.component.consumer.post.SkiTrackingComponent
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.controller.toLiveState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Result of component construction and optional ski/sailing/plane state wiring.
 */
internal data class ComponentSet(
	val dataComponents: List<DataTrackerComponent>,
	val skiTrackingComponent: SkiTrackingComponent?,
	val skiSegmentWriter: SkiSegmentWriter?,
	val sailingTrackingComponent: SailingTrackingComponent?,
	val planeTrackingComponent: PlaneTrackingComponent?,
	val sessionComponent: SessionTrackerComponent,
)

/**
 * Constructs and enables the tracker component lists for a given tier.
 *
 * Extracted from [TrackerService.initializeComponents] to reduce the service's
 * responsibility surface.
 */
internal class TrackerComponentFactory(
	private val appDatabase: AppDatabase,
	private val trackingParamsRepository: TrackingParamsRepository,
	private val dispatchers: DispatchersProvider,
	private val enableNotifications: Boolean = true,
) {

	/**
	 * Build and enable all tracker components for a new session.
	 *
	 * Which data/pre components are created is derived from the user's per-source toggles
	 * (read fresh inside this call), not from [tier]. [tier] is retained only so the result can be
	 * wrapped in the tier-configuration model; it no longer selects sources.
	 *
	 * @param context            Android context for component lifecycle calls.
	 * @param isSessionUserInitiated  whether the session was started by the user.
	 * @param tier               starting [PolicyTier] (governs trigger cadence, not source set).
	 * @param notificationComponent shared notification component instance.
	 * @param trackingPolicyManager policy manager for adaptive location filtering (nullable).
	 * @param escalationEngine   engine for ski tracking component wiring.
	 * @param controller         service controller for forwarding ski state.
	 * @param scope              coroutine scope for ski state collection.
	 */
	suspend fun create(
		context: Context,
		isSessionUserInitiated: Boolean,
		notificationComponent: NotificationComponent,
		escalationEngine: DefaultPolicyEscalationEngine,
		controller: TrackerServiceController,
		scope: CoroutineScope,
	): ComponentSet {
		var sessionComponent: SessionTrackerComponent? = null
		var dataComponents: List<DataTrackerComponent> = emptyList()
		var notificationEnabled = false
		var skiTracking: SkiTrackingComponent? = null
		var skiWriter: SkiSegmentWriter? = null
		var sailingTracking: SailingTrackingComponent? = null
		var planeTracking: PlaneTrackingComponent? = null
		val stateCollectorJobs = mutableListOf<Job>()

		try {
			sessionComponent = SessionTrackerComponent(
				isSessionUserInitiated,
				appDatabase.sessionSegmentDao(),
				trackingParamsRepository,
			).apply {
				onEnable(context)
			}

			// Location quality is validated by LocationTrackerComponent only. A bad/missing GPS fix
			// must never reject Wi-Fi, cell, activity, step, or pressure data acquired in the same
			// cycle.
			dataComponents = buildDataComponents(context)

			if (enableNotifications) {
				notificationComponent.onEnable(context)
				notificationEnabled = true
			}

			buildSkiComponents(
				context = context,
				escalationEngine = escalationEngine,
				controller = controller,
				scope = scope,
				stateCollectorJobs = stateCollectorJobs,
			).also {
				skiTracking = it.first
				skiWriter = it.second
			}
			sailingTracking = buildSailingComponent(
				context,
				escalationEngine,
				controller,
				scope,
				stateCollectorJobs,
			)
			planeTracking = buildPlaneComponent(
				context,
				escalationEngine,
				controller,
				scope,
				stateCollectorJobs,
			)

			return ComponentSet(
				dataComponents = dataComponents,
				skiTrackingComponent = skiTracking,
				skiSegmentWriter = skiWriter,
				sailingTrackingComponent = sailingTracking,
				planeTrackingComponent = planeTracking,
				sessionComponent = requireNotNull(sessionComponent),
			)
		} catch (failure: Exception) {
			withContext(NonCancellable) {
				stateCollectorJobs.forEach(Job::cancel)
				rollback { planeTracking?.onDisable(context) }
				rollback { sailingTracking?.onDisable(context) }
				rollback { skiTracking?.onDisable(context) }
				rollback { skiWriter?.onDisable(context) }
				if (notificationEnabled) {
					rollback { notificationComponent.onDisable(context) }
				}
				dataComponents.asReversed().forEach { component ->
					rollback { component.onDisable(context) }
				}
				rollback { sessionComponent?.onDisable(context) }
			}
			throw failure
		}
	}

	private suspend fun rollback(block: suspend () -> Unit) {
		runCatchingCancellable { block() }.getOrNull()
	}

	/**
	 * Build data-collection components, one per enabled source toggle.
	 *
	 * Each component is added iff its source toggle is on, fully decoupling source selection from
	 * the battery tier. Components also self-skip when their data is absent (see
	 * `TrackerComponentRequirement`), so a built-but-starved component is harmless.
	 */
	private suspend fun buildDataComponents(
		context: Context,
	): List<DataTrackerComponent> {
		val components = listOf(
			ActivityTrackerComponent(),
			LocationTrackerComponent(trackingParamsRepository, dispatchers),
			CellTrackerComponent(),
			WifiTrackerComponent(),
		)
		val enabled = mutableListOf<DataTrackerComponent>()
		try {
			for (component in components) {
				component.onEnable(context)
				enabled += component
			}
		} catch (failure: Exception) {
			withContext(NonCancellable) {
				enabled.asReversed().forEach { component ->
					rollback { component.onDisable(context) }
				}
			}
			throw failure
		}
		return components
	}

	private suspend fun buildSkiComponents(
		context: Context,
		escalationEngine: DefaultPolicyEscalationEngine,
		controller: TrackerServiceController,
		scope: CoroutineScope,
		stateCollectorJobs: MutableList<Job>,
	): Pair<SkiTrackingComponent?, SkiSegmentWriter?> {
		val skiEnabled = trackingParamsRepository.data.first().skiDetectionEnabled
		if (!skiEnabled) return null to null

		val segmentWriter = SkiSegmentWriter()
		val skiComponent = SkiTrackingComponent().also {
			it.setEscalationEngine(escalationEngine)
			it.setSecondaryListener(segmentWriter)
			stateCollectorJobs += scope.launch {
				it.skiState.collect { skiState ->
					controller.updateSkiState(skiState?.toLiveState())
				}
			}
		}
		try {
			segmentWriter.onEnable(context)
			skiComponent.onEnable(context)
			return skiComponent to segmentWriter
		} catch (failure: Exception) {
			withContext(NonCancellable) {
				rollback { skiComponent.onDisable(context) }
				rollback { segmentWriter.onDisable(context) }
			}
			throw failure
		}
	}

	private suspend fun buildSailingComponent(
		context: Context,
		escalationEngine: DefaultPolicyEscalationEngine,
		controller: TrackerServiceController,
		scope: CoroutineScope,
		stateCollectorJobs: MutableList<Job>,
	): SailingTrackingComponent? {
		val sailingEnabled = trackingParamsRepository.data.first().sailingDetectionEnabled
		if (!sailingEnabled) return null

		val sailingComponent = SailingTrackingComponent().also {
			it.setEscalationEngine(escalationEngine)
			stateCollectorJobs += scope.launch {
				it.sailingState.collect { sailingState ->
					controller.updateSailingState(sailingState?.toLiveState())
				}
			}
		}
		try {
			sailingComponent.onEnable(context)
			return sailingComponent
		} catch (failure: Exception) {
			withContext(NonCancellable) {
				rollback { sailingComponent.onDisable(context) }
			}
			throw failure
		}
	}

	private suspend fun buildPlaneComponent(
		context: Context,
		escalationEngine: DefaultPolicyEscalationEngine,
		controller: TrackerServiceController,
		scope: CoroutineScope,
		stateCollectorJobs: MutableList<Job>,
	): PlaneTrackingComponent? {
		val planeEnabled = trackingParamsRepository.data.first().planeDetectionEnabled
		if (!planeEnabled) return null

		val planeComponent = PlaneTrackingComponent().also {
			it.setEscalationEngine(escalationEngine)
			stateCollectorJobs += scope.launch {
				it.planeState.collect { planeState ->
					controller.updatePlaneState(planeState?.toLiveState())
				}
			}
		}
		try {
			planeComponent.onEnable(context)
			return planeComponent
		} catch (failure: Exception) {
			withContext(NonCancellable) {
				rollback { planeComponent.onDisable(context) }
			}
			throw failure
		}
	}
}
