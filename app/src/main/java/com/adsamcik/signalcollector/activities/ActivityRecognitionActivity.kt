package com.adsamcik.signalcollector.activities

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.adapters.StringFilterableAdapter
import com.adsamcik.signalcollector.database.DebugDatabase
import com.adsamcik.signalcollector.database.data.DatabaseDebugActivity
import com.adsamcik.signalcollector.utility.ActivityInfo
import com.adsamcik.signalcollector.utility.Constants.DAY_IN_MILLISECONDS
import com.adsamcik.signalcollector.utility.Preferences
import kotlinx.android.synthetic.main.layout_activity_recognition.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.text.DateFormat.getDateTimeInstance

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

		start_stop_button.setOnClickListener {
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

		val appContext = applicationContext;
		val items = DebugDatabase.getAppDatabase(appContext).activityDebugDao().getAll()
		adapter = StringFilterableAdapter(this, R.layout.spinner_item) { item ->
			item.joinToString(delim)
		}
		adapter.addAll(items.map {
			val action = it.action ?: ""
			val activityName = it.activity.getResolvedActivityName(appContext)
			arrayOf(it.time.toString(), activityName, action)
		})

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

		findViewById<View>(R.id.dev_activity_recognition_clear).setOnClickListener {
			adapter.clear()
			DebugDatabase.getAppDatabase(applicationContext).activityDebugDao().deleteAll()
		}

		listView.adapter = adapter

		instance = WeakReference(this)
	}

	companion object {
		private var instance: WeakReference<ActivityRecognitionActivity>? = null

		private const val delim = " - "

		/**
		 * Adds line to the activity debug if tracking is enabled
		 * @param context Context
		 * @param activity Name of the activity
		 * @param action Action that this activity resulted in
		 */
		fun addLineIfDebug(context: Context, time: Long, activity: ActivityInfo, action: String?) {
			val preferences = Preferences.getPref(context)
			if (preferences.getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false)) {
				if ((System.currentTimeMillis() - preferences.getLong(Preferences.PREF_DEV_ACTIVITY_TRACKING_STARTED, 0)) / DAY_IN_MILLISECONDS > 0) {
					preferences.edit().putBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false).apply()
					if (instance?.get() != null) {
						GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
							val inst = instance?.get()
							if (inst != null)
								inst.start_stop_button.text = inst.getString(R.string.start)
						}
					}
				}
				addLine(context, time, activity, action)
			}
		}

		/**
		 * Adds line to the activity debug
		 * @param context Context
		 * @param activity Name of the activity
		 * @param action Action that this activity resulted in
		 */
		private fun addLine(context: Context, time: Long, activity: ActivityInfo, action: String?) {
			val timeString = getDateTimeInstance().format(time)
			val dao = DebugDatabase.getAppDatabase(context).activityDebugDao()
			dao.insert(DatabaseDebugActivity(time, activity, action))
			val inst = instance?.get()
			inst?.runOnUiThread {
				val adapter = inst.adapter
				val activityName = activity.getResolvedActivityName(context)
				adapter.add(if (action == null) arrayOf(timeString, activityName) else arrayOf(timeString, activityName, action))
				if (inst.listView.lastVisiblePosition == adapter.count - 2)
					inst.listView.smoothScrollToPosition(adapter.count - 1)
			}
		}
	}
}
