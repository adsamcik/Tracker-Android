package com.adsamcik.signalcollector.tracker.component

import androidx.annotation.AnyThread

@AnyThread
internal interface TrackerDataProducerObserver {
	fun onStateChange(shouldBeEnabled: Boolean, component: TrackerDataProducerComponent)
}
