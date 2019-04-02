package com.adsamcik.signalcollector.statistics.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.adsamcik.cardlist.AppendBehaviour
import com.adsamcik.cardlist.CardItemDecoration
import com.adsamcik.cardlist.table.TableCard
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.app.adapter.ChangeTableAdapter
import com.adsamcik.signalcollector.app.color.ColorManager
import com.adsamcik.signalcollector.app.color.ColorSupervisor
import com.adsamcik.signalcollector.app.color.ColorView
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.DatabaseMaintenance
import com.adsamcik.signalcollector.database.data.DatabaseLocation
import com.adsamcik.signalcollector.misc.DistanceUnit
import com.adsamcik.signalcollector.misc.extension.*
import com.adsamcik.signalcollector.statistics.data.StatData
import com.adsamcik.signalcollector.statistics.data.TableStat
import com.adsamcik.signalcollector.tracker.data.LengthUnit
import kotlinx.android.synthetic.main.fragment_stats.view.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

class FragmentStats : Fragment(), IOnDemandView {
	private lateinit var fragmentView: View

	private var adapter: ChangeTableAdapter? = null

	private lateinit var swipeRefreshLayout: SwipeRefreshLayout

	private lateinit var colorManager: ColorManager

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val activity = activity!!
		colorManager = ColorSupervisor.createColorManager(activity)

		val fragmentView = inflater.inflate(R.layout.fragment_stats, container, false)


		adapter = ChangeTableAdapter(activity.packageManager.getActivityInfo(activity.componentName, 0).themeResource)

		//weeklyStats.addToViewGroup(view.findViewById(R.id.statsLayout), hasRecentUpload ? 1 : 0, false, 0);

		swipeRefreshLayout = fragmentView.findViewById(R.id.swiperefresh_stats)
		swipeRefreshLayout.setOnRefreshListener { this.updateStats() }
		swipeRefreshLayout.setColorSchemeResources(R.color.color_primary)
		swipeRefreshLayout.setProgressViewOffset(true, 0, 40.dpAsPx)

		val recyclerView = fragmentView!!.recycler_stats
		recyclerView.setRecyclerListener { }
		updateStats()
		recyclerView.adapter = adapter
		val decoration = CardItemDecoration()
		recyclerView.addItemDecoration(decoration)

		this.fragmentView = fragmentView

		colorManager.watchAdapterView(ColorView(recyclerView, 1, recursive = true, rootIsBackground = true))

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
			val locationDao = database.locationDao()

			val calendar = Calendar.getInstance()

			val now = calendar.timeInMillis

			calendar.add(Calendar.WEEK_OF_YEAR, -1)
			val weekAgo = calendar.timeInMillis

			DatabaseMaintenance().run(activity)

			val sumSessionData = sessionDao.getSummary()
			val summaryStats = TableStat(r.getString(R.string.stats_sum_title), showPosition = false, data = listOf(
					StatData(r.getString(R.string.stats_time), sumSessionData.duration.formatAsDuration(appContext)),
					StatData(r.getString(R.string.stats_collections), sumSessionData.collections.formatReadable()),
					StatData(r.getString(R.string.stats_distance_total), sumSessionData.distanceInM.formatAsDistance(1, DistanceUnit.Metric)),
					StatData(r.getString(R.string.stats_location_count), (locationDao.count().value
							?: 0).formatReadable()),
					StatData(r.getString(R.string.stats_wifi_count), wifiDao.count().formatReadable()),
					StatData(r.getString(R.string.stats_cell_count), cellDao.count().formatReadable()),
					StatData(r.getString(R.string.stats_session_count), sessionDao.count().formatReadable()),
					StatData(r.getString(R.string.stats_steps), sumSessionData.steps.formatReadable())
			))

			val lastMonthSummary = sessionDao.getSummary(weekAgo, now)

			val weeklyStats = TableStat(r.getString(R.string.stats_weekly_title), showPosition = false, data = listOf(
					StatData(r.getString(R.string.stats_time), lastMonthSummary.duration.formatAsDuration(appContext)),
					StatData(r.getString(R.string.stats_distance_total), lastMonthSummary.distanceInM.formatAsDistance(1, DistanceUnit.Metric)),
					StatData(r.getString(R.string.stats_collections), lastMonthSummary.collections.formatReadable()),
					StatData(r.getString(R.string.stats_steps), lastMonthSummary.steps.formatReadable())
			))

			val sumStatsArray = arrayOf(summaryStats, weeklyStats)

			handleResponse(sumStatsArray, AppendBehaviour.First)

			val dayAgoCalendar = Calendar.getInstance().apply {
				add(Calendar.DAY_OF_MONTH, -1)
			}
			sessionDao.getBetween(dayAgoCalendar.timeInMillis, now).map {
				arrayOf(TableStat("${it.start.formatAsShortDateTime()} - ${it.end.formatAsShortDateTime()}", false, listOf(
						StatData(r.getString(R.string.stats_distance_total), it.distanceInM.formatAsDistance(1, DistanceUnit.Metric)),
						StatData(r.getString(R.string.stats_collections), it.collections.formatReadable()),
						StatData(r.getString(R.string.stats_steps), it.steps.formatReadable())
				)))
			}.let {
				handleResponse(it, AppendBehaviour.Any)
			}

			val monthAgoCalendar = Calendar.getInstance().apply {
				add(Calendar.MONTH, -1)
			}

			sessionDao.getSummaryByDays(monthAgoCalendar.timeInMillis, dayAgoCalendar.timeInMillis).map {
				arrayOf(TableStat(it.time.formatAsDate(), false, listOf(
						StatData(r.getString(R.string.stats_distance_total), it.distanceInM.formatAsDistance(1, DistanceUnit.Metric)),
						StatData(r.getString(R.string.stats_collections), it.collections.formatReadable()),
						StatData(r.getString(R.string.stats_steps), it.steps.formatReadable())
				)))
			}.let {
				handleResponse(it, AppendBehaviour.Any)
			}

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

	private fun handleResponse(value: Array<TableStat>, appendBehavior: AppendBehaviour) {
		GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
			addStatsTable(value, appendBehavior)
			adapter!!.sort()
		}
	}

	private fun handleResponse(list: List<Array<TableStat>>, appendBehavior: AppendBehaviour) {
		GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
			list.forEach { addStatsTable(it, appendBehavior) }
			adapter!!.sort()
		}
	}

	private fun generateMockData() {
		addStatsTable(generateMockStatList(), AppendBehaviour.Any)
	}

	private fun generateMockStatList(): Array<TableStat> {
		val list = ArrayList<TableStat>()
		for (i in 1..10) {
			list.add(generateMockStat(i))
		}
		return list.toTypedArray()
	}

	private fun generateMockStat(index: Int) = TableStat("Mock $index", false, generateStatData(index))

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
	private fun addStatsTable(stats: Array<TableStat>, appendBehavior: AppendBehaviour) {
		for ((name, showPosition, data) in stats) {
			val table = TableCard(showPosition, appendBehavior)
			table.title = name
			data.indices
					.asSequence()
					.map { data[it] }
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
}

