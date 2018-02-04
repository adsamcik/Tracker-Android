package com.adsamcik.signalcollector.activities

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import com.adsamcik.signalcollector.adapters.StringFilterableAdapter
import com.adsamcik.signals.tracking.storage.DataStore
import com.adsamcik.signals.utilities.Parser
import com.adsamcik.signals.utilities.Preferences
import kotlinx.coroutines.experimental.async
import java.lang.ref.WeakReference
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
        //todo Rewrite code below this comment
        async {
            //I think this is here to load data asynchronously
            val items = Parser.parseTSVFromFile(activity, FILE) ?: ArrayList()
            adapter = StringFilterableAdapter(activity, R.layout.spinner_item, { item ->
                item.joinToString(delim)
            })
        }

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

    }
}
