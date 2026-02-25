package com.adsamcik.tracker.tracker.service

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.tracker.component.DataProducerManager
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.stats.api.PolicyTier
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
import com.adsamcik.tracker.tracker.component.consumer.post.RawLocationWriter
import com.adsamcik.tracker.tracker.component.consumer.pre.LocationPreTrackerComponent
import com.adsamcik.tracker.tracker.component.consumer.pre.PolicyAwareLocationPreTrackerComponent
import com.adsamcik.tracker.tracker.data.DefaultPersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import com.adsamcik.tracker.tracker.module.TrackerListenerManager
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manages the lifecycle and dependencies of tracker components.
 * Extracts complexity from [TrackerService].
 */
internal class TrackerComponentManager @Inject constructor() {

    private val preComponentList = mutableListOf<PreTrackerComponent>()
    private val postComponentList = mutableListOf<PostTrackerComponent>()
    private val dataComponentList = mutableListOf<DataTrackerComponent>()
    
    // Exposed for error reporting in service
    val notificationComponent: NotificationComponent = NotificationComponent()
    
    /**
     * Collector for database persistence errors.
     * Observable by TrackerService to surface errors to UI.
     */
    var persistenceErrorCollector: PersistenceErrorCollector? = null
        private set

    var mobileSessionComponent: SessionTrackerComponent? = null
        private set
        
    var dataProducerManager: DataProducerManager? = null
        private set
        
    var trackingPolicyManager: TrackingPolicyManager? = null
        private set

    /**
     * Initializes all components.
     */
    suspend fun initialize(
        context: Context,
        scope: CoroutineScope,
        isSessionUserInitiated: Boolean,
        initialTier: PolicyTier = PolicyTier.PRECISION,
        controller: TrackerServiceController,
        onPolicyChanged: suspend (com.adsamcik.tracker.tracker.policy.TrackingPolicy) -> Unit
    ) {
        // Clear existing components
        disableAll(context)
        preComponentList.clear()
        dataComponentList.clear()
        postComponentList.clear()

        mobileSessionComponent = SessionTrackerComponent(isSessionUserInitiated).apply {
            onEnable(context)
        }

        // Emit initial session
        mobileSessionComponent?.let { controller.updateSession(it.session) }

        // Data Producer
        dataProducerManager = DataProducerManager(context, initialTier).apply { onEnable() }

        // Policy Manager
        trackingPolicyManager = TrackingPolicyManager(
            context = context,
            isUserInitiated = isSessionUserInitiated
        ).apply {
            start()
        }

        // Observe policy changes
        trackingPolicyManager?.let { policyManager ->
            scope.launch {
                policyManager.currentPolicy.collect { newPolicy ->
                    onPolicyChanged(newPolicy)
                }
            }
        }

        // Pre Components
        preComponentList.apply {
             trackingPolicyManager?.let { policyMgr ->
                add(PolicyAwareLocationPreTrackerComponent(policyMgr.currentPolicy))
            } ?: run {
                add(LocationPreTrackerComponent())
            }
        }.forEach { it.onEnable(context) }

        // Data Components
        dataComponentList.apply {
            add(ActivityTrackerComponent())
            if (initialTier.isGpsEnabled) {
                add(CellTrackerComponent())
                add(LocationTrackerComponent())
                add(WifiTrackerComponent())
            }
        }.forEach { it.onEnable(context) }

        // Create persistence error collector
        val errorCollector = DefaultPersistenceErrorCollector()
        persistenceErrorCollector = errorCollector

        // Post Components - inject error collector into database components
        postComponentList.apply {
            add(notificationComponent)
            if (initialTier.isGpsEnabled) {
                add(DatabaseCellComponent().also { it.setErrorCollector(errorCollector) })
                add(DatabaseLocationComponent().also { it.setErrorCollector(errorCollector) })
                add(DatabaseWifiComponent().also { it.setErrorCollector(errorCollector) })
                add(DatabaseWifiLocationCountComponent())
                add(RawLocationWriter())
            }
        }.forEach { it.onEnable(context) }
    }

