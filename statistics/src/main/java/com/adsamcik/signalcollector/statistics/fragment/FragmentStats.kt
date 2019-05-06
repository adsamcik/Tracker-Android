package com.adsamcik.signalcollector.statistics.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.recycler.AppendBehavior
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.recycler.card.CardItemDecoration
import com.adsamcik.recycler.card.table.TableCard
import com.adsamcik.signalcollector.common.color.ColorManager
import com.adsamcik.signalcollector.common.color.ColorSupervisor
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.misc.extension.*
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.DatabaseMaintenance
import com.adsamcik.signalcollector.statistics.ChangeTableAdapter
import com.adsamcik.signalcollector.statistics.R
import com.adsamcik.signalcollector.statistics.data.StatData
import com.adsamcik.signalcollector.statistics.data.TableStat
import com.adsamcik.signalcollector.statistics.detail.activity.StatsDetailActivity
import com.adsamcik.signalcollector.tracker.data.session.TrackerSession
import kotlinx.android.synthetic.main.fragment_stats.view.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

class FragmentStats : Fragment(), IOnDemandView {
	private lateinit var fragmentView: View

	private lateinit var adapter: ChangeTableAdapter

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
		//swipeRefreshLayout.setColorSchemeResources(R.color.color_primary)
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
		val activity = activity!!
		val appContext = activity.applicationContext
		val isRefresh = swipeRefreshLayout.isRefreshing

		adapter.clear()

		val resources = activity.resources

		GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
			swipeRefreshLayout.isRefreshing = true
		}


		/*if (useMock) {
			generateMockData()
		} else {*/
		//refreshingCount = 2
		//newLocations stat loading
		GlobalScope.launch {
			val database = AppDatabase.getDatabase(activity)
			val sessionDao = database.sessionDao()
			val wifiDao = database.wifiDao()
			val cellDao = database.cellDao()
			val locationDao = database.locationDao()

			val calendar = Calendar.getInstance()

			val now = calendar.timeInMillis

			calendar.add(Calendar.WEEK_OF_YEAR, -1)
			val weekAgo = calendar.timeInMillis

			DatabaseMaintenance().run(activity)

			val lengthSystem = Preferences.getLengthSystem(activity)

			val sumSessionData = sessionDao.getSummary()
			val summaryStats = TableStat(resources.getString(R.string.stats_sum_title), showPosition = false, data = listOf(
					StatData(resources.getString(R.string.stats_time), sumSessionData.duration.formatAsDuration(appContext)),
					StatData(resources.getString(R.string.stats_collections), sumSessionData.collections.formatReadable()),
					StatData(resources.getString(R.string.stats_distance_total), resources.formatDistance(sumSessionData.distanceInM, 1, lengthSystem)),
					StatData(resources.getString(R.string.stats_location_count), locationDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_wifi_count), wifiDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_cell_count), cellDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_session_count), sessionDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_steps), sumSessionData.steps.formatReadable())
			))

			val lastMonthSummary = sessionDao.getSummary(weekAgo, now)

			val weeklyStats = TableStat(resources.getString(R.string.stats_weekly_title), showPosition = false, data = listOf(
					StatData(resources.getString(R.string.stats_time), lastMonthSummary.duration.formatAsDuration(appContext)),
					StatData(resources.getString(R.string.stats_distance_total), resources.formatDistance(lastMonthSummary.distanceInM, 1, lengthSystem)),
					StatData(resources.getString(R.string.stats_collections), lastMonthSummary.collections.formatReadable()),
					StatData(resources.getString(R.string.stats_steps), lastMonthSummary.steps.formatReadable())
			))


			handleResponse(summaryStats, AppendPriority(AppendBehavior.Start))
			handleResponse(weeklyStats, AppendPriority(AppendBehavior.Start))

			val startOfTheDay = Calendar.getInstance().apply { roundToDate() }

			sessionDao.getBetween(startOfTheDay.timeInMillis, now).forEach { addSessionData(it) }

			val monthAgoCalendar = Calendar.getInstance().apply {
				roundToDate()
				add(Calendar.MONTH, -1)
			}

			sessionDao.getSummaryByDays(monthAgoCalendar.timeInMillis, startOfTheDay.timeInMillis).map {
				TableStat(it.time.formatAsDate(), false, listOf(
						StatData(resources.getString(R.string.stats_distance_total), resources.formatDistance(it.distanceInM, 1, lengthSystem)),
						StatData(resources.getString(R.string.stats_collections), it.collections.formatReadable()),
						StatData(resources.getString(R.string.stats_steps), it.steps.formatReadable()),
						StatData(resources.getString(R.string.stats_distance_on_foot), resources.formatDistance(it.distanceOnFootInM, 2, lengthSystem)),
						StatData(resources.getString(R.string.stats_distance_in_vehicle), resources.formatDistance(it.distanceInVehicleInM, 1, lengthSystem))
				))
			}.let {
				it.forEach { statData ->
					handleResponse(statData, AppendPriority(AppendBehavior.Any))
				}
			}

			GlobalScope.launch(Dispatchers.Main) {
				swipeRefreshLayout.isRefreshing = false
			}
		}
		//}
	}

	private fun handleResponse(value: TableStat, appendPriority: AppendPriority) {
		GlobalScope.launch(Dispatchers.Main) {
			addStatsTable(value, appendPriority)
		}
	}

	private fun handleResponse(list: List<TableStat>, appendPriority: AppendPriority) {
		GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
			//todo add in batch to less events are called
			list.forEach { addStatsTable(it, appendPriority) }
		}
	}

	private fun addSessionData(session: TrackerSession) {
		val table = TableCard(false, 10)
		table.title = "${session.start.formatAsShortDateTime()} - ${session.end.formatAsShortDateTime()}"

		val resources = resources
		val lengthSystem = Preferences.getLengthSystem(context!!)

		table.addData(resources.getString(R.string.stats_distance_total), resources.formatDistance(session.distanceInM, 1, lengthSystem))
		table.addData(resources.getString(R.string.stats_collections), session.collections.formatReadable())
		table.addData(resources.getString(R.string.stats_steps), session.steps.formatReadable())
		table.addData(resources.getString(R.string.stats_distance_on_foot), resources.formatDistance(session.distanceOnFootInM, 2, lengthSystem))
		table.addData(resources.getString(R.string.stats_distance_in_vehicle), resources.formatDistance(session.distanceInVehicleInM, 1, lengthSystem))

		table.addButton(resources.getString(R.string.stats_details), View.OnClickListener { startActivity<StatsDetailActivity> { putExtra(StatsDetailActivity.ARG_SESSION_ID, session.id) } })

		GlobalScope.launch(Dispatchers.Main) { adapter.add(table, AppendPriority(AppendBehavior.Any)) }
	}

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
	private fun addStatsTable(stats: TableStat, appendPriority: AppendPriority): TableStat {
		val table = TableCard(stats.showPosition)
		table.title = stats.name
		stats.data.indices
				.asSequence()
				.map { stats.data[it] }
				.forEach { table.addData(it.id, it.value) }
		GlobalScope.launch(Dispatchers.Main) { adapter.add(table, appendPriority) }
		return stats
	}


	override fun onEnter(activity: FragmentActivity) {

	}

	override fun onLeave(activity: FragmentActivity) {
	}


	override fun onPermissionResponse(requestCode: Int, success: Boolean) {

	}
}

