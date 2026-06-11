package com.adsamcik.tracker.shared.preferences.local

import android.content.Context
import com.adsamcik.tracker.shared.base.constant.LengthConstants
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.extension.formatAncientRome
import com.adsamcik.tracker.shared.preferences.extension.formatFlying
import com.adsamcik.tracker.shared.preferences.extension.formatMetric
import com.adsamcik.tracker.shared.preferences.extension.formatSailing
import com.adsamcik.tracker.shared.preferences.extension.formatUscs
import com.adsamcik.tracker.shared.preferences.type.LengthSystem

/**
 * Minimal local copy of distance formatting access used by preference sliders,
 * to avoid depending on :sutils and keep modules acyclic.
 */
object FormatHelpers {
    fun formatDistance(context: Context, meters: Int, digits: Int): String {
        val res = context.resources
        val system = TrackerSettingsQuick.lengthSystem(context)
        return when (system) {
            LengthSystem.Metric -> res.formatMetric(meters.toDouble(), digits)
            LengthSystem.Imperial -> {
                val feet = meters * LengthConstants.FEET_IN_METERS
                res.formatUscs(feet, digits)
            }
            LengthSystem.AncientRoman -> {
                val passus = meters / LengthConstants.METERS_IN_PASSUS
                res.formatAncientRome(passus.toDouble(), digits)
            }
            LengthSystem.Sailing -> {
                val fathoms = meters / LengthConstants.METERS_IN_FATHOM
                res.formatSailing(fathoms.toDouble(), digits)
            }
            LengthSystem.Flying -> {
                val feet = meters * LengthConstants.FEET_IN_METERS
                res.formatFlying(feet, digits)
            }
        }
    }
}
