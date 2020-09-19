package com.adsamcik.tracker.shared.base.misc

object Filter {

	/**
	 * Simplifies list of points.
	 * Based on https://en.wikipedia.org/wiki/Ramer%E2%80%93Douglas%E2%80%93Peucker_algorithm
	 */
	fun rdpSimplify(pointList: List<Double2>, threshold: Double): List<Double2> {
		if (pointList.size <= 2) return pointList.toList()

		// Find the point with the maximum distance from line between start and end
		var dmax = 0.0
		var index = 0

		val firstEndLine = Line(pointList[0], pointList[pointList.lastIndex])
		for (i in 1..pointList.lastIndex) {
			val d = firstEndLine.perpendicularDistance(pointList[i])
			if (d > dmax) {
				index = i
				dmax = d
			}
		}

		// If max distance is greater than epsilon, recursively simplify
		return if (dmax > threshold) {
			val firstLine = pointList.take(index + 1)
			val lastLine = pointList.drop(index)
			val result1 = rdpSimplify(firstLine, threshold)
			val result2 = rdpSimplify(lastLine, threshold)

			result1.take(result1.lastIndex) + result2
		} else {
			listOf(pointList.first(), pointList.last())
		}
	}
}

fun List<Double2>.simplifyRDP(threshold: Double): List<Double2> =
		Filter.rdpSimplify(this, threshold)

fun Sequence<Double2>.simplifyRDP(threshold: Double): List<Double2> =
		Filter.rdpSimplify(this.toList(), threshold)
