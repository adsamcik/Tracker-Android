package com.adsamcik.signalcollector.statistics.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.recycler.card.table.TableCard
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.extension.formatAsDuration
import com.adsamcik.signalcollector.common.extension.formatAsShortDateTime
import com.adsamcik.signalcollector.common.extension.formatDistance
import com.adsamcik.signalcollector.common.extension.formatReadable
import com.adsamcik.signalcollector.common.extension.observe
import com.adsamcik.signalcollector.common.extension.startActivity
import com.adsamcik.signalcollector.common.fragment.CoreUIFragment
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.common.recycler.decoration.SimpleMarginDecoration
import com.adsamcik.signalcollector.common.style.RecyclerStyleView
import com.adsamcik.signalcollector.common.style.StyleView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

@Suppress("unused")
//todo move this to the main package so basic overview can be accessed and activities set
class FragmentStats : CoreUIFragment(), IOnDemandView {
	private lateinit var viewModel: StatsViewModel

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		viewModel = ViewModelProvider(this).get(StatsViewModel::class.java)

		viewModel.sessionLiveData.observe(this, this::onDataUpdated)
	}

	//Todo add smart update if sections exist
	private fun onDataUpdated(collection: Collection<TrackerSession>?) {
		if (collection == null) return

		viewModel.adapter.removeAllSections()

		SummarySection().apply {
			addData(R.string.stats_sum_title) {
				showSummary()
			}

			addData(R.string.stats_weekly_title) {
				showLastSevenDays()
			}
		}.also { viewModel.adapter.addSection(it) }

		collection.groupBy { Time.roundToDate(it.start) }.forEach {
			val distance = it.value.sumByDouble { session -> session.distanceInM.toDouble() }
			viewModel.adapter.addSection(SessionSection(it.key, distance).apply {
				addAll(it.value)
			})
		}

		viewModel.adapter.notifyDataSetChanged()
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val activity = requireActivity()
		val fragmentView = inflater.inflate(R.layout.fragment_stats, container, false)

		//weeklyStats.addToViewGroup(view.findViewById(R.id.statsLayout), hasRecentUpload ? 1 : 0, false, 0);

		//todo unify this in some way so it can be easily reused for any recycler currently also in FragmentGame
		val contentPadding = activity.resources.getDimension(
				com.adsamcik.signalcollector.common.R.dimen.content_padding)
				.toInt()
		val statusBarHeight = Assist.getStatusBarHeight(activity)
		val navBarSize = Assist.getNavigationBarSize(activity)
		val navBarHeight = navBarSize.second.y

		val recyclerView = fragmentView.findViewById<RecyclerView>(R.id.recycler_stats).apply {
			adapter = viewModel.adapter
			val layoutManager = LinearLayoutManager(activity)
			this.layoutManager = layoutManager

			addItemDecoration(SectionedDividerDecoration(viewModel.adapter, context, layoutManager.orientation))
			addItemDecoration(SimpleMarginDecoration(verticalMargin = 0,
					horizontalMargin = 0,
					firstLineMargin = statusBarHeight + contentPadding,
					lastLineMargin = navBarHeight + contentPadding))
		}

		styleController.watchRecyclerView(RecyclerStyleView(recyclerView, onlyChildren = true, childrenLayer = 2))
		styleController.watchView(StyleView(fragmentView, layer = 1, maxDepth = 0))

		return fragmentView
	}

	private fun showSummaryDialog(statDataCollection: Collection<StatData>, @StringRes titleRes: Int) {
		val activity = requireActivity()
		val adapter = SessionSummaryAdapter().apply { addAll(statDataCollection) }


		launch(Dispatchers.Main) {
			MaterialDialog(activity).show {
				title(res = titleRes)
				customListAdapter(adapter, LinearLayoutManager(activity)).getRecyclerView().apply {
					addItemDecoration(SimpleMarginDecoration())
					styleController.watchRecyclerView(RecyclerStyleView(this, 2))
				}

				styleController.watchView(StyleView(view, 2))
				setOnDismissListener {
					styleController.stopWatchingView(view)
					styleController.stopWatchingRecyclerView(getRecyclerView())
				}
			}
		}
	}

	private fun showSummary() {
		launch(Dispatchers.Default) {
			val activity = requireActivity()
			val database = AppDatabase.getDatabase(activity)
			val wifiDao = database.wifiDao()
			val cellDao = database.cellLocationDao()
			val locationDao = database.locationDao()
			val sessionDao = database.sessionDao()
			val sumSessionData = sessionDao.getSummary()

			val statList = listOf(
					StatData(resources.getString(R.string.stats_time),
							sumSessionData.duration.formatAsDuration(activity)),
					StatData(resources.getString(R.string.stats_collections),
							sumSessionData.collections.formatReadable()),
					StatData(resources.getString(R.string.stats_distance_total),
							resources.formatDistance(sumSessionData.distanceInM, 1,
									Preferences.getLengthSystem(activity))),
					StatData(resources.getString(R.string.stats_location_count), locationDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_wifi_count), wifiDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_cell_count), cellDao.uniqueCount().formatReadable()),
					StatData(resources.getString(R.string.stats_session_count), sessionDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_steps), sumSessionData.steps.formatReadable())
			)

			showSummaryDialog(statList, R.string.stats_sum_title)

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
			val statDataList = listOf(
					StatData(resources.getString(R.string.stats_time),
							lastWeekSummary.duration.formatAsDuration(activity)),
					StatData(resources.getString(R.string.stats_distance_total),
							resources.formatDistance(lastWeekSummary.distanceInM, 1,
									Preferences.getLengthSystem(activity))),
					StatData(resources.getString(R.string.stats_collections),
							lastWeekSummary.collections.formatReadable()),
					StatData(resources.getString(R.string.stats_steps), lastWeekSummary.steps.formatReadable())
					/*StatData(resources.getString(R.string.stats_location_count), locationDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_wifi_count), wifiDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_cell_count), cellDao.count().formatReadable())*/
			)

			showSummaryDialog(statDataList, R.string.stats_weekly_title)
		}
	}

	private fun addSessionData(sessionList: List<TrackerSession>, priority: AppendPriority) {
		val tableList = ArrayList<SortableAdapter.SortableData<TableCard>>(sessionList.size)

		sessionList.forEach { session ->
			val table = TableCard(false, 10)
			table.title = "${session.start.formatAsShortDateTime()} - ${session.end.formatAsShortDateTime()}"

			val resources = resources
			val lengthSystem = Preferences.getLengthSystem(requireContext())

			table.addData(resources.getString(R.string.stats_distance_total),
					resources.formatDistance(session.distanceInM, 1, lengthSystem))
			table.addData(resources.getString(R.string.stats_collections), session.collections.formatReadable())
			table.addData(resources.getString(R.string.stats_steps), session.steps.formatReadable())
			table.addData(resources.getString(R.string.stats_distance_on_foot),
					resources.formatDistance(session.distanceOnFootInM, 2, lengthSystem))
			table.addData(resources.getString(R.string.stats_distance_in_vehicle),
					resources.formatDistance(session.distanceInVehicleInM, 1, lengthSystem))

			table.addButton(resources.getString(R.string.stats_details), View.OnClickListener {
				startActivity<StatsDetailActivity> {
					putExtra(StatsDetailActivity.ARG_SESSION_ID, session.id)
				}
			})

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

	override fun onResume() {
		super.onResume()
		viewModel.updateSessionData()
	}

	override fun onEnter(activity: FragmentActivity) {
		viewModel.updateSessionData()
	}

	override fun onLeave(activity: FragmentActivity) {
	}


	override fun onPermissionResponse(requestCode: Int, success: Boolean) {

	}
}

