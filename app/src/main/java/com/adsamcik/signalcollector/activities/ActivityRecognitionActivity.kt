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
import kotlinx.android.synthetic.main.layout_activity_recognition.*
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.android.Main
import kotlinx.coroutines.experimental.launch
import java.lang.ref.WeakReference
import java.text.DateFormat.getDateTimeInstance
import java.util.*

/**
 * Activity which helps with debugging of activity related issues.
 */
class ActivityRecognitionActivity : DetailActivity() {

    private lateinit var adapter: StringFilterableAdapter
    private lateinit var listView: ListView

    private var usingFilter = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layoutInflater.inflate(R.layout.layout_activity_recognition, createLinearContentParent(false))

        setTitle(R.string.settings_activity_debug_title)

        listView = dev_activity_list_view

        if (Preferences.getPref(this).getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false))
            start_stop_button.text = getString(R.string.stop)
        else
            start_stop_button.text = getString(R.string.start)

        start_stop_button.setOnClickListener { _ ->
            val sp = Preferences.getPref(this)
            val setEnabled = !sp.getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false)
            val editor = sp.edit()
            editor.putBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, setEnabled)
            if (setEnabled) {
                start_stop_button.text = getString(R.string.stop)
                editor.putLong(Preferences.PREF_DEV_ACTIVITY_TRACKING_STARTED, System.currentTimeMillis())
            } else
                start_stop_button.text = getString(R.string.start)

            editor.apply()
        }

        val items = Parser.parseTSVFromFile(this, FILE) ?: ArrayList()
        adapter = StringFilterableAdapter(this, R.layout.spinner_item) { item ->
            item.joinToString(delim)
        }
        adapter.addAll(items)

        findViewById<View>(R.id.dev_activity_recognition_filter).setOnClickListener { f ->
            if (usingFilter) {
                adapter.filter(null)
                (f as Button).setText(R.string.dev_activity_recognition_hide)
            } else {
                (f as Button).setText(R.string.dev_activity_recognition_show)
                adapter.filter(".*$delim.*$delim.*")
            }

            usingFilter = !usingFilter
        }

        findViewById<View>(R.id.dev_activity_recognition_clear).setOnClickListener { _ ->
            adapter.clear()
            DataStore.delete(this, FILE)
        }

        listView.adapter = adapter

        instance = WeakReference(this)
    }

    companion object {
        private const val FILE = "activityRecognitionDebug.tsv"

        private var instance: WeakReference<ActivityRecognitionActivity>? = null

        private const val delim = " - "

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
                        GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT, null, {
                            val inst = instance?.get()
                            if (inst != null)
                                inst.start_stop_button.text = inst.getString(R.string.start)
                        })
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
            val inst = instance?.get()
            inst?.runOnUiThread {
                val adapter = inst.adapter
                adapter.add(if (action == null) arrayOf(time, activity) else arrayOf(time, activity, action))
                if (inst.listView.lastVisiblePosition == adapter.count - 2)
                    inst.listView.smoothScrollToPosition(adapter.count - 1)
            }
        }
    }
}
