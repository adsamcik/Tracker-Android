package com.adsamcik.signalcollector.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.adsamcik.signalcollector.data.Location
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.DatabaseLocation
import com.adsamcik.signalcollector.map.LocationTileProvider.Companion.IMAGE_SIZE
import com.adsamcik.signalcollector.map.MapFunctions.getTileCount
import com.adsamcik.signalcollector.utility.CoordinateBounds

class LocationTileColorProvider(context: Context) : MapTileColorProvider {
	override fun getHeatmap(from: Long, to: Long, x: Int, y: Int, z: Int, area: CoordinateBounds, maxHeat: Float): HeatmapTile {
		return createHeatmap(x, y, z, area, maxHeat) { topLatitude, rightLongitude, bottomLatitude, leftLongitude ->
			dao.getAllInsideAndBetween(from, to, topLatitude, rightLongitude, bottomLatitude, leftLongitude)
		}
	}

	override fun getHeatmap(x: Int, y: Int, z: Int, area: CoordinateBounds, maxHeat: Float): HeatmapTile {
		return createHeatmap(x, y, z, area, maxHeat) { topLatitude, rightLongitude, bottomLatitude, leftLongitude ->
			dao.getAllInside(topLatitude, rightLongitude, bottomLatitude, leftLongitude)
		}
	}

	private fun createHeatmap(x: Int, y: Int, z: Int, area: CoordinateBounds, maxHeat: Float, getLocations: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<DatabaseLocation>): HeatmapTile {
		val extendLatitude = area.height * (9.toDouble() / HeatmapTile.HEATMAP_SIZE_AS_DOUBLE)
		val extendLongitude = area.width * (9.toDouble() / HeatmapTile.HEATMAP_SIZE_AS_DOUBLE)

		val allInside = getLocations.invoke(area.top + extendLatitude, area.right + extendLongitude, area.bottom - extendLatitude, area.left - extendLongitude)
		//val allInside = dao.getAllInside(area.top, area.right, area.bottom, area.left)

		val heatmap = HeatmapTile(x, y, z, maxHeat, true)
		heatmap.addAll(allInside.sortedWith(compareBy({ it.longitude }, { it.latitude })))
		return heatmap
	}

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