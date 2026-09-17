package com.adsamcik.tracker.diagnostics

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object TrackingDiagnosticRecorderModule {
	@Provides
	@Singleton
	fun provideTrackingDiagnosticDatabase(
		@ApplicationContext context: Context,
	): TrackingDiagnosticDatabase = TrackingDiagnosticDatabase.create(context)

	@Provides
	@Singleton
	fun provideRoomTrackingDiagnosticStore(
		database: TrackingDiagnosticDatabase,
	): RoomTrackingDiagnosticStore = RoomTrackingDiagnosticStore(database)

	@Provides
	fun provideTrackingDiagnosticEventStore(
		store: RoomTrackingDiagnosticStore,
	): TrackingDiagnosticEventStore = store

	@Provides
	fun provideTrackingDiagnosticHistory(
		store: RoomTrackingDiagnosticStore,
	): TrackingDiagnosticHistory = store

	@Provides
	fun provideTrackingDiagnosticDataControl(
		store: RoomTrackingDiagnosticStore,
	): TrackingDiagnosticDataControl = store

	@Provides
	fun provideTrackingDiagnosticMaintenance(
		store: RoomTrackingDiagnosticStore,
	): TrackingDiagnosticMaintenance = store

	@Provides
	@Singleton
	fun provideTrackingDiagnosticRecorder(
		store: TrackingDiagnosticEventStore,
	): TrackingDiagnosticRecorder = TrackingDiagnosticRecorder.local(store)
}
