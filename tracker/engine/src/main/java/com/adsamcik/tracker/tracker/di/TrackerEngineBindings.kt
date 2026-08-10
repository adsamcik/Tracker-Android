package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.tracker.insights.DefaultSessionInsightsGenerator
import com.adsamcik.tracker.tracker.insights.SessionInsightsGenerator
import com.adsamcik.tracker.tracker.notification.DefaultTrackerNotificationSettingsRepository
import com.adsamcik.tracker.tracker.notification.TrackerNotificationSettingsRepository
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.RoomActivityRecognitionEventIngress
import com.adsamcik.tracker.tracker.source.ingress.RoomDurableSourceIngress
import com.adsamcik.tracker.tracker.source.projection.Projection
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntime
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.coordinator.DefaultTrackingSettingsStatusProvider
import com.adsamcik.tracker.tracker.source.coordinator.TrackingSettingsStatusProvider
import com.adsamcik.tracker.tracker.source.battery.BatteryImpactEstimator
import com.adsamcik.tracker.tracker.source.battery.QualitativeBatteryImpactEstimator
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.runtime.AndroidLocationDeviceStateProvider
import com.adsamcik.tracker.tracker.source.runtime.LocationDeviceStateProvider
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEventIngress
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackerEngineBindings {
	@Binds
	abstract fun bindActivityWatcherController(
		impl: ActivityWatcherServiceController,
	): ActivityWatcherController

	@Binds
	abstract fun bindSessionInsightsGenerator(
		impl: DefaultSessionInsightsGenerator,
	): SessionInsightsGenerator

	@Binds
	abstract fun bindTrackerNotificationSettingsRepository(
		impl: DefaultTrackerNotificationSettingsRepository,
	): TrackerNotificationSettingsRepository

	@Binds
	abstract fun bindDurableSourceIngress(
		impl: RoomDurableSourceIngress,
	): DurableSourceIngress

	@Binds
	abstract fun bindActivityRecognitionEventIngress(
		impl: RoomActivityRecognitionEventIngress,
	): ActivityRecognitionEventIngress

	@Binds
	abstract fun bindTrackingSettingsStatusProvider(
		impl: DefaultTrackingSettingsStatusProvider,
	): TrackingSettingsStatusProvider

	@Binds
	abstract fun bindBatteryImpactEstimator(
		impl: QualitativeBatteryImpactEstimator,
	): BatteryImpactEstimator

	@Binds
	abstract fun bindTrackingRolloutStateStore(
		impl: RoomTrackingRolloutStateStore,
	): TrackingRolloutStateStore

	@Binds
	abstract fun bindLocationDeviceStateProvider(
		impl: AndroidLocationDeviceStateProvider,
	): LocationDeviceStateProvider

	@Multibinds
	abstract fun bindSourceProjections(): Set<Projection>

	@Multibinds
	abstract fun bindSourceRuntimes(): Set<SourceRuntime<out SourcePlan>>
}
