package com.adsamcik.tracker.app.tracking

import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access to the real application graph for the availability identity check. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TrackingPurposeAvailabilityIdentityEntryPoint {
	fun reader(): TrackingPurposeAvailabilityReader
	fun reporter(): TrackingPurposeAvailabilityReporter
	fun currentReader(): CurrentTrackingPurposeAvailabilityReader
}
