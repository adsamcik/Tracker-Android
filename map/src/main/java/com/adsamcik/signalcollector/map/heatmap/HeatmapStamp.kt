package com.adsamcik.signalcollector.map.heatmap

import kotlin.math.sqrt

internal data class HeatmapStamp(var width: Int, var height: Int, val stampData: FloatArray) {
	companion object {

		fun generateNonlinear(radius: Int, distFunction: (Float) -> Float): HeatmapStamp {
			assert(radius > 0)

			val diameter = radius * 2 + 1
			val stampData = FloatArray(diameter * diameter)

			for (y in 0 until diameter) {
				val yOffset = y * diameter
				for (x in 0 until diameter) {
					val xMinusRadius = x - radius
					val yMinusRadius = y - radius
					val baseDistance = sqrt(
							((xMinusRadius * xMinusRadius) + (yMinusRadius * yMinusRadius)).toFloat()) / (radius + 1).toFloat()
					val distance = distFunction(baseDistance).coerceIn(0f, 1f)
					stampData[x + yOffset] = 1f - distance
				}
			}

			return HeatmapStamp(diameter, diameter, stampData)
		}

		fun calculateOptimalRadius(size: Int): Int = size / 16 + 1

		/* Having a default stamp ready makes it easier for simple usage of the library
        * since there is no need to create a new stamp.
        */
		private val STAMP_DEFAULT_4_DATA = floatArrayOf(
				0.0f, 0.0f, 0.1055728f, 0.1753789f, 0.2f, 0.1753789f, 0.1055728f, 0.0f, 0.0f,
				0.0f, 0.1514719f, 0.2788897f, 0.3675445f, 0.4f, 0.3675445f, 0.2788897f, 0.1514719f, 0.0f,
				0.1055728f, 0.2788897f, 0.4343146f, 0.5527864f, 0.6f, 0.5527864f, 0.4343146f, 0.2788897f, 0.1055728f,
				0.1753789f, 0.3675445f, 0.5527864f, 0.7171573f, 0.8f, 0.7171573f, 0.5527864f, 0.3675445f, 0.1753789f,
				0.2f, 0.4f, 0.6f, 0.8f, 1.0f, 0.8f, 0.6f, 0.4f, 0.2f,
				0.1753789f, 0.3675445f, 0.5527864f, 0.7171573f, 0.8f, 0.7171573f, 0.5527864f, 0.3675445f, 0.1753789f,
				0.1055728f, 0.2788897f, 0.4343146f, 0.5527864f, 0.6f, 0.5527864f, 0.4343146f, 0.2788897f, 0.1055728f,
				0.0f, 0.1514719f, 0.2788897f, 0.3675445f, 0.4f, 0.3675445f, 0.2788897f, 0.1514719f, 0.0f,
				0.0f, 0.0f, 0.1055728f, 0.1753789f, 0.2f, 0.1753789f, 0.1055728f, 0.0f, 0.0f)

		val default9x9: HeatmapStamp = HeatmapStamp(9, 9, STAMP_DEFAULT_4_DATA)
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as HeatmapStamp

		if (width != other.width) return false
		if (height != other.height) return false
		if (!stampData.contentEquals(other.stampData)) return false

		return true
	}

	override fun hashCode(): Int {
		var result = width
		result = 31 * result + height
		result = 31 * result + stampData.contentHashCode()
		return result
	}
}

