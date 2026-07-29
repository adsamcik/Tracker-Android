package com.adsamcik.tracker.stats.engine.reconstruction

internal fun wrappedLongitudeDeltaDegrees(toDegrees: Double, fromDegrees: Double): Double {
	var delta = (toDegrees - fromDegrees) % FULL_LONGITUDE_DEGREES
	if (delta > HALF_LONGITUDE_DEGREES) delta -= FULL_LONGITUDE_DEGREES
	if (delta < -HALF_LONGITUDE_DEGREES) delta += FULL_LONGITUDE_DEGREES
	return delta
}

internal fun normalizeLongitudeDegrees(longitudeDegrees: Double): Double {
	var normalized =
		(longitudeDegrees + HALF_LONGITUDE_DEGREES) % FULL_LONGITUDE_DEGREES
	if (normalized < 0.0) normalized += FULL_LONGITUDE_DEGREES
	return normalized - HALF_LONGITUDE_DEGREES
}

private const val HALF_LONGITUDE_DEGREES = 180.0
private const val FULL_LONGITUDE_DEGREES = 360.0
