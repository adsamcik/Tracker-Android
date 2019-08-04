package com.adsamcik.signalcollector.tracker.component

import android.content.Context
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.extension.mapIf
import com.adsamcik.signalcollector.common.preference.observer.PreferenceObserver
import com.adsamcik.signalcollector.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.signalcollector.tracker.component.consumer.data.ActivityTrackerComponent
import com.adsamcik.signalcollector.tracker.component.consumer.data.CellTrackerComponent
import com.adsamcik.signalcollector.tracker.component.consumer.data.LocationTrackerComponent
import com.adsamcik.signalcollector.tracker.component.consumer.data.WifiTrackerComponent
import com.adsamcik.signalcollector.tracker.component.producer.ActivityDataProducer
import com.adsamcik.signalcollector.tracker.component.producer.CellDataProducer
import com.adsamcik.signalcollector.tracker.component.producer.WifiDataProducer
import com.adsamcik.signalcollector.tracker.data.collection.CollectionTempData
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionTempData
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

internal class DataProducerManager(context: Context) : TrackerDataProducerObserver, CoroutineScope {
	private val appContext = context.applicationContext

	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	/**
	 * Keeps all producers from being recycled. Producers should take only very little memory so this is fine.
	 */
	@Suppress("unused")
	private val producerList = listOf(WifiDataProducer(this),
			CellDataProducer(this),
			ActivityDataProducer(this))

	private val activeProducerList = mutableListOf<TrackerDataProducerComponent>()

	suspend fun onEnable() = coroutineScope {
		producerList.forEach { it.onAttach(appContext) }
	}

	override fun onStateChange(isEnabled: Boolean, component: TrackerDataProducerComponent) {
		if (isEnabled) {
			activeProducerList.add(component)
			component.onEnable(appContext)
		} else {
			activeProducerList.remove(component)
			component.onDisable(appContext)
		}
	}


	suspend fun onDisable() = coroutineScope {
		producerList.forEach { it.onDetach(appContext) }
		activeProducerList.clear()
	}

	suspend fun getData(tempData: MutableCollectionTempData) {
		withContext(coroutineContext) {
			activeProducerList.map {
				async { it.onDataRequest(tempData) }
			}.awaitAll()
		}
	}
}