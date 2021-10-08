package com.adsamcik.tracker.map.heatmap.creators

/**
 * This essentially makes many variables in the formula meaningless,
 * but they are kept there for readability
 */
const val NORMALIZER: Float = 58.6273f

// , path loss can be represented by the path loss exponent, whose value is normally in the range of 2 to 4
// (where 2 is for propagation in free space, 4 is for relatively lossy environments and for the case of
// full specular reflection from the earth surface—the so-called flat earth model).
const val LOSS_EXPONENT: Float = 3f

const val APPROXIMATE_DISTANCE_IN_METERS: Float = 90f

const val VISUAL_SCALE: Float = 2f

const val MAX_WIFI_HEAT: Float = 50f
