package com.adsamcik.signalcollector.data

import android.content.res.Resources

import com.adsamcik.signalcollector.R
import com.vimeo.stag.UseStag

/**
 * Data class containing UploadStats information
 */
@UseStag
data class UploadStats(var time: Long, var wifi: Int, var newWifi: Int, var cell: Int, var newCell: Int, var collections: Int, var newLocations: Int, var uploadSize: Long) {

    fun generateNotificationText(resources: Resources): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append(resources.getString(R.string.notification_found)).append(' ')
        stringBuilder.append(resources.getQuantityString(R.plurals.new_locations, newLocations, newLocations)).append(", ")

        if (newWifi > 0)
            stringBuilder.append(resources.getString(R.string.new_wifi, newWifi)).append(", ")
        if (newCell > 0)
            stringBuilder.append(resources.getString(R.string.new_cell, newCell)).append(", ")

        stringBuilder.setLength(stringBuilder.length - 2)
        return stringBuilder.toString()

    }
}
