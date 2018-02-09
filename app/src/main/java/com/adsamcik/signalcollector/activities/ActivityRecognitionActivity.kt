package com.adsamcik.signalcollector.activities

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.adapters.StringFilterableAdapter
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.utility.Constants.DAY_IN_MILLISECONDS
import com.adsamcik.signalcollector.utility.Parser
import com.adsamcik.signalcollector.utility.Preferences
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import java.lang.ref.WeakReference
import java.text.DateFormat.getDateTimeInstance
import java.util.*

class ActivityRecognitionActivity : DetailActivity() {

    private var startStopButton: Button? = null
    private var adapter: StringFilterableAdapter? = null
    private var listView: ListView? = null

    private var usingFilter = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        instance = WeakReference(this)

        val v = layoutInflater.inflate(R.layout.layout_activity_recognition, createContentParent(false))
        startStopButton = findViewById(R.id.dev_activity_debug_start_stop_button)

        setTitle(R.string.dev_activity_recognition_title)

        listView = v.findViewById(R.id.dev_activity_list_view)

        if (Preferences.getPref(this).getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false))
            startStopButton!!.text = getString(R.string.stop)
        else
            startStopButton!!.text = getString(R.string.start)

        startStopButton!!.setOnClickListener { _ ->
            val sp = Preferences.getPref(this)
            val setEnabled = !sp.getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false)
            val editor = sp.edit()
            editor.putBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, setEnabled)
            if (setEnabled) {
                startStopButton!!.text = getString(R.string.stop)
                editor.putLong(Preferences.PREF_DEV_ACTIVITY_TRACKING_STARTED, System.currentTimeMillis())
            } else
                startStopButton!!.text = getString(R.string.start)

            editor.apply()
        }

        val activity = this

        val items = Parser.parseTSVFromFile(activity, FILE) ?: ArrayList()
        adapter = StringFilterableAdapter(activity, R.layout.spinner_item, { item ->
            item.joinToString(delim)
        })

        adapter!!.addAll(items)

        findViewById<View>(R.id.dev_activity_recognition_filter).setOnClickListener { f ->
            if (usingFilter) {
                adapter!!.filter(null)
                (f as Button).setText(R.string.dev_activity_recognition_hide)
            } else {
                (f as Button).setText(R.string.dev_activity_recognition_show)
                adapter!!.filter(".*$delim.*$delim.*")
            }

            usingFilter = !usingFilter
        }

        findViewById<View>(R.id.dev_activity_recognition_clear).setOnClickListener { _ ->
            adapter!!.clear()
            DataStore.delete(this, FILE)
        }
    }

    companion object {
        private val FILE = "activityRecognitionDebug.tsv"

        private var instance: WeakReference<ActivityRecognitionActivity>? = null

        private val delim = " - "

        /**
         * Adds line to the activity debug if tracking is enabled
         * @param context Context
         * @param activity Name of the activity
         * @param action Action that this activity resulted in
         */
        fun addLineIfDebug(context: Context, activity: String, action: String?) {
            val preferences = Preferences.getPref(context)
            if (preferences.getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false)) {
                if ((System.currentTimeMillis() - preferences.getLong(Preferences.PREF_DEV_ACTIVITY_TRACKING_STARTED, 0)) / DAY_IN_MILLISECONDS > 0) {
                    preferences.edit().putBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false).apply()
                    if (instance?.get() != null) {
                        launch(UI) {
                            val inst = instance!!.get()!!
                            inst.startStopButton!!.text = inst.getString(R.string.start)
                        }
                    }
                }
                addLine(context, activity, action)
            }
        }

        /**
         * Adds line to the activity debug
         * @param context Context
         * @param activity Name of the activity
         * @param action Action that this activity resulted in
         */
        private fun addLine(context: Context, activity: String, action: String?) {
            val time = getDateTimeInstance().format(System.currentTimeMillis())
            val line = time + '\t' + activity + '\t' + if (action != null) action + '\n' else '\n'
            DataStore.saveString(context, FILE, line, true)
            if (instance != null && instance!!.get() != null) {
                val inst = instance!!.get()!!
                inst.runOnUiThread {
                    val adapter = inst.adapter!!
                    adapter.add(if (action == null) arrayOf(time, activity) else arrayOf(time, activity, action))
                    if (inst.listView!!.lastVisiblePosition == adapter.count - 2)
                        inst.listView!!.smoothScrollToPosition(adapter.count - 1)
                }
            }
        }
    }
}
