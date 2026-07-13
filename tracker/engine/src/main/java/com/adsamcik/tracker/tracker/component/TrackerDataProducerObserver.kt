package com.adsamcik.tracker.tracker.component

import androidx.annotation.AnyThread

@AnyThread
internal interface TrackerDataProducerObserver {
	suspend fun onStateChange(shouldBeEnabled: Boolean, component: TrackerDataProducerComponent)
}
