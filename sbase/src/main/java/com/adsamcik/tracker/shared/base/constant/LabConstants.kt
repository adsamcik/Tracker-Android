package com.adsamcik.tracker.shared.base.constant

/**
 * LAB (color system) constants
 */
object LabConstants {
	/**
	 * Corresponds roughly to RGB brighter/darker
	 */
	const val Kn: Int = 18


	// D65 standard referent
	/**
	 * D65 standard X tristimulus value.
	 */
	const val Xn: Double = 0.950470

	/**
	 * D65 standard Y tristimulus value.
	 */
	const val Yn: Double = 1.0

	/**
	 * D65 standard Z tristimulus value.
	 */
	const val Zn: Double = 1.088830

	const val t0: Double = 0.137931034  // 4 / 29
	const val t1: Double = 0.206896552  // 6 / 29
	const val t2: Double = 0.12841855   // 3 * t1 * t1
	const val t3: Double = 0.008856452   // t1 * t1 * t1
}
