package com.adsamcik.signalcollector.activities

import android.content.Context
import android.os.Bundle
import android.widget.ListView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.data.UploadStats
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants.MINUTE_IN_MILLISECONDS
import com.adsamcik.signalcollector.utility.Preferences
import com.adsamcik.table.AppendBehavior
import com.adsamcik.table.Table
import com.adsamcik.table.TableAdapter
import com.google.gson.Gson
import java.util.*

class UploadReportsActivity : DetailActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.recent_uploads)

        val recent = Gson().fromJson(DataStore.loadAppendableJsonArray(this, DataStore.RECENT_UPLOADS_FILE), Array<UploadStats>::class.java)
        Arrays.sort(recent) { uploadStats, t1 -> ((t1.time - uploadStats.time) / MINUTE_IN_MILLISECONDS).toInt() }
        if (recent.isNotEmpty()) {
            val parent = createContentParent(false)
            val listView = ListView(this)

            listView.divider = null
            listView.dividerHeight = 0
            listView.setSelector(android.R.color.transparent)
            parent.addView(listView)

            val adapter = TableAdapter(this, 16, Preferences.getTheme(this))
            listView.adapter = adapter

            recent.forEach { stats ->
                adapter.add(generateTableForUploadStat(stats, this, null, AppendBehavior.Any))
            }

            colorManager!!.watchElement(ColorView(parent, 0, true, true))
        }
    }

    companion object {

        /**
         * Function for generating table for upload stats
         *
         * @param uploadStat upload stat
         * @param context    context
         * @param title      title, if null is replaced with upload time
         * @return table
         */
        fun generateTableForUploadStat(uploadStat: UploadStats, context: Context, title: String?, @AppendBehavior appendBehavior: Int): Table {
            val resources = context.resources
            val t = Table(9, false, 16, appendBehavior)


            if (title == null) {
                val dateFormat = android.text.format.DateFormat.getDateFormat(context)
                val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
                val dateTime = Date(uploadStat.time)
                t.title = dateFormat.format(dateTime) + " " + timeFormat.format(dateTime)
            } else
                t.title = title

            t.addData(resources.getString(R.string.recent_upload_size), Assist.humanReadableByteCount(uploadStat.uploadSize, true))
            t.addData(resources.getString(R.string.recent_upload_collections), uploadStat.collections.toString())
            t.addData(resources.getString(R.string.recent_upload_locations_new), uploadStat.newLocations.toString())
            t.addData(resources.getString(R.string.recent_upload_wifi), uploadStat.wifi.toString())
            t.addData(resources.getString(R.string.recent_upload_wifi_new), uploadStat.newWifi.toString())
            t.addData(resources.getString(R.string.recent_upload_cell), uploadStat.cell.toString())
            t.addData(resources.getString(R.string.recent_upload_cell_new), uploadStat.newCell.toString())
            t.addData(resources.getString(R.string.recent_upload_noise), uploadStat.noiseCollections.toString())
            t.addData(resources.getString(R.string.recent_upload_noise_new), uploadStat.newNoiseLocations.toString())
            return t
        }
    }
}
