package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.ActivityTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.CellTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.LocationTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.data.WifiTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseCellComponent
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseLocationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseWifiComponent
import com.adsamcik.tracker.tracker.component.consumer.post.DatabaseWifiLocationCountComponent
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.PressureSampleWriter
import com.adsamcik.tracker.tracker.component.consumer.post.RawLocationWriter
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
	val postComponents: List<PostTrackerComponent>,
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
			appDatabase.sessionDao(),
			appDatabase.sessionSegmentDao(),
		).apply {
			onEnable(context)
		}

		val preComponents = buildPreComponents(context, trackingPolicyManager)
		val dataComponents = buildDataComponents(context, tier)
		val errorCollector = DefaultPersistenceErrorCollector()
		val postComponents = buildPostComponents(
			context = context,
			tier = tier,
			notificationComponent = notificationComponent,
			errorCollector = errorCollector,
			escalationEngine = escalationEngine,
			controller = controller,
			scope = scope,
		)

		return ComponentSet(
			preComponents = preComponents,
			dataComponents = dataComponents,
			postComponents = postComponents,
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

	/**
	 * Build GPS-dependent post components added during tier escalation.
	 */
	suspend fun buildEscalationPostComponents(
		context: Context,
		errorCollector: DefaultPersistenceErrorCollector?,
	): List<PostTrackerComponent> {
		val dualWrite = trackerSettingsRepository.data.first().legacyDualWriteEnabled
		val components = mutableListOf<PostTrackerComponent>()
		if (errorCollector != null) {
			components.add(DatabaseCellComponent(dualWrite).also { it.setErrorCollector(errorCollector) })
			components.add(DatabaseLocationComponent().also { it.setErrorCollector(errorCollector) })
			components.add(
				DatabaseWifiComponent(
					appDatabase.wifiDao(),
					appDatabase.wifiObservationDao(),
					dualWrite,
				).also { it.setErrorCollector(errorCollector) }
			)
		}
		components.add(
			DatabaseWifiLocationCountComponent(appDatabase.wifiLocationCountDao()).also { comp ->
				errorCollector?.let { comp.setErrorCollector(it) }
			}
		)
		components.add(
			RawLocationWriter(appDatabase.locationSampleDao()).also { comp ->
				errorCollector?.let { comp.setErrorCollector(it) }
			}
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
				add(PolicyAwareLocationPreTrackerComponent(policyMgr.currentPolicy))
			} ?: run {
				add(LocationPreTrackerComponent())
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

	private suspend fun buildPostComponents(
		context: Context,
		tier: PolicyTier,
		notificationComponent: NotificationComponent,
		errorCollector: DefaultPersistenceErrorCollector,
		escalationEngine: DefaultPolicyEscalationEngine,
		controller: TrackerServiceController,
		scope: CoroutineScope,
	): List<PostTrackerComponent> {
		val components = mutableListOf<PostTrackerComponent>()
		components.add(notificationComponent)
		components.add(PressureSampleWriter().also { it.setErrorCollector(errorCollector) })
		val skiEnabled = trackingParamsRepository.data.first().skiDetectionEnabled
		if (skiEnabled) {
			val segmentWriter = SkiSegmentWriter()
			components.add(segmentWriter)
			components.add(SkiTrackingComponent().also { skiComponent ->
				skiComponent.setEscalationEngine(escalationEngine)
				skiComponent.setSecondaryListener(segmentWriter)
				scope.launch {
					skiComponent.skiState.collect { skiState ->
						controller.updateSkiState(skiState)
					}
				}
			})
		}
		if (tier.isGpsEnabled) {
			val dualWrite = trackerSettingsRepository.data.first().legacyDualWriteEnabled
			components.add(DatabaseCellComponent(dualWrite).also { it.setErrorCollector(errorCollector) })
			components.add(DatabaseLocationComponent().also { it.setErrorCollector(errorCollector) })
			components.add(
				DatabaseWifiComponent(
					appDatabase.wifiDao(),
					appDatabase.wifiObservationDao(),
					dualWrite,
				).also { it.setErrorCollector(errorCollector) }
			)
			components.add(
				DatabaseWifiLocationCountComponent(
					appDatabase.wifiLocationCountDao(),
				).also { it.setErrorCollector(errorCollector) }
			)
			components.add(
				RawLocationWriter(
					appDatabase.locationSampleDao(),
				).also { it.setErrorCollector(errorCollector) }
			)
		}
		for (component in components) { component.onEnable(context) }
		return components
	}
}
