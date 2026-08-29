package com.adsamcik.tracker.app.di

import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.coordinator.StepsSessionFactWriterTransitionCoordinator
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
	fun collectedDataLifecycleStore(): CollectedDataLifecycleStore
	fun stepsWriterTransitionCoordinator(): StepsSessionFactWriterTransitionCoordinator
}
