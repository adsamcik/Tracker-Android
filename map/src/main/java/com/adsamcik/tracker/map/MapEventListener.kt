package com.adsamcik.tracker.map

import com.google.android.libraries.maps.GoogleMap

/**
 * New class that handles all camera listener events.
 * During refactoring of the Map fragment, it was necessary to introduce
 * safe way to add and remove listeners even if it is as of implementing unnecessary
 */
internal class MapEventListener(val map: GoogleMap) {
	private val onCameraMoveListeners: MutableList<GoogleMap.OnCameraMoveListener> = mutableListOf()
	private val onCameraIdleListeners: MutableList<GoogleMap.OnCameraIdleListener> = mutableListOf()
	private val onCameraMoveStartedListeners: MutableList<GoogleMap.OnCameraMoveStartedListener> = mutableListOf()
	private val onCameraMoveCancelledListeners: MutableList<GoogleMap.OnCameraMoveCanceledListener> = mutableListOf()

	operator fun plusAssign(listener: GoogleMap.OnCameraIdleListener) {
		if (onCameraIdleListeners.isEmpty()) {
			map.setOnCameraIdleListener { onCameraIdleListeners.forEach { it.onCameraIdle() } }
		}
		onCameraIdleListeners.add(listener)
	}

	operator fun minusAssign(listener: GoogleMap.OnCameraIdleListener) {
		onCameraIdleListeners.remove(listener)
		if (onCameraIdleListeners.isEmpty()) {
			map.setOnCameraIdleListener(null)
		}
	}


	operator fun plusAssign(listener: GoogleMap.OnCameraMoveListener) {
		if (onCameraMoveListeners.isEmpty()) {
			map.setOnCameraMoveListener { onCameraMoveListeners.forEach { it.onCameraMove() } }
		}
		onCameraMoveListeners.add(listener)
	}

	operator fun minusAssign(listener: GoogleMap.OnCameraMoveListener) {
		onCameraMoveListeners.remove(listener)
		if (onCameraMoveListeners.isEmpty()) {
			map.setOnCameraMoveListener(null)
		}
	}


	operator fun plusAssign(listener: GoogleMap.OnCameraMoveStartedListener) {
		if (onCameraMoveStartedListeners.isEmpty()) {
			map.setOnCameraMoveStartedListener { reason ->
				onCameraMoveStartedListeners.forEach {
					it.onCameraMoveStarted(reason)
				}
			}
		}
		onCameraMoveStartedListeners.add(listener)
	}

	operator fun minusAssign(listener: GoogleMap.OnCameraMoveStartedListener) {
		onCameraMoveStartedListeners.remove(listener)
		if (onCameraMoveStartedListeners.isEmpty()) {
			map.setOnCameraMoveStartedListener(null)
		}
	}


	operator fun plusAssign(listener: GoogleMap.OnCameraMoveCanceledListener) {
		if (onCameraMoveCancelledListeners.isEmpty()) {
			map.setOnCameraMoveCanceledListener { onCameraMoveCancelledListeners.forEach { it.onCameraMoveCanceled() } }
		}
		onCameraMoveCancelledListeners.add(listener)
	}

	operator fun minusAssign(listener: GoogleMap.OnCameraMoveCanceledListener) {
		onCameraMoveCancelledListeners.remove(listener)
		if (onCameraMoveCancelledListeners.isEmpty()) {
			map.setOnCameraMoveStartedListener(null)
		}
	}
}

