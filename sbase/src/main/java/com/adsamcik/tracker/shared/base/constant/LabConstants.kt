package com.adsamcik.tracker.shared.base.constant

/**
 * LAB (color system) constants
 */
object LabConstants {
	// Corresponds roughly to RGB brighter/darker
	const val Kn = 18

	// D65 standard referent
	const val Xn = 0.950470
	const val Yn = 1.0
	const val Zn = 1.088830

	const val t0 = 0.137931034  // 4 / 29
	const val t1 = 0.206896552  // 6 / 29
	const val t2 = 0.12841855   // 3 * t1 * t1
	const val t3 = 0.008856452   // t1 * t1 * t1
}
