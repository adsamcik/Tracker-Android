package com.adsamcik.tracker.map

import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.fragment.app.FragmentManager
import com.adsamcik.tracker.shared.base.extension.transaction
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

typealias GoogleMapListener = (map: GoogleMap) -> Unit

/**
 * Map owner.
 * Provides various lifecycle listeners for map with support for multiple listeners per type.
 */
@Suppress("MemberVisibilityCanBePrivate")
@AnyThread
class MapOwner : OnMapReadyCallback {
	private val initLock = ReentrantLock()
	private val listenerLock = ReentrantLock()

	private var map: GoogleMap? = null
	private var fragment: SupportMapFragment? = null

	private var isEnableRequested = false
	private var onCreateListeners = mutableListOf<GoogleMapListener>()
	private var onEnableListeners = mutableListOf<GoogleMapListener>()
	private var onDisableListeners = mutableListOf<GoogleMapListener>()

	/**
	 * Is map initialized
	 */
	val isInitialized: Boolean
		get() = fragment != null && map != null

	/**
	 * Is map enabled
	 */
	var isEnabled: Boolean = false
		private set

	/**
	 * Add listener on map creation.
	 * Invoked immediately if map is already created.
	 */
	fun addOnCreateListener(listener: GoogleMapListener) {
		initLock.withLock {
			if (!isInitialized) {
				onCreateListeners.add(listener)
				return
			}
		}

		listener.invoke(requireNotNull(map))
	}

	/**
	 * Add listener on map enable.
	 * Invoked immediately if map is already enabled.
	 */
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

	/**
	 * Add on map disable listener.
	 * Invoked only when map is disabled in the future.
	 */
	fun addOnDisableListener(listener: GoogleMapListener) {
		listenerLock.withLock {
			onDisableListeners.add(listener)
		}
	}

	/**
	 * Creates map if not already created.
	 * Calling repeatedly only creates map once.
	 */
	@Synchronized
	@MainThread
	fun createMap(fragmentManager: FragmentManager) {
		if (fragment != null) return

		val mapFragment = SupportMapFragment.newInstance()
		mapFragment.getMapAsync(this)

		fragmentManager.transaction {
			replace(R.id.container_map, mapFragment)
		}

		fragment = mapFragment
	}

	override fun onMapReady(map: GoogleMap) {
		var isEnableRequested: Boolean

		initLock.withLock {
			this.map = map
			isEnableRequested = this.isEnableRequested

			onCreateListeners.invokeEach(map)
		}

		if (isEnableRequested) {
			onEnable()
		}
	}

	/**
	 * Enables map and calls appropriate listeners.
	 */
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

	/**
	 * Disables map and calls appropriate listeners.
	 */
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


	private fun List<GoogleMapListener>.invokeEach(map: GoogleMap) {
		forEach { it.invoke(map) }
	}
}
