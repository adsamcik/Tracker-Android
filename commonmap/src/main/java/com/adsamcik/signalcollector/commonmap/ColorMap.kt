package com.adsamcik.signalcollector.commonmap

import android.content.Context
import android.content.res.Resources
import androidx.annotation.RawRes
import com.adsamcik.signalcollector.common.color.ColorController
import com.adsamcik.signalcollector.common.color.ColorManager
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapStyleOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object ColorMap {
	private val colorChangeListeners = ArrayList<GoogleMap>(0)
	private var resources: Resources? = null
	private var colorController: ColorController? = null

	private var activeMapStyle: MapStyleOptions? = null
	private var activeMapStyleRes: Int = 0

	private fun init(context: Context) {
		if (resources == null) resources = context.resources
		if (colorController == null) {
			ColorManager.createColorManager().also {
				colorController = it
				it.addListener(this::onColorChange)
			}
		}
	}

	private fun destroy() {
		resources = null
		colorController?.let { ColorManager.recycleColorManager(it) }
	}

	private fun onColorChange(luminance: Byte, foregroundColor: Int, backgroundColor: Int) {
		val newStyle = getMapStyleRes(luminance)
		if (newStyle != activeMapStyleRes) {
			activeMapStyleRes = newStyle
			activeMapStyle = loadMapStyleRes(newStyle).also { onStyleChange(it) }
		}
	}

	private fun onStyleChange(style: MapStyleOptions) {
		GlobalScope.launch(Dispatchers.Main) {
			colorChangeListeners.forEach { it.setMapStyle(style) }
		}
	}

	fun addListener(context: Context, googleMap: GoogleMap) {
		synchronized(colorChangeListeners) {
			val isEmpty = colorChangeListeners.isEmpty()
			colorChangeListeners.add(googleMap)
			if (isEmpty) init(context)
		}
	}

	fun removeListener(googleMap: GoogleMap) {
		synchronized(colorChangeListeners) {
			colorChangeListeners.remove(googleMap)

			if (colorChangeListeners.isEmpty()) destroy()
		}
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