package com.adsamcik.signalcollector.data

import android.content.res.Resources
import com.adsamcik.signalcollector.R

/**
 * Data class containing UploadStats information
 */
data class UploadStats(var time: Long, var wifi: Int, var newWifi: Int, var cell: Int, var newCell: Int, var collections: Int, var newLocations: Int, var uploadSize: Long) {

    fun generateNotificationText(resources: Resources): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append(resources.getString(R.string.notification_found)).append(' ')
        stringBuilder.append(resources.getQuantityString(R.plurals.new_locations, newLocations, newLocations)).append(", ")

        if (newWifi > 0)
            stringBuilder.append(resources.getString(R.string.new_wifi_count, newWifi)).append(", ")
        else if(wifi > 0)
            stringBuilder.append(resources.getString(R.string.wifi_count, wifi)).append(", ")

        if (newCell > 0)
            stringBuilder.append(resources.getString(R.string.new_cell_count, newCell)).append(", ")
        else if(cell > 0)
            stringBuilder.append(resources.getQuantityString(R.plurals.cell_count, cell, cell)).append(", ")

        stringBuilder.setLength(stringBuilder.length - 2)
        return stringBuilder.toString()

    }
}
