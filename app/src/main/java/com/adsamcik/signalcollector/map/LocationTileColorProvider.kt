package com.adsamcik.signalcollector.map

import android.content.Context
import android.graphics.Canvas
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

		val blockSize = 30

		val tileCount = getTileCount(z)

		allInside.forEach {
			val latitude = it.location.latitude
			val longitude = it.location.longitude

			val gX = Location.toGoogleLat(latitude, tileCount)
			val gY = Location.toGoogleLon(longitude, tileCount)

			val posX = ((gX - kotlin.math.floor(gX)) * (IMAGE_SIZE - 1)).toFloat()
			val posY = ((gY - kotlin.math.floor(gY)) * (IMAGE_SIZE - 1)).toFloat()

			val size = (blockSize / Location.countPixelSize(latitude, z)).toFloat()


			val paint = Paint().apply {
				color = 0x0aFF0000
			}

			canvas.drawCircle(posX, posY, size, paint)
		}

		return getByteArray(bitmap)
	}

}