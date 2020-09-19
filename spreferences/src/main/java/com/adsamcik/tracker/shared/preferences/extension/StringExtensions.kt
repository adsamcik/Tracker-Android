package com.adsamcik.tracker.shared.preferences.extension

import android.content.res.Resources
import com.adsamcik.tracker.shared.base.constant.LengthConstants
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.R

/**
 * Formats distance in metric length system.
 */
fun Resources.formatMetric(meters: Double, digits: Int): String {
	return if (meters >= LengthConstants.METERS_IN_KILOMETER) {
		val kilometers = meters / LengthConstants.METERS_IN_KILOMETER
		getString(R.string.kilometer_abbr, kilometers.formatReadable(digits))
	} else {
		getString(R.string.meter_abbr, meters.formatReadable(digits))
	}
}

/**
 * Formats distance in USCS.
 */
fun Resources.formatUscs(feet: Double, digits: Int): String {
	return if (feet >= LengthConstants.FEET_IN_MILE) {
		val miles = feet / LengthConstants.FEET_IN_MILE
		getString(R.string.mile_abbr, miles.formatReadable(digits))
	} else {
		getString(R.string.feet_abbr, feet.formatReadable(digits))
	}
}

/**
 * Formats distance in Ancient Roman length system.
 */
fun Resources.formatAncientRome(passus: Double, digits: Int): String {
	return if (passus >= LengthConstants.PASSUS_IN_MILE_PASSUS) {
		val millepassus = passus / LengthConstants.PASSUS_IN_MILE_PASSUS
		getString(R.string.millepassus, millepassus.formatReadable(digits))
	} else {
		getString(R.string.passus, passus.formatReadable(digits))
	}
}
