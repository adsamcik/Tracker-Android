package com.adsamcik.tracker.tracker.module

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.remove
import com.adsamcik.tracker.shared.utils.module.TrackerUpdateReceiver

/**
 * Manages all tracker listeners.
 */
internal object TrackerListenerManager {
	private val listenerList: MutableList<TrackerUpdateReceiver> = mutableListOf()

	private var lastCollectionData: CollectionData? = null
	private var lastSessionData: TrackerSession? = null

	/**
	 * Registers new component.
	 */
	fun register(context: Context, component: TrackerUpdateReceiver) {
		synchronized(listenerList) {
			val index = listenerList.indexOfFirst { it::class.java == component::class.java }
			if (index >= 0) {
				Reporter.report("There is already an active listener for class ${component::class.java.name}. Has it leaked?")
				listenerList[index] = component
			} else {
				listenerList.add(component)
			}

			if (lastSessionData != null && lastCollectionData != null) {
				component.onNewData(
						context,
						requireNotNull(lastSessionData),
						requireNotNull(lastCollectionData)
				)
			}
		}
	}

	/**
	 * Unregister class from listener.
	 */
	fun unregister(componentClass: Class<TrackerUpdateReceiver>) {
		synchronized(listenerList) {
			val anyRemoved = listenerList.remove { it::class.java == componentClass }
			if (!anyRemoved) {
				Reporter.report("Tried to unregister ${componentClass.name} but it was not registered.")
			}
		}
	}

	/**
	 * Sends update to all listeners.
	 */
	fun send(context: Context, session: TrackerSession, data: CollectionData) {
		synchronized(listenerList) {
			lastSessionData = session
			lastCollectionData = data

			listenerList.forEach {
				it.onNewData(context, session, data)
			}
		}
	}
}
