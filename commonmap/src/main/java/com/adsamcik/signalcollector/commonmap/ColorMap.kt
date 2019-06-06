package com.adsamcik.signalcollector.commonmap

import android.content.Context
import android.content.res.Resources
import androidx.annotation.RawRes
import com.adsamcik.signalcollector.common.color.ColorController
import com.adsamcik.signalcollector.common.color.ColorManager
import com.adsamcik.signalcollector.common.misc.extension.remove
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapStyleOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

object ColorMap {
	private val colorChangeListeners = mutableListOf<WeakReference<GoogleMap>>()
	private var resources: Resources? = null
	private var colorController: ColorController? = null

	private var activeMapStyle: MapStyleOptions? = null
	private var activeMapStyleRes: Int = 0

	private fun init(context: Context) {
		if (resources == null) resources = context.resources
		if (colorController == null) {
			ColorManager.createController().also {
				colorController = it
				it.addListener(this::onColorChange)
			}
		}
	}

	private fun destroy() {
		resources = null
		colorController?.let { ColorManager.recycleController(it) }
	}

	private fun onColorChange(luminance: Byte, foregroundColor: Int, backgroundColor: Int) {
		val newStyle = getMapStyleRes(luminance)
		if (newStyle != activeMapStyleRes) {
			activeMapStyleRes = newStyle
			activeMapStyle = loadMapStyleRes(newStyle).also { onStyleChange(it) }
		}
	}

	private fun removeNullMaps() {
		synchronized(colorChangeListeners) {
			colorChangeListeners.removeAll { it.get() == null }
			checkIfEmpty()
		}
	}

	private fun onStyleChange(style: MapStyleOptions) {
		removeNullMaps()
		GlobalScope.launch(Dispatchers.Main) {
			synchronized(colorChangeListeners) {
				colorChangeListeners.forEach {
					it.get()?.setMapStyle(style)
				}
			}
		}
	}

	fun addListener(context: Context, googleMap: GoogleMap) {
		synchronized(colorChangeListeners) {
			val isEmpty = colorChangeListeners.isEmpty()
			colorChangeListeners.add(WeakReference(googleMap))
			if (isEmpty) init(context) else googleMap.setMapStyle(activeMapStyle)
			removeNullMaps()
		}
	}

	fun removeListener(googleMap: GoogleMap) {
		synchronized(colorChangeListeners) {
			colorChangeListeners.remove { it.get() == googleMap }
			removeNullMaps()
		}
	}

	private fun checkIfEmpty() {
		if (colorChangeListeners.isEmpty()) destroy()
	}

	private fun loadMapStyleRes(@RawRes mapStyleRes: Int): MapStyleOptions {
		resources!!.openRawResource(mapStyleRes).bufferedReader().use { return MapStyleOptions(it.readText()) }
	}

	@RawRes
	private fun getMapStyleRes(luminance: Byte): Int {
		return when {
			luminance > 24 -> R.raw.map_style_light
			luminance < -48 -> R.raw.map_style_dark
			else -> R.raw.map_style_grey
		}
	}
}