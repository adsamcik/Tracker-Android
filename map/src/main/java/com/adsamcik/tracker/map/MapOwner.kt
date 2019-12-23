package com.adsamcik.tracker.map

import androidx.annotation.AnyThread
import androidx.fragment.app.FragmentManager
import com.adsamcik.tracker.shared.base.extension.transaction
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

typealias GoogleMapListener = (map: GoogleMap) -> Unit

@AnyThread
class MapOwner : OnMapReadyCallback {
	private var map: GoogleMap? = null
	private var fragment: SupportMapFragment? = null

	private val initLock = ReentrantLock()

	private val listenerLock = ReentrantLock()

	val isInitialized: Boolean
		get() = fragment != null && map != null

	var isEnabled: Boolean = false
		private set

	private var isEnableRequested = false

	private var onCreateListeners = mutableListOf<GoogleMapListener>()

	private var onEnableListeners = mutableListOf<GoogleMapListener>()

	private var onDisableListeners = mutableListOf<GoogleMapListener>()

	fun addOnCreateListener(listener: GoogleMapListener) {
		initLock.withLock {
			if (!isInitialized) {
				onCreateListeners.add(listener)
				return
			}
		}

		listener.invoke(requireNotNull(map))
	}

	fun addOnEnableListener(listener: GoogleMapListener) {
		listenerLock.withLock {
			onEnableListeners.add(listener)
		}

		initLock.withLock {
			if (isEnabled) {
				listener.invoke(requireNotNull(map))
			}
		}
	}

	fun addOnDisableListener(listener: GoogleMapListener) {
		listenerLock.withLock {
			onDisableListeners.add(listener)
		}
	}

	@Synchronized
	fun createMap(fragmentManager: FragmentManager) {
		if (fragment != null) return

		val mapFragment = SupportMapFragment.newInstance()
		mapFragment.getMapAsync(this)

		fragmentManager.transaction {
			replace(R.id.container_map, mapFragment)
		}

		fragment = mapFragment
	}

	private fun List<GoogleMapListener>.invokeEach(map: GoogleMap) {
		forEach { it.invoke(map) }
	}

	override fun onMapReady(map: GoogleMap) {
		var isEnableRequested = false

		initLock.withLock {
			this.map = map
			isEnableRequested = this.isEnableRequested

			onCreateListeners.invokeEach(map)
		}

		if (isEnableRequested) {
			onEnable()
		}
	}

	fun onEnable() {
		initLock.withLock {
			if (isEnabled) return

			if (!isInitialized) {
				isEnableRequested = true
				return
			} else {
				isEnabled = true
			}
		}

		listenerLock.withLock {
			onEnableListeners.invokeEach(requireNotNull(map))
		}
	}

	fun onDisable() {
		initLock.withLock {
			if (!isEnabled) return

			if (!isInitialized) {
				isEnableRequested = false
				return
			} else {
				isEnabled = false
			}
		}

		listenerLock.withLock {
			onDisableListeners.invokeEach(requireNotNull(map))
		}
	}
}
