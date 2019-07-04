package com.adsamcik.signalcollector.statistics.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.recycler.card.table.TableCard
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.color.StyleView
import com.adsamcik.signalcollector.common.color.RecyclerStyleView
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.extension.*
import com.adsamcik.signalcollector.common.fragment.CoreUIFragment
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.common.recycler.decoration.SimpleMarginDecoration
import com.adsamcik.signalcollector.statistics.R
import com.adsamcik.signalcollector.statistics.data.StatData
import com.adsamcik.signalcollector.statistics.detail.activity.StatsDetailActivity
import com.adsamcik.signalcollector.statistics.list.recycler.SectionedDividerDecoration
import com.adsamcik.signalcollector.statistics.list.recycler.SessionSection
import com.adsamcik.signalcollector.statistics.list.recycler.SessionSummaryAdapter
import com.adsamcik.signalcollector.statistics.list.recycler.SummarySection
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.customListAdapter
import com.afollestad.materialdialogs.list.getRecyclerView
import io.github.luizgrp.sectionedrecyclerviewadapter.SectionedRecyclerViewAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

@Suppress("unused")
//todo move this to the main package so basic overview can be accessed and activities set
class FragmentStats : CoreUIFragment(), IOnDemandView {
	private lateinit var adapter: SectionedRecyclerViewAdapter

	private lateinit var swipeRefreshLayout: SwipeRefreshLayout

	private lateinit var viewModel: StatsViewModel

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		adapter = SectionedRecyclerViewAdapter()

		viewModel = ViewModelProviders.of(this).get(StatsViewModel::class.java)

		viewModel.sessionLiveData.observe(this, this::onDataUpdated)
	}

	//Todo add smart update if sections exist
	private fun onDataUpdated(collection: Collection<TrackerSession>?) {
		if (collection == null) return

		adapter.removeAllSections()

		SummarySection().apply {
			addData(R.string.stats_sum_title) {
				showSummary()
			}

			addData(R.string.stats_weekly_title) {
				showLastSevenDays()
			}
		}.also { adapter.addSection(it) }

		collection.groupBy { Time.roundToDate(it.start) }.forEach {
			val distance = it.value.sumByDouble { session -> session.distanceInM.toDouble() }
			adapter.addSection(SessionSection(it.key, distance).apply {
				addAll(it.value)
			})
		}

		adapter.notifyDataSetChanged()
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val activity = requireActivity()
		val fragmentView = inflater.inflate(R.layout.fragment_stats, container, false)

		//weeklyStats.addToViewGroup(view.findViewById(R.id.statsLayout), hasRecentUpload ? 1 : 0, false, 0);

		//todo unify this in some way so it can be easily reused for any recycler currently also in FragmentGame
		val contentPadding = activity.resources.getDimension(com.adsamcik.signalcollector.common.R.dimen.content_padding).toInt()
		val statusBarHeight = Assist.getStatusBarHeight(activity)
		val navBarSize = Assist.getNavigationBarSize(activity)
		val navBarHeight = navBarSize.second.y

		val recyclerView = fragmentView.findViewById<RecyclerView>(R.id.recycler_stats).apply {
			this.adapter = this@FragmentStats.adapter
			val layoutManager = LinearLayoutManager(activity)
			this.layoutManager = layoutManager

			addItemDecoration(SectionedDividerDecoration(this@FragmentStats.adapter, context, layoutManager.orientation))
			addItemDecoration(SimpleMarginDecoration(spaceBetweenItems = 0,
					horizontalMargin = 0,
					firstRowMargin = statusBarHeight + contentPadding,
					lastRowMargin = navBarHeight + contentPadding))
		}

		styleController.watchRecyclerView(RecyclerStyleView(recyclerView, layer = 1))
		styleController.watchView(StyleView(fragmentView, layer = 1, maxDepth = 0))

		return fragmentView
	}

	private fun showSummary() {
		launch(Dispatchers.Default) {
			val activity = requireActivity()
			val database = AppDatabase.getDatabase(activity)
			val wifiDao = database.wifiDao()
			val cellDao = database.cellDao()
			val locationDao = database.locationDao()
			val sessionDao = database.sessionDao()
			val sumSessionData = sessionDao.getSummary()

			val adapter = SessionSummaryAdapter()
			adapter.addAll(listOf(
					StatData(resources.getString(R.string.stats_time), sumSessionData.duration.formatAsDuration(activity)),
					StatData(resources.getString(R.string.stats_collections), sumSessionData.collections.formatReadable()),
					StatData(resources.getString(R.string.stats_distance_total), resources.formatDistance(sumSessionData.distanceInM, 1, Preferences.getLengthSystem(activity))),
					StatData(resources.getString(R.string.stats_location_count), locationDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_wifi_count), wifiDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_cell_count), cellDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_session_count), sessionDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_steps), sumSessionData.steps.formatReadable())
			))

			launch(Dispatchers.Main) {
				MaterialDialog(activity).show {
					title(res = R.string.stats_sum_title)
					customListAdapter(adapter, LinearLayoutManager(activity)).getRecyclerView().apply {
						addItemDecoration(SimpleMarginDecoration())
					}

					styleController.watchView(StyleView(view, 2))
					setOnDismissListener {
						styleController.stopWatchingView(view)
					}
				}
			}
		}
	}

	private fun showLastSevenDays() {
		launch(Dispatchers.Default) {
			val activity = requireActivity()
			val now = Time.nowMillis
			val weekAgo = Calendar.getInstance(Locale.getDefault()).apply {
				add(Calendar.WEEK_OF_MONTH, -1)
			}.timeInMillis

			val database = AppDatabase.getDatabase(activity)
			val sessionDao = database.sessionDao()
			val lastWeekSummary = sessionDao.getSummary(weekAgo, now)

			val adapter = SessionSummaryAdapter()
			adapter.addAll(listOf(
					StatData(resources.getString(R.string.stats_time), lastWeekSummary.duration.formatAsDuration(activity)),
					StatData(resources.getString(R.string.stats_distance_total), resources.formatDistance(lastWeekSummary.distanceInM, 1, Preferences.getLengthSystem(activity))),
					StatData(resources.getString(R.string.stats_collections), lastWeekSummary.collections.formatReadable()),
					StatData(resources.getString(R.string.stats_steps), lastWeekSummary.steps.formatReadable())
					/*StatData(resources.getString(R.string.stats_location_count), locationDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_wifi_count), wifiDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_cell_count), cellDao.count().formatReadable())*/
			))

			launch(Dispatchers.Main) {
				MaterialDialog(activity).show {
					title(res = R.string.stats_weekly_title)
					customListAdapter(adapter, LinearLayoutManager(activity)).getRecyclerView().apply {
						addItemDecoration(SimpleMarginDecoration())
					}

					styleController.watchView(StyleView(view, 2))
					setOnDismissListener {
						styleController.stopWatchingView(view)
					}
				}
			}
		}
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

		//launch(Dispatchers.Main) { adapter.addAll(tableList) }
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

