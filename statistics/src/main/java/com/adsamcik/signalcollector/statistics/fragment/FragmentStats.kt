package com.adsamcik.signalcollector.statistics.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.recycler.AppendBehavior
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.recycler.card.table.TableCard
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.DatabaseMaintenance
import com.adsamcik.signalcollector.common.extension.*
import com.adsamcik.signalcollector.common.fragment.CoreUIFragment
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.common.recycler.decoration.SimpleMarginDecoration
import com.adsamcik.signalcollector.statistics.ChangeTableAdapter
import com.adsamcik.signalcollector.statistics.R
import com.adsamcik.signalcollector.statistics.data.StatData
import com.adsamcik.signalcollector.statistics.data.TableStat
import com.adsamcik.signalcollector.statistics.detail.activity.StatsDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

@Suppress("unused")
//todo move this to the main package so basic overview can be accessed and activities set
class FragmentStats : CoreUIFragment(), IOnDemandView {
	private lateinit var fragmentView: View

	private lateinit var adapter: ChangeTableAdapter

	private lateinit var swipeRefreshLayout: SwipeRefreshLayout

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val activity = requireActivity()
		val fragmentView = inflater.inflate(R.layout.fragment_stats, container, false)

		adapter = ChangeTableAdapter(activity.packageManager.getActivityInfo(activity.componentName, 0).themeResource)

		//weeklyStats.addToViewGroup(view.findViewById(R.id.statsLayout), hasRecentUpload ? 1 : 0, false, 0);

		//todo unify this in some way so it can be easily reused for any recycler currently also in FragmentGame
		val contentPadding = activity.resources.getDimension(com.adsamcik.signalcollector.common.R.dimen.content_padding).toInt()
		val statusBarHeight = Assist.getStatusBarHeight(activity)
		val navBarSize = Assist.getNavigationBarSize(activity)
		val navBarHeight = navBarSize.second.y

		swipeRefreshLayout = fragmentView.findViewById(R.id.swiperefresh_stats)
		swipeRefreshLayout.setOnRefreshListener { this.updateStats() }
		//swipeRefreshLayout.setColorSchemeResources(R.color.color_primary)
		swipeRefreshLayout.setProgressViewOffset(true, 0, statusBarHeight)

		val recyclerView = fragmentView.findViewById<RecyclerView>(R.id.recycler_stats).apply {
			this.adapter = this@FragmentStats.adapter
			layoutManager = LinearLayoutManager(activity)
			addItemDecoration(SimpleMarginDecoration(
					firstRowMargin = statusBarHeight + contentPadding,
					lastRowMargin = navBarHeight + contentPadding))
		}
		updateStats()

		this.fragmentView = fragmentView

		colorController.watchRecyclerView(ColorView(recyclerView, layer = 1))
		colorController.watchView(ColorView(fragmentView, layer = 1, maxDepth = 0))

		return fragmentView
	}

	private fun updateStats() {
		val context = requireContext()

		adapter.clear()

		val resources = context.resources

		launch(Dispatchers.Main) { swipeRefreshLayout.isRefreshing = true }


		launch(Dispatchers.Default) {
			val database = AppDatabase.getDatabase(context)
			val sessionDao = database.sessionDao()
			val wifiDao = database.wifiDao()
			val cellDao = database.cellDao()
			val locationDao = database.locationDao()

			val calendar = Calendar.getInstance()

			val now = calendar.timeInMillis

			calendar.add(Calendar.WEEK_OF_YEAR, -1)
			val weekAgo = calendar.timeInMillis

			DatabaseMaintenance().run(context)

			val lengthSystem = Preferences.getLengthSystem(context)

			val sumSessionData = sessionDao.getSummary()
			val summaryStats = TableStat(resources.getString(R.string.stats_sum_title), showPosition = false, data = listOf(
					StatData(resources.getString(R.string.stats_time), sumSessionData.duration.formatAsDuration(context)),
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
					StatData(resources.getString(R.string.stats_time), lastMonthSummary.duration.formatAsDuration(context)),
					StatData(resources.getString(R.string.stats_distance_total), resources.formatDistance(lastMonthSummary.distanceInM, 1, lengthSystem)),
					StatData(resources.getString(R.string.stats_collections), lastMonthSummary.collections.formatReadable()),
					StatData(resources.getString(R.string.stats_steps), lastMonthSummary.steps.formatReadable())
			))

			addTableStat(listOf(summaryStats, weeklyStats), AppendPriority(AppendBehavior.Start))

			val startOfTheDay = Calendar.getInstance().apply { roundToDate() }

			val todayStats = sessionDao.getBetween(startOfTheDay.timeInMillis, now)
			addSessionData(todayStats, AppendPriority(AppendBehavior.End, -1))

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
				addTableStat(it, AppendPriority(AppendBehavior.End))
			}

			launch(Dispatchers.Main) { swipeRefreshLayout.isRefreshing = false }
		}
	}

	private fun addTableStat(list: List<TableStat>, appendPriority: AppendPriority) {
		val tableList = ArrayList<SortableAdapter.SortableData<TableCard>>(list.size)

		list.forEach { stats ->
			val table = TableCard(stats.showPosition)
			table.title = stats.name
			stats.data.indices
					.map { stats.data[it] }
					.forEach { table.addData(it.id, it.value) }

			tableList.add(SortableAdapter.SortableData(table, appendPriority))
		}

		launch(Dispatchers.Main) { adapter.addAll(tableList) }
	}

	private fun addSessionData(sessionList: List<TrackerSession>, priority: AppendPriority) {
		val tableList = ArrayList<SortableAdapter.SortableData<TableCard>>(sessionList.size)

		sessionList.forEach { session ->
			val table = TableCard(false, 10)
			table.title = "${session.start.formatAsShortDateTime()} - ${session.end.formatAsShortDateTime()}"

			val resources = resources
			val lengthSystem = Preferences.getLengthSystem(requireContext())

			table.addData(resources.getString(R.string.stats_distance_total), resources.formatDistance(session.distanceInM, 1, lengthSystem))
			table.addData(resources.getString(R.string.stats_collections), session.collections.formatReadable())
			table.addData(resources.getString(R.string.stats_steps), session.steps.formatReadable())
			table.addData(resources.getString(R.string.stats_distance_on_foot), resources.formatDistance(session.distanceOnFootInM, 2, lengthSystem))
			table.addData(resources.getString(R.string.stats_distance_in_vehicle), resources.formatDistance(session.distanceInVehicleInM, 1, lengthSystem))

			table.addButton(resources.getString(R.string.stats_details), View.OnClickListener { startActivity<StatsDetailActivity> { putExtra(StatsDetailActivity.ARG_SESSION_ID, session.id) } })

			tableList.add(SortableAdapter.SortableData(table, priority))
		}

		launch(Dispatchers.Main) { adapter.addAll(tableList) }
	}

	private fun generateStatData(index: Int): List<StatData> {
		val list = ArrayList<StatData>()
		for (i in 1..index) {
			list.add(StatData("Title $i", i.toString()))
		}
		return list
	}


	override fun onEnter(activity: FragmentActivity) {

	}

	override fun onLeave(activity: FragmentActivity) {
	}


	override fun onPermissionResponse(requestCode: Int, success: Boolean) {

	}
}

