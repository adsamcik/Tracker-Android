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

/**
 * Formats distance in Sailing length system.
 */
fun Resources.formatSailing(fathoms: Double, digits: Int): String {
	return when {
		fathoms >= LengthConstants.FATHOMS_IN_CABLE -> {
			val cables = fathoms / LengthConstants.FATHOMS_IN_CABLE
			getString(R.string.cable_abbr, cables.formatReadable(digits))
		}
		fathoms >= 1.0 -> {
			getString(R.string.fathom_abbr, fathoms.formatReadable(digits))
		}
		else -> {
			// For very small distances, convert back to nautical miles
			val meters = fathoms * LengthConstants.METERS_IN_FATHOM
			val nauticalMiles = meters / LengthConstants.METERS_IN_NAUTICAL_MILE
			getString(R.string.nautical_mile_abbr, nauticalMiles.formatReadable(digits))
		}
	}
}

/**
 * Formats speed in knots (nautical miles per hour), the standard sailing speed unit.
 * Unlike [formatSailing], this always represents an hourly rate — there is no
 * "knots per minute"/"knots per second" equivalent in real-world usage.
 */
fun Resources.formatKnots(knots: Double, digits: Int): String {
	return getString(R.string.knot_abbr, knots.formatReadable(digits))
}

/**
 * Formats distance in Flying length system.
 */
fun Resources.formatFlying(feet: Double, digits: Int): String {
	return when {
		feet >= LengthConstants.FEET_IN_FLIGHT_LEVEL * 10 -> {
			val flightLevel = feet / LengthConstants.FEET_IN_FLIGHT_LEVEL
			getString(R.string.flight_level_abbr, flightLevel.formatReadable(0)) // Flight levels don't use decimals
		}
		feet >= 1.0 -> {
			getString(R.string.feet_abbr, feet.formatReadable(digits))
		}
		else -> {
			// For very small distances, convert back to meters
			val meters = feet * LengthConstants.METERS_IN_FOOT
			getString(R.string.meter_abbr, meters.formatReadable(digits))
		}
	}
}
