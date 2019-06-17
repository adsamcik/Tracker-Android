package com.adsamcik.signalcollector.debug.activity

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.Constants.DAY_IN_MILLISECONDS
import com.adsamcik.signalcollector.common.activity.DetailActivity
import com.adsamcik.signalcollector.common.adapter.StringFilterableAdapter
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.common.database.DebugDatabase
import com.adsamcik.signalcollector.common.database.data.DatabaseDebugActivity
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
	private lateinit var recyclerView: RecyclerView

	private var usingFilter = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		layoutInflater.inflate(R.layout.layout_activity_recognition, createLinearContentParent(false))

		setTitle(R.string.settings_debug_activity_title)

		recyclerView = dev_activity_list_view


		val keyDevActivityTracking = R.string.settings_activity_debug_tracking_key
		val defaultDevActivityTracking = R.string.settings_activity_debug_tracking_key

		if (Preferences.getPref(this).getBooleanRes(keyDevActivityTracking, defaultDevActivityTracking))
			start_stop_button.text = getString(R.string.stop)
		else
			start_stop_button.text = getString(R.string.start)

		start_stop_button.setOnClickListener {
			val sp = Preferences.getPref(this)
			val setEnabled = !sp.getBooleanRes(keyDevActivityTracking, defaultDevActivityTracking)
			sp.edit {
				setBoolean(keyDevActivityTracking, setEnabled)
				if (setEnabled) {
					start_stop_button.text = getString(R.string.stop)
					setLong(keyDevActivityTracking, System.currentTimeMillis())
				} else
					start_stop_button.text = getString(R.string.start)
			}
		}

		val appContext = applicationContext
		val items = DebugDatabase.getAppDatabase(appContext).activityDebugDao().getAll()
		adapter = StringFilterableAdapter(this, com.adsamcik.signalcollector.common.R.layout.recycler_item) { item ->
			item.joinToString(delim)
		}
		adapter.addAll(items.map {
			val action = it.action ?: ""
			val activityName = it.activity.getGroupedActivityName(appContext)
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

		recyclerView.adapter = adapter

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
			val keyDevActivityTracking = R.string.settings_activity_debug_tracking_key
			val defaultDevActivityTracking = R.string.settings_activity_debug_tracking_key

			if (preferences.getBooleanRes(keyDevActivityTracking, defaultDevActivityTracking)) {

				val keyStartTime = context.resources.getString(R.string.settings_activity_debug_tracking_start_time_key)

				if ((System.currentTimeMillis() - preferences.getLong(keyStartTime)) / DAY_IN_MILLISECONDS > 0) {
					preferences.edit {
						setBoolean(keyDevActivityTracking, false)
					}
				}

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
				val activityName = activity.getGroupedActivityName(context)
				adapter.add(if (action == null) arrayOf(timeString, activityName) else arrayOf(timeString, activityName, action))
				//too complicated on recycler view
				/*if (inst.recyclerView. == adapter.count - 2)
					inst.recyclerView.smoothScrollToPosition(adapter.count - 1)*/
			}
		}
	}
}
