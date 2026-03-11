package com.adsamcik.tracker.tracker.component.consumer.pre

import android.content.Context
import android.os.Build
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal class LocationPreTrackerComponent : PreTrackerComponent, CoroutineScope {
	override val requiredData: Collection<TrackerComponentRequirement> = listOf(
		TrackerComponentRequirement.LOCATION
	)

	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	private var requiredAccuracy = 0
	private var accuracyJob: Job? = null

	override suspend fun onEnable(context: Context) {
		withContext(coroutineContext) {
			requiredAccuracy = Preferences.getPref(context).fetchIntRes(
				com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_required_accuracy_key,
				com.adsamcik.tracker.shared.preferences.R.integer.settings_tracking_required_accuracy_default
			)
			accuracyJob?.cancel()
			accuracyJob = PreferenceFlows.int(
				context,
				com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_required_accuracy_key,
				com.adsamcik.tracker.shared.preferences.R.integer.settings_tracking_required_accuracy_default
			).onEach { requiredAccuracy = it }
				.launchIn(this@LocationPreTrackerComponent)
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
