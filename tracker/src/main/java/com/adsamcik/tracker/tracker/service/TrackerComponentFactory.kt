package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
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
import com.adsamcik.tracker.tracker.component.consumer.post.SkiSegmentWriter
import com.adsamcik.tracker.tracker.component.consumer.post.SkiTrackingComponent
import com.adsamcik.tracker.tracker.component.consumer.pre.LocationPreTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.pre.PolicyAwareLocationPreTrackerComponent
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.data.DefaultPersistenceErrorCollector
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Result of component construction, containing the 3 component lists,
 * session component, persistence error collector, and ski state wiring.
 */
internal data class ComponentSet(
	val preComponents: List<PreTrackerComponent>,
	val dataComponents: List<DataTrackerComponent>,
	val skiTrackingComponent: SkiTrackingComponent?,
	val skiSegmentWriter: SkiSegmentWriter?,
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
) {

	/**
	 * Build and enable all tracker components for the given [tier].
	 *
	 * @param context            Android context for component lifecycle calls.
	 * @param isSessionUserInitiated  whether the session was started by the user.
	 * @param tier               current [PolicyTier] determining which components are active.
	 * @param notificationComponent shared notification component instance.
	 * @param trackingPolicyManager policy manager for adaptive location filtering (nullable).
	 * @param escalationEngine   engine for ski tracking component wiring.
	 * @param controller         service controller for forwarding ski state.
	 * @param scope              coroutine scope for ski state collection.
	 */
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
		val sessionComponent = SessionTrackerComponent(
			isSessionUserInitiated,
			appDatabase.sessionSegmentDao(),
			trackingParamsRepository,
		).apply {
			onEnable(context)
		}

		val preComponents = buildPreComponents(context, trackingPolicyManager)
		val dataComponents = buildDataComponents(context, tier)
		val errorCollector = DefaultPersistenceErrorCollector()

		// Enable notification component directly (no longer in generic list)
		notificationComponent.onEnable(context)

		// Build and enable ski components (if ski detection is enabled)
		val (skiTracking, skiWriter) = buildSkiComponents(
			context = context,
			escalationEngine = escalationEngine,
			controller = controller,
			scope = scope,
		)

		return ComponentSet(
			preComponents = preComponents,
			dataComponents = dataComponents,
			skiTrackingComponent = skiTracking,
			skiSegmentWriter = skiWriter,
			sessionComponent = sessionComponent,
			errorCollector = errorCollector,
		)
	}

	/**
	 * Build GPS-dependent data components added during tier escalation.
	 */
	suspend fun buildEscalationDataComponents(context: Context): List<DataTrackerComponent> {
		val components = listOf(
			CellTrackerComponent(),
			LocationTrackerComponent(),
			WifiTrackerComponent(),
		)
		for (component in components) { component.onEnable(context) }
		return components
	}

	private suspend fun buildPreComponents(
		context: Context,
		trackingPolicyManager: TrackingPolicyManager?,
	): List<PreTrackerComponent> {
		val components = mutableListOf<PreTrackerComponent>().apply {
			trackingPolicyManager?.let { policyMgr ->
				add(PolicyAwareLocationPreTrackerComponent(
					policyFlow = policyMgr.currentPolicy,
					trackingParamsRepository = trackingParamsRepository,
				))
			} ?: run {
				add(LocationPreTrackerComponent(trackingParamsRepository = trackingParamsRepository))
			}
		}
		for (component in components) { component.onEnable(context) }
		return components
	}

	private suspend fun buildDataComponents(
		context: Context,
		tier: PolicyTier,
	): List<DataTrackerComponent> {
		val components = mutableListOf<DataTrackerComponent>().apply {
			add(ActivityTrackerComponent())
			if (tier.isGpsEnabled) {
				add(CellTrackerComponent())
				add(LocationTrackerComponent())
				add(WifiTrackerComponent())
			}
		}
		for (component in components) { component.onEnable(context) }
		return components
	}

	private suspend fun buildSkiComponents(
		context: Context,
		escalationEngine: DefaultPolicyEscalationEngine,
		controller: TrackerServiceController,
		scope: CoroutineScope,
	): Pair<SkiTrackingComponent?, SkiSegmentWriter?> {
		val skiEnabled = trackingParamsRepository.data.first().skiDetectionEnabled
		if (!skiEnabled) return null to null

		val segmentWriter = SkiSegmentWriter()
		val skiComponent = SkiTrackingComponent(dispatchers).also {
			it.setEscalationEngine(escalationEngine)
			it.setSecondaryListener(segmentWriter)
			scope.launch {
				it.skiState.collect { skiState ->
					controller.updateSkiState(skiState)
				}
			}
		}
		segmentWriter.onEnable(context)
		skiComponent.onEnable(context)
		return skiComponent to segmentWriter
	}
}
