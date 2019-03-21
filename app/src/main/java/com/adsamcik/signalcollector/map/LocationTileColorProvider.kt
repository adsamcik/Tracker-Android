package com.adsamcik.signalcollector.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
		val tileCount = getTileCount(z)


		/*canvas.drawRect(0f, IMAGE_SIZE.toFloat(), IMAGE_SIZE.toFloat(), 0f, Paint().apply {
			color = Color.BLACK
		})

		canvas.drawRect(1f, IMAGE_SIZE.toFloat() - 1f, IMAGE_SIZE.toFloat() - 1f, 1f, Paint().apply {
			color = Color.BLACK.apply {
				alpha = 1
			}
		})*/



		allInside.forEach {
			val location = it.location
			val latitude = location.latitude
			val longitude = location.longitude

			val gX = Location.toGoogleLon(longitude, tileCount)
			val gY = Location.toGoogleLat(latitude, tileCount)

			val posX = ((gX - kotlin.math.floor(gX)) * (IMAGE_SIZE - 1)).toFloat()
			val posY = ((gY - kotlin.math.floor(gY)) * (IMAGE_SIZE - 1)).toFloat()

			val size = (location.horizontalAccuracy / Location.countPixelSize(latitude, z)).toFloat()
			val paint = Paint().apply {
				color = Color.argb(25, 255, 0, 0)
			}

			canvas.drawCircle(posX, posY, size, paint)
		}

		return getByteArray(bitmap)
	}

}