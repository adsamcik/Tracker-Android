package com.adsamcik.signals.stats

import android.content.res.Resources
import com.adsamcik.signalcollector.R
import com.vimeo.stag.UseStag

@UseStag
class UploadStats(var time: Long, var wifi: Int, var newWifi: Int, var cell: Int, var newCell: Int, var collections: Int, var newLocations: Int, var noiseCollections: Int, var uploadSize: Long, var newNoiseLocations: Int) {

    fun generateNotificationText(resources: Resources): String {
        val stringBuilder = StringBuilder()
        val newLocations = this.newLocations + this.newNoiseLocations
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
