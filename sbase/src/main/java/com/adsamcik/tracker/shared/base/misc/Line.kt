package com.adsamcik.tracker.shared.base.misc

import kotlin.math.hypot

data class Line(val start: Double2, val end: Double2) {
	fun perpendicularDistance(point: Double2): Double {
		var dx = end.x - start.x
		var dy = end.y - start.y

		// Normalize
		val mag = hypot(dx, dy)
		if (mag > 0.0) {
			dx /= mag
		}
		dy /= mag
		val pvx = point.x - start.x
		val pvy = point.y - start.y

		// Get dot product (project pv onto normalized direction)
		val pvdot = dx * pvx + dy * pvy

		// Scale line direction vector and substract it from pv
		val ax = pvx - pvdot * dx
		val ay = pvy - pvdot * dy

		return hypot(ax, ay)
	}
}
