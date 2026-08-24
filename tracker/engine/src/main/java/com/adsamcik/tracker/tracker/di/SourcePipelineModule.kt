package com.adsamcik.tracker.tracker.di

import android.content.Context
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjection
import com.adsamcik.tracker.tracker.source.projection.Projection
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.runtime.AndroidBootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.ActivitySourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.PressureSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.LocationSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.CellSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SharedStepSourceController
import com.adsamcik.tracker.tracker.source.runtime.WifiSourceRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SourcePipelineModule {
	@Provides
	@Singleton
	fun provideBootClockDomainProvider(
		@ApplicationContext context: Context,
	): BootClockDomainProvider = AndroidBootClockDomainProvider(context)

	@Provides
	@IntoSet
	fun provideActivityAutomationProjection(projection: ActivityAutomationProjection): Projection = projection

	@Provides
	@IntoSet
	fun provideActivitySourceRuntime(runtime: ActivitySourceRuntime): SourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideStepSourceRuntime(runtime: SharedStepSourceController): SourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun providePressureSourceRuntime(runtime: PressureSourceRuntime): SourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideLocationSourceRuntime(runtime: LocationSourceRuntime): SourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideWifiSourceRuntime(runtime: WifiSourceRuntime): SourceRuntime<out SourcePlan> = runtime

	@Provides
	@IntoSet
	fun provideCellSourceRuntime(runtime: CellSourceRuntime): SourceRuntime<out SourcePlan> = runtime
}
