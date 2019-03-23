package com.adsamcik.signalcollector.map

import android.graphics.Bitmap
import android.graphics.Rect
import android.renderscript.Int2
import com.adsamcik.signalcollector.data.Location
import com.adsamcik.signalcollector.data.Location.Companion.countPixelSize
import com.adsamcik.signalcollector.data.Location.Companion.toGoogleLat
import com.adsamcik.signalcollector.data.Location.Companion.toGoogleLon
import com.adsamcik.signalcollector.map.LocationTileProvider.Companion.IMAGE_SIZE
import com.adsamcik.signalcollector.utility.CoordinateBounds
import java.io.ByteArrayOutputStream

interface MapTileColorProvider {
	fun createBitmap(): Bitmap {
		return Bitmap.createBitmap(LocationTileProvider.IMAGE_SIZE, LocationTileProvider.IMAGE_SIZE, Bitmap.Config.ARGB_8888)
	}

	fun getByteArray(bitmap: Bitmap): ByteArray {
		val stream = ByteArrayOutputStream()
		bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
		return stream.toByteArray()
	}

	fun generateBaseRectangle(loc: Location, tileCount: Int, blockSize: Int, zoom: Int): Rect {
		var x = toGoogleLon(loc.longitude, tileCount)
		var y = toGoogleLat(loc.latitude, tileCount)
		val tilePos = Int2(x.toInt(), y.toInt())
		x -= tilePos.x
		y -= tilePos.y
		val posX = (x * (IMAGE_SIZE - 1)).toInt()
		val posY = (y * (IMAGE_SIZE - 1)).toInt()
		val size = (blockSize / countPixelSize(loc.latitude, zoom)).toInt()
		val startX: Int
		val endX: Int
		val startY: Int
		val endY: Int
		if (loc.longitude < 0) {
			startX = posX - size
			endX = posX
		} else {
			startX = posX
			endX = posX + size
		}
		if (loc.latitude < 0) {
			startY = posY
			endY = posY + size
		} else {
			startY = posY - size
			endY = posY
		}
		return Rect(startX, startY, endX, endY)
	}


	fun getHeatmap(x: Int, y: Int, z:Int, area: CoordinateBounds): HeatmapTile

	fun getColor(x: Int, y: Int, z: Int, area: CoordinateBounds): ByteArray
}