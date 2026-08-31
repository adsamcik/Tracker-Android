package com.adsamcik.tracker.app.di

import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.tracker.source.coordinator.StepsSessionFactWriterTransitionCoordinator
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Narrow process entry point for integration checks that cross app and tracking modules.
 *
 * Keeping this contract in the production graph lets instrumentation verify the exact singleton
 * instances used by deletion and writer re-arm without replacing the application or its Hilt graph.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TrackingInfrastructureIntegrationEntryPoint {
	fun activityRegistrationArbiter(): ActivityRegistrationArbiter
	fun collectedDataLifecycleStore(): CollectedDataLifecycleStore
	fun durableSourceIngress(): DurableSourceIngress
	fun stepsWriterTransitionCoordinator(): StepsSessionFactWriterTransitionCoordinator
	fun trackingHistoryRepository(): TrackingHistoryRepository
	fun trackingParamsRepository(): TrackingParamsRepository
}
