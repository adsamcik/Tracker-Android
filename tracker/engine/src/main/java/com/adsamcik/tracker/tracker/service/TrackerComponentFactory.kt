package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
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
import com.adsamcik.tracker.tracker.component.consumer.pre.LocationPreTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.pre.PolicyAwareLocationPreTrackerComponent
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.controller.toLiveState
import com.adsamcik.tracker.tracker.data.DefaultPersistenceErrorCollector
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import com.adsamcik.tracker.logger.Reporter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Result of component construction, containing the 3 component lists,
 * session component, persistence error collector, and ski/sailing/plane state wiring.
 */
internal data class ComponentSet(
	val preComponents: List<PreTrackerComponent>,
	val dataComponents: List<DataTrackerComponent>,
	val skiTrackingComponent: SkiTrackingComponent?,
	val skiSegmentWriter: SkiSegmentWriter?,
	val sailingTrackingComponent: SailingTrackingComponent?,
	val planeTrackingComponent: PlaneTrackingComponent?,
	val sessionComponent: SessionTrackerComponent,
	val errorCollector: DefaultPersistenceErrorCollector,
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
	private val trackerSettingsRepository: TrackerSettingsRepository,
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
	@Suppress("UNUSED_PARAMETER")
	suspend fun create(
		context: Context,
		isSessionUserInitiated: Boolean,
		tier: PolicyTier,
		notificationComponent: NotificationComponent,
		trackingPolicyManager: TrackingPolicyManager?,
		escalationEngine: DefaultPolicyEscalationEngine,
		controller: TrackerServiceController,
		scope: CoroutineScope,
	): ComponentSet {
		var sessionComponent: SessionTrackerComponent? = null
		var preComponents: List<PreTrackerComponent> = emptyList()
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

			// Single snapshot of the user's per-source toggles. Component/pre-component existence is
			// derived purely from these toggles, so any combination of sources can be tracked
			// independently of the battery tier.
			val params = trackingParamsRepository.data.first()

			// Location quality is validated by LocationTrackerComponent only. A bad/missing GPS fix
			// must never reject Wi-Fi, cell, activity, step, or pressure data acquired in the same
			// cycle.
			preComponents = emptyList()
			dataComponents = buildDataComponents(context, params)

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
				preComponents = preComponents,
				dataComponents = dataComponents,
				skiTrackingComponent = skiTracking,
				skiSegmentWriter = skiWriter,
				sailingTrackingComponent = sailingTracking,
				planeTrackingComponent = planeTracking,
				sessionComponent = requireNotNull(sessionComponent),
				errorCollector = DefaultPersistenceErrorCollector(),
			)
		} catch (failure: Exception) {
			withContext(NonCancellable) {
				stateCollectorJobs.forEach(Job::cancel)
				rollback("plane component") { planeTracking?.onDisable(context) }
				rollback("sailing component") { sailingTracking?.onDisable(context) }
				rollback("ski component") { skiTracking?.onDisable(context) }
				rollback("ski writer") { skiWriter?.onDisable(context) }
				if (notificationEnabled) {
					rollback("notification component") { notificationComponent.onDisable(context) }
				}
				dataComponents.asReversed().forEach { component ->
					rollback("data component") { component.onDisable(context) }
				}
				preComponents.asReversed().forEach { component ->
					rollback("pre component") { component.onDisable(context) }
				}
				rollback("session component") { sessionComponent?.onDisable(context) }
			}
			throw failure
		}
	}

	private suspend fun rollback(label: String, block: suspend () -> Unit) {
		try {
			block()
		} catch (cleanupFailure: Exception) {
			Reporter.report(IllegalStateException("Failed to roll back $label", cleanupFailure))
		}
	}

	/**
	 * Build pre-validation components for the session.
	 *
	 * The location pre-tracker gates the whole cycle on a usable GPS fix when the policy demands
	 * location. It must therefore only be installed when the user actually enabled location —
	 * otherwise a Wi-Fi/cell/activity-only session would have every cycle rejected for lack of a
	 * fix. When location is disabled this returns an empty list so non-location cycles always pass.
	 */
	private suspend fun buildPreComponents(
		context: Context,
		trackingPolicyManager: TrackingPolicyManager?,
		controller: TrackerServiceController,
		params: TrackingParamsState,
	): List<PreTrackerComponent> {
		if (!params.locationEnabled) return emptyList()

		val components = mutableListOf<PreTrackerComponent>().apply {
			trackingPolicyManager?.let { policyMgr ->
				add(PolicyAwareLocationPreTrackerComponent(
					policyFlow = policyMgr.currentPolicy,
					effectiveTierFlow = controller.policyTierFlow,
					trackingParamsRepository = trackingParamsRepository,
				))
			} ?: run {
				add(LocationPreTrackerComponent(trackingParamsRepository = trackingParamsRepository))
			}
		}
		val enabled = mutableListOf<PreTrackerComponent>()
		try {
			for (component in components) {
				component.onEnable(context)
				enabled += component
			}
		} catch (failure: Exception) {
			withContext(NonCancellable) {
				enabled.asReversed().forEach { component ->
					rollback("pre component") { component.onDisable(context) }
				}
			}
			throw failure
		}
		return components
	}

	/**
	 * Build data-collection components, one per enabled source toggle.
	 *
	 * Each component is added iff its source toggle is on, fully decoupling source selection from
	 * the battery tier. Components also self-skip when their data is absent (see
	 * `TrackerComponentRequirement`), so a built-but-starved component is harmless.
	 */
	@Suppress("UNUSED_PARAMETER")
	private suspend fun buildDataComponents(
		context: Context,
		params: TrackingParamsState,
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
					rollback("data component") { component.onDisable(context) }
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
				rollback("ski component") { skiComponent.onDisable(context) }
				rollback("ski writer") { segmentWriter.onDisable(context) }
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
				rollback("sailing component") { sailingComponent.onDisable(context) }
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
				rollback("plane component") { planeComponent.onDisable(context) }
			}
			throw failure
		}
	}
}
