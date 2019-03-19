package com.adsamcik.signalcollector.extensions

/// <summary>
/// Converts degrees to radians
/// </summary>
/// <param name="deg">Degree to convert</param>
/// <returns>Degree in radians</returns>
public fun Double.deg2rad() = (this * kotlin.math.PI / 180.0)

/// <summary>
/// Converts radians to degrees
/// </summary>
/// <param name="rad">Radians to convert</param>
/// <returns>Radians as degrees</returns>
public fun Double.rad2deg() = (this / kotlin.math.PI * 180.0)

fun Double.round(decimals: Int): Double {
	var multiplier = 1.0
	repeat(decimals) { multiplier *= 10 }
	return kotlin.math.round(this * multiplier) / multiplier
}