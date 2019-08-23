package com.adsamcik.tracker.commonmap

import android.content.Context
import android.content.res.Resources
import androidx.annotation.RawRes
import com.adsamcik.tracker.common.extension.remove
import com.adsamcik.tracker.common.style.StyleController
import com.adsamcik.tracker.common.style.StyleData
import com.adsamcik.tracker.common.style.StyleManager
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapStyleOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

object ColorMap {
	private val styleChangeListeners = mutableListOf<WeakReference<GoogleMap>>()
	private var resources: Resources? = null
	private var styleController: StyleController? = null

	private var activeMapStyle: MapStyleOptions? = null
	private var activeMapStyleRes: Int = 0

	private fun init(context: Context) {
		if (resources == null) resources = context.resources
		if (styleController == null) {
			StyleManager.createController().also {
				styleController = it
				it.addListener(this::onColorChange)
			}
		}
	}

	private fun destroy() {
		GlobalScope.launch {
			synchronized(styleChangeListeners) {
				if (styleChangeListeners.isEmpty()) {
					resources = null
					styleController?.let { StyleManager.recycleController(it) }
					styleController = null
					activeMapStyle = null
				}
			}
		}
	}

	private fun onColorChange(styleData: StyleData) {
		val newStyle = getMapStyleRes(styleData)
		if (newStyle != activeMapStyleRes) {
			activeMapStyleRes = newStyle
			activeMapStyle = loadMapStyleRes(newStyle).also { onStyleChange(it) }
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
		GlobalScope.launch(Dispatchers.Main) {
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
		resources!!.openRawResource(mapStyleRes).bufferedReader()
				.use { return MapStyleOptions(it.readText()) }
	}

	@RawRes
	private fun getMapStyleRes(styleData: StyleData): Int {
		val perceivedLuminance = styleData.perceivedLuminance(false)
		return when {
			styleData.saturation > 0.6f -> R.raw.map_style_vibrant
			perceivedLuminance > 24 -> R.raw.map_style_light
			perceivedLuminance < -48 -> R.raw.map_style_dark
			else -> R.raw.map_style_grey
		}
	}
}
