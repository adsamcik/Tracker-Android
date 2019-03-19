package com.adsamcik.signalcollector.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.adsamcik.signalcollector.data.Location
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.map.LocationTileProvider.Companion.IMAGE_SIZE
import com.adsamcik.signalcollector.utility.CoordinateBounds

class LocationTileColorProvider(context: Context) : MapTileColorProvider {
	private val dao = AppDatabase.getAppDatabase(context).locationDao()

	override fun getColor(x: Int, y: Int, z: Int, area: CoordinateBounds): ByteArray {
		val allInside = dao.getAllInside(area.top, area.right, area.bottom, area.left)
		val bitmap = createBitmap()

		val canvas = Canvas(bitmap)

		val blockSize = 30

		val grouped = allInside.groupBy { it.location.roundTo(blockSize) }
		val tileCount = getTileCount(z)

		grouped.forEach {
			val latitude = it.key.latitude
			val longitude = it.key.longitude
			val pixelSize = Location.countPixelSize(latitude, z)

			var x = Location.toGoogleLat(latitude, tileCount)
			var y = Location.toGoogleLon(longitude, tileCount)

			x = (x - kotlin.math.floor(x)) * IMAGE_SIZE
			y = (y - kotlin.math.floor(y)) * IMAGE_SIZE

			val bounds = Rect(y.toInt(), (x + pixelSize * blockSize).toInt(), (y + pixelSize * blockSize).toInt(), x.toInt())

			val paint = Paint().apply {
				color = Color.RED
			}
			canvas.drawRect(bounds, paint)
		}

		return getByteArray(bitmap)
	}

}