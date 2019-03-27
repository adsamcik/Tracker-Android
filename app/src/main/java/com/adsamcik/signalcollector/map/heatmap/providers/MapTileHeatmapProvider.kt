package com.adsamcik.signalcollector.map.heatmap.providers

import android.graphics.Bitmap
import com.adsamcik.signalcollector.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.map.LocationTileProvider
import com.adsamcik.signalcollector.map.heatmap.HeatmapTile
import com.adsamcik.signalcollector.utility.CoordinateBounds
import java.io.ByteArrayOutputStream

interface MapTileHeatmapProvider {
	val getAllInsideAndBetween: (from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>

	val getAllInside: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>

	fun createBitmap(): Bitmap {
		return Bitmap.createBitmap(LocationTileProvider.IMAGE_SIZE, LocationTileProvider.IMAGE_SIZE, Bitmap.Config.ARGB_8888)
	}

	fun getByteArray(bitmap: Bitmap): ByteArray {
		val stream = ByteArrayOutputStream()
		bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
		return stream.toByteArray()
	}


	fun getHeatmap(from: Long, to: Long, x: Int, y: Int, z: Int, area: CoordinateBounds, maxHeat: Float): HeatmapTile {
		return createHeatmap(x, y, z, area, maxHeat) { topLatitude, rightLongitude, bottomLatitude, leftLongitude ->
			getAllInsideAndBetween(from, to, topLatitude, rightLongitude, bottomLatitude, leftLongitude)
		}
	}

	fun getHeatmap(x: Int, y: Int, z: Int, area: CoordinateBounds, maxHeat: Float): HeatmapTile {
		return createHeatmap(x, y, z, area, maxHeat) { topLatitude, rightLongitude, bottomLatitude, leftLongitude ->
			getAllInside(topLatitude, rightLongitude, bottomLatitude, leftLongitude)
		}
	}

	private fun createHeatmap(x: Int, y: Int, z: Int, area: CoordinateBounds, maxHeat: Float, getLocations: (topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double) -> List<Database2DLocationWeightedMinimal>): HeatmapTile {
		val extendLatitude = area.height * (9.toDouble() / HeatmapTile.HEATMAP_SIZE_AS_DOUBLE)
		val extendLongitude = area.width * (9.toDouble() / HeatmapTile.HEATMAP_SIZE_AS_DOUBLE)

		val allInside = getLocations.invoke(area.top + extendLatitude, area.right + extendLongitude, area.bottom - extendLatitude, area.left - extendLongitude)
		//val allInside = dao.getAllInside(area.top, area.right, area.bottom, area.left)

		val heatmap = HeatmapTile(x, y, z, maxHeat, true)
		heatmap.addAll(allInside.sortedWith(compareBy({ it.longitude }, { it.latitude })))
		return heatmap
	}
}