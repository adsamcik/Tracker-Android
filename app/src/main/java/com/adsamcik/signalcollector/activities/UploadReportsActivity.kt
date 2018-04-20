package com.adsamcik.signalcollector.activities

import android.content.Context
import android.os.Bundle
import android.widget.ListView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.adapters.ChangeTableAdapter
import com.adsamcik.signalcollector.data.UploadStats
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants.MINUTE_IN_MILLISECONDS
import com.adsamcik.table.AppendBehavior
import com.adsamcik.table.Table
import com.google.gson.Gson
import java.util.*

/**
 * Activity that shows all the recent upload reports user received from the server.
 */
class UploadReportsActivity : DetailActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.recent_uploads)

        val recent = if (useMock) {
            val arr = ArrayList<UploadStats>()
            for (i in 0 until 10)
                arr.add(mockItem())
            arr.toTypedArray()
        } else
            Gson().fromJson(DataStore.loadAppendableJsonArray(this, DataStore.RECENT_UPLOADS_FILE), Array<UploadStats>::class.java)

        Arrays.sort(recent) { uploadStats, t1 -> ((t1.time - uploadStats.time) / MINUTE_IN_MILLISECONDS).toInt() }
        if (recent.isNotEmpty()) {
            val parent = createLinearContentParent(false)
            val listView = ListView(this)

            listView.divider = null
            listView.dividerHeight = 0
            listView.setSelector(android.R.color.transparent)
            parent.addView(listView)

            val adapter = ChangeTableAdapter(this, 16, packageManager.getActivityInfo(componentName, 0).themeResource)
            listView.adapter = adapter

            recent.forEach { stats ->
                adapter.add(generateTableForUploadStat(stats, this, null, AppendBehavior.Any))
            }

            colorManager!!.watchAdapterView(ColorView(listView, 0, true, true))
        }
    }

    companion object {

        /**
         * Returns mocked UploadStat
         */
        fun mockItem() = UploadStats(System.currentTimeMillis(), 45464, 101, 156, 11, 65478, 65546, 5465646541L)

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

            t.addData(resources.getString(R.string.size_title), Assist.humanReadableByteCount(uploadStat.uploadSize, true))
            t.addData(resources.getString(R.string.collections_title), uploadStat.collections.toString())
            t.addData(resources.getString(R.string.locations_new_title), uploadStat.newLocations.toString())
            t.addData(resources.getString(R.string.wifi), uploadStat.wifi.toString())
            t.addData(resources.getString(R.string.wifi_new_title), uploadStat.newWifi.toString())
            t.addData(resources.getString(R.string.cell_title), uploadStat.cell.toString())
            t.addData(resources.getString(R.string.cell_new), uploadStat.newCell.toString())
            return t
        }
    }
}
