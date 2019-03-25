package com.adsamcik.signalcollector.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.adapters.ChangeTableAdapter
import com.adsamcik.signalcollector.data.*
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.DatabaseLocation
import com.adsamcik.signalcollector.extensions.dpAsPx
import com.adsamcik.signalcollector.extensions.format
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.table.AppendBehaviors
import com.adsamcik.table.Table
import com.adsamcik.table.TableAdapter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

class FragmentStats : Fragment(), IOnDemandView {
	private lateinit var fragmentView: View

	private var adapter: TableAdapter? = null

	private lateinit var swipeRefreshLayout: SwipeRefreshLayout

	private lateinit var colorManager: ColorManager

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val activity = activity!!
		colorManager = ColorSupervisor.createColorManager(activity)

		val fragmentView = inflater.inflate(R.layout.fragment_stats, container, false)


		adapter = ChangeTableAdapter(activity, CARD_LIST_MARGIN, activity.packageManager.getActivityInfo(activity.componentName, 0).themeResource)

		//weeklyStats.addToViewGroup(view.findViewById(R.id.statsLayout), hasRecentUpload ? 1 : 0, false, 0);

		swipeRefreshLayout = fragmentView.findViewById(R.id.swiperefresh_stats)
		swipeRefreshLayout.setOnRefreshListener { this.updateStats() }
		swipeRefreshLayout.setColorSchemeResources(R.color.color_primary)
		swipeRefreshLayout.setProgressViewOffset(true, 0, 40.dpAsPx)

		val listView = fragmentView!!.findViewById<ListView>(R.id.listview_stats)
		listView.setRecyclerListener { }
		listView.adapter = adapter
		updateStats()

		this.fragmentView = fragmentView

		colorManager.watchAdapterView(ColorView(listView, 1, recursive = true, rootIsBackground = true))

