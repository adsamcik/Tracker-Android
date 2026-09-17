package com.adsamcik.tracker.diagnostics

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object TrackingDiagnosticRecorderModule {
	@Provides
	@Singleton
	fun provideTrackingDiagnosticRecorder(): TrackingDiagnosticRecorder =
		TrackingDiagnosticRecorder.local()
}
