package com.adsamcik.tracker.tracker.component.consumer.pre

import android.content.Context
import android.os.Build
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository

import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal class LocationPreTrackerComponent(
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
	private val trackingParamsRepository: TrackingParamsRepository? = null,
) : PreTrackerComponent, CoroutineScope {
	override val requiredData: Collection<TrackerComponentRequirement> = listOf(
		TrackerComponentRequirement.LOCATION
	)

	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = dispatchers.main + job

	private var requiredAccuracy = 0
	private var accuracyJob: Job? = null

	override suspend fun onEnable(context: Context) {
		withContext(coroutineContext) {
			accuracyJob?.cancel()
			val repository = trackingParamsRepository
			if (repository != null) {
				requiredAccuracy = repository.data.first().requiredAccuracyMeters
				accuracyJob = repository.data
					.onEach { requiredAccuracy = it.requiredAccuracyMeters }
					.launchIn(this@LocationPreTrackerComponent)
			} else {
				requiredAccuracy = Preferences(context).fetchInt(
					PreferenceKeys.TRACKING_REQUIRED_ACCURACY,
					PreferenceKeys.TRACKING_REQUIRED_ACCURACY_DEFAULT
				)
				accuracyJob = PreferenceFlows.int(
					context,
					PreferenceKeys.TRACKING_REQUIRED_ACCURACY,
					PreferenceKeys.TRACKING_REQUIRED_ACCURACY_DEFAULT
				).onEach { requiredAccuracy = it }
					.launchIn(this@LocationPreTrackerComponent)
			}
		}
	}

	override suspend fun onDisable(context: Context) {
		withContext(coroutineContext) {
			accuracyJob?.cancel()
			accuracyJob = null
		}
	}

	override suspend fun onNewData(cycle: TrackingCycle): Boolean {
		val location = requireNotNull(cycle.location).lastLocation

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			if (location.isMock) return false
		} else {
			@Suppress("deprecation")
			if (location.isFromMockProvider) return false
		}

		if (!location.hasAccuracy()) return false

		return location.accuracy <= requiredAccuracy
	}
}