		return fragmentView
	}

	override fun onDestroyView() {
		ColorSupervisor.recycleColorManager(colorManager)
		super.onDestroyView()
	}

	private fun updateStats() {
		val activity = activity
		val appContext = activity!!.applicationContext
		val isRefresh = swipeRefreshLayout.isRefreshing

		adapter!!.clear()

		val r = activity.resources

		GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
			swipeRefreshLayout.isRefreshing = true
		}


		/*if (useMock) {
			generateMockData()
		} else {*/
		//refreshingCount = 2
		//new stat loading
		GlobalScope.launch {
			val database = AppDatabase.getAppDatabase(activity)
			val sessionDao = database.sessionDao()
			val wifiDao = database.wifiDao()
			val cellDao = database.cellDao()

			val calendar = Calendar.getInstance()

			val now = calendar.timeInMillis

			calendar.add(Calendar.WEEK_OF_YEAR, -1)
			val weekAgo = calendar.timeInMillis

			val sumSession = TrackingSession(now, now, 0, 0f, 0)
			var totalMinutes = 0.0
			sessionDao.getBetween(weekAgo, now).forEach {
				sumSession.mergeWith(it)
				val time = it.end - it.start
				totalMinutes += time.toDouble() / Constants.MINUTE_IN_MILLISECONDS.toDouble()
			}

			val weeklyStats = arrayOf(Stat(r.getString(R.string.stats_weekly_title), "", showPosition = false, data = listOf(
					StatData(r.getString(R.string.stats_weekly_minutes), totalMinutes.toString()),
					StatData(r.getString(R.string.stats_weekly_collected_location), sumSession.collections.toString()),
					StatData(r.getString(R.string.stats_weekly_steps), sumSession.steps.toString()),
					StatData(r.getString(R.string.stats_weekly_distance_travelled), "${sumSession.distanceInM / 1000} km")
			)))

			handleResponse(weeklyStats, AppendBehaviors.FirstFirst)


			val monthAgoCalendar = Calendar.getInstance()
			monthAgoCalendar.add(Calendar.MONTH, -1)
			//todo show all session for the past 24 hours and merge the rest
			sessionDao.getBetween(monthAgoCalendar.timeInMillis, now).forEach {

				//val locations = locationDao.getAllBetween(it.start, it.end)

				//val distance = calculateDistance(locations)

				val stats = arrayOf(Stat("${Assist.formatShortDateTime(it.start)} - ${Assist.formatShortDateTime(it.end)}", "", false, listOf(
						StatData(r.getString(R.string.stats_weekly_distance_travelled), "${(it.distanceInM / 1000.0).format(2)} km"),
						StatData(r.getString(R.string.stats_weekly_collected_location), Assist.formatNumber(it.collections)),
						StatData(r.getString(R.string.stats_weekly_steps), it.steps.toString())
				)))
				handleResponse(stats, AppendBehaviors.FirstLast)
			}


			/*val locations = AppDatabase.getAppDatabase(activity).locationDao().getAllSince(System.currentTimeMillis() - Constants.DAY_IN_MILLISECONDS * 30)
			locations.groupBy { it.location.time / Constants.DAY_IN_MILLISECONDS }.forEach {
				val distance = calculateDistance(it.value).format(2)
				//todo add localization support
				val stats = arrayOf(Stat(Assist.formatDate(activity, it.value.first().location.time), "", false, listOf(
						StatData("distance", "$distance km"),
						StatData("locations", Assist.formatNumber(it.value.size))
				)))
				GlobalScope.launch(Dispatchers.Main) {
					addStatsTable(stats, AppendBehaviors.Any)
					adapter!!.sort()
				}
			}*/

			GlobalScope.launch(Dispatchers.Main) {
				swipeRefreshLayout.isRefreshing = false
			}
		}
		//}
	}

	private fun calculateDistance(locations: List<DatabaseLocation>): Double {
		var totalDistance = 0.0
		val count = locations.size
		var last = locations[0]
		for (i in 1 until count) {
			val current = locations[i]

			if (current.location.time - last.location.time < Constants.MINUTE_IN_MILLISECONDS) {
				totalDistance += last.location.distanceFlat(current.location, LengthUnit.Kilometer)
			}

			last = current
		}

		return totalDistance
	}

	private fun handleResponse(value: Array<Stat>, @AppendBehaviors.AppendBehavior appendBehavior: Int) {
		GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
			addStatsTable(value, appendBehavior)
			adapter!!.sort()
		}
	}

	private fun generateMockData() {
		addStatsTable(generateMockStatList(), AppendBehaviors.Any)
	}

	private fun generateMockStatList(): Array<Stat> {
		val list = ArrayList<Stat>()
		for (i in 1..10) {
			list.add(generateMockStat(i))
		}
		return list.toTypedArray()
	}

	private fun generateMockStat(index: Int) = Stat("Mock $index", "donut", false, generateStatData(index))

	private fun generateStatData(index: Int): List<StatData> {
		val list = ArrayList<StatData>()
		for (i in 1..index) {
			list.add(StatData("Title $i", i.toString()))
		}
		return list
	}

	/**
	 * Generates tables from list of stats
	 *
	 * @param stats stats
	 */
	private fun addStatsTable(stats: Array<Stat>, @AppendBehaviors.AppendBehavior appendBehavior: Int) {
		for (s in stats) {
			val table = Table(s.data.size, s.showPosition, CARD_LIST_MARGIN, appendBehavior)
			table.title = s.name
			s.data.indices
					.asSequence()
					.map { s.data[it] }
					.forEach { table.addData(it.id, it.value) }
			adapter!!.add(table)
		}
	}


	override fun onEnter(activity: FragmentActivity) {

	}

	override fun onLeave(activity: FragmentActivity) {
	}


	override fun onPermissionResponse(requestCode: Int, success: Boolean) {

	}

	companion object {
		private const val CARD_LIST_MARGIN = 16
	}
}

