package com.adsamcik.tracker.shared.map

import android.content.Context
import android.content.res.Resources
import android.content.res.Configuration
import androidx.annotation.RawRes
import com.adsamcik.tracker.shared.base.extension.remove
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapStyleOptions
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

/**
 * Object that provides color updates to maps.
 */
object ColorMap {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val styleChangeListeners = mutableListOf<WeakReference<GoogleMap>>()
	private var resources: Resources? = null

	private var activeMapStyle: MapStyleOptions? = null
	private var activeMapStyleRes: Int = 0

	private fun init(context: Context) {
		if (resources == null) resources = context.resources
		// Initialize current style based on UI mode
		val resId = getMapStyleRes(context)
		if (resId != activeMapStyleRes) {
			activeMapStyleRes = resId
			activeMapStyle = loadMapStyleRes(resId)
		}
	}

	private fun destroy() {
		scope.launch {
			synchronized(styleChangeListeners) {
				if (styleChangeListeners.isEmpty()) {
					resources = null
					activeMapStyle = null
				}
			}
		}
	}

	/**
	 * Public hook to update theme manually (e.g., when app theme toggles).
	 */
	fun updateTheme(context: Context) {
		val newStyle = getMapStyleRes(context)
		if (newStyle != activeMapStyleRes) {
			activeMapStyleRes = newStyle
			activeMapStyle = loadMapStyleRes(newStyle)
			onStyleChange(requireNotNull(activeMapStyle))
		}
	}

	private fun removeNullMaps() {
		synchronized(styleChangeListeners) {
			styleChangeListeners.removeAll { it.get() == null }
			checkIfEmpty()
		}
	}

	private fun onStyleChange(style: MapStyleOptions) {
		removeNullMaps()
		scope.launch(Dispatchers.Main) {
			synchronized(styleChangeListeners) {
				styleChangeListeners.forEach {
					it.get()?.setMapStyle(style)
				}
			}
		}
	}

	fun addListener(context: Context, googleMap: GoogleMap) {
		synchronized(styleChangeListeners) {
			val isEmpty = styleChangeListeners.isEmpty()
			styleChangeListeners.add(WeakReference(googleMap))
			if (isEmpty) init(context) else googleMap.setMapStyle(activeMapStyle)
			removeNullMaps()
		}
	}

	fun removeListener(googleMap: GoogleMap) {
		synchronized(styleChangeListeners) {
			styleChangeListeners.remove { it.get() == googleMap }
			removeNullMaps()
		}
	}

	private fun checkIfEmpty() {
		synchronized(styleChangeListeners) {
			if (styleChangeListeners.isEmpty()) destroy()
		}
	}

	private fun loadMapStyleRes(@RawRes mapStyleRes: Int): MapStyleOptions {
		requireNotNull(resources).openRawResource(mapStyleRes).bufferedReader()
				.use { return MapStyleOptions(it.readText()) }
	}

	@RawRes
	private fun getMapStyleRes(context: Context): Int {
		val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
		return when (nightMask) {
			Configuration.UI_MODE_NIGHT_YES -> R.raw.map_style_dark
			Configuration.UI_MODE_NIGHT_NO -> R.raw.map_style_default
			else -> R.raw.map_style_grey
		}
	}
}
