package com.adsamcik.tracker.tracker.module

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.di.DefaultDispatcher
import com.adsamcik.tracker.shared.base.extension.remove
import com.adsamcik.tracker.shared.utils.module.TrackerSessionChannel
import com.adsamcik.tracker.shared.utils.module.TrackerUpdateReceiver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages all tracker listeners.
 * 
 * Injectable singleton per Section 16A guidelines.
 * Scoped to application lifetime via @Singleton.
 * Uses explicit dispatcher injection for testability.
 */
@Singleton
class TrackerListenerManager @Inject constructor(
	@DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
	private val sessionChannel: TrackerSessionChannel,
) {
	private val job = SupervisorJob()
	private val scope = CoroutineScope(defaultDispatcher + job)

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
				scope.launch {
					component.onNewData(
							context,
							requireNotNull(lastSessionData),
							requireNotNull(lastCollectionData)
					)
				}
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
		sessionChannel.emit(session)
	}
}