    /**
     * Updates data through the pipeline: Pre -> Data -> Post.
     */
    suspend fun updateData(
        tempData: MutableCollectionTempData,
        context: Context,
        session: TrackerSession,
        controller: TrackerServiceController,
        trackerListenerManager: TrackerListenerManager
    ): MutableCollectionData? {
        requireNotNull(dataProducerManager).getData(tempData)

        // Pre-req check
        if (!preComponentList.all {
                if (it.requirementsMet(tempData)) {
                    com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport({ true }) {
                        it.onNewData(tempData)
                    }
                } else {
                    true
                }
            }) {
            return null
        }

        val collectionData = MutableCollectionData(tempData.timeMillis)

        // Collect Data
        dataComponentList
            .asSequence()
            .filter { it.requirementsMet(tempData) }
            .forEach {
                tryWithReport {
                    it.onDataUpdated(tempData, collectionData)
                }
            }

        requireNotNull(mobileSessionComponent).onDataUpdated(tempData, collectionData)

        // Update Controller
        controller.updateSession(session)
        controller.updateCollectionData(collectionData)

        // Post Processing
        postComponentList
            .asSequence()
            .filter { it.requirementsMet(tempData) }
            .forEach {
                tryWithReport {
                    it.onNewData(context, session, collectionData, tempData)
                }
            }
            
        // Notify Listeners
        trackerListenerManager.send(context, session, collectionData)

        return collectionData
    }
    
    /**
     * Feeds data back into the policy manager for adaptive tracking.
     */
    fun feedPolicyManager(
        tempData: MutableCollectionTempData,
        collectionData: CollectionData,
        scope: CoroutineScope,
        accumulatedStepCount: Int,
        lastLocation: com.adsamcik.tracker.shared.base.data.Location?,
        lastActivityType: Int
    ): Triple<Int, com.adsamcik.tracker.shared.base.data.Location?, Int> { // Returns updated state
        
        var newStepCount = accumulatedStepCount
        var newLastLocation = lastLocation
        var newLastActivityType = lastActivityType

        trackingPolicyManager?.let { policyMgr ->
            tryWithReport {
                val currentTimeMs = tempData.timeMillis

                // Activity Transition
                collectionData.activity?.let { activity ->
                    val currentActivityType = activity.activityType
                    if (newLastActivityType >= 0 && newLastActivityType != currentActivityType) {
                        scope.launch {
                            policyMgr.onActivityTransition(
                                activityType = currentActivityType,
                                confidence = activity.confidence,
                                timeMs = currentTimeMs
                            )
                        }
                    }
                    newLastActivityType = currentActivityType
                }

                // Location Displacement
                collectionData.location?.let { location ->
                    newLastLocation?.let { prevLocation ->
                        val distance = prevLocation.distance(
                            location,
                            com.adsamcik.tracker.shared.base.data.LengthUnit.Meter
                        ).toFloat()
                        if (distance > 0f) {
                            scope.launch {
                                policyMgr.onLocationChange(
                                    displacementMeters = distance,
                                    timeMs = currentTimeMs
                                )
                            }
                        }
                    }
                    newLastLocation = location
                }

                // Step Updates
                tempData.tryGet<Int>(com.adsamcik.tracker.tracker.component.producer.StepDataProducer.NEW_STEPS_ARG)?.let { newSteps ->
                    if (newSteps > 0) {
                        newStepCount += newSteps
                        scope.launch {
                            policyMgr.onStepUpdate(
                                stepCount = newStepCount,
                                timeMs = currentTimeMs
                            )
                        }
                    }
                }
            }
        }
        
        return Triple(newStepCount, newLastLocation, newLastActivityType)
    }

    suspend fun disableAll(context: Context) {
        dataProducerManager?.onDisable()
        trackingPolicyManager?.stop()
        
        preComponentList.forEach { tryWithReport { it.onDisable(context) } }
        dataComponentList.forEach { tryWithReport { it.onDisable(context) } }
        postComponentList.forEach { tryWithReport { it.onDisable(context) } }
        
        persistenceErrorCollector = null
    }
    
    suspend fun flushPending(scope: CoroutineScope) {
        postComponentList.filterIsInstance<DatabaseLocationComponent>().firstOrNull()?.let { comp ->
            scope.launch { comp.flushPending() }
        }
    }
}
