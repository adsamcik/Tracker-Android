package com.adsamcik.tracker.statistics.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.recycler.adapter.implementation.card.table.TableCard
import com.adsamcik.recycler.adapter.implementation.sort.AppendPriority
import com.adsamcik.recycler.adapter.implementation.sort.PrioritySortAdapter
import com.adsamcik.recycler.decoration.MarginDecoration
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.DisplayAssist
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatAsShortDateTime
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.base.extension.startActivity
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.dynamicStyle
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.fragment.CoreUIFragment
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.database.data.CacheStatData
import com.adsamcik.tracker.statistics.detail.activity.StatsDetailActivity
import com.adsamcik.tracker.statistics.list.recycler.SectionedDividerDecoration
import com.adsamcik.tracker.statistics.list.recycler.SessionSection
import com.adsamcik.tracker.statistics.list.recycler.SessionSummaryAdapter
import com.adsamcik.tracker.statistics.list.recycler.SummarySection
import com.adsamcik.tracker.statistics.wifi.WifiBrowseActivity
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.customListAdapter
import com.afollestad.materialdialogs.list.getRecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

@Suppress("unused")
class FragmentStats : CoreUIFragment(), IOnDemandView {
	private var viewModel: StatsViewModel? = null

	private var isEntered = false

	private fun requireViewModel() = requireNotNull(viewModel)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		viewModel = ViewModelProvider(this).get(StatsViewModel::class.java).also { viewModel ->
			viewModel.sessionLiveData.observe(this, this::onDataUpdated)
		}
	}

	//Todo add smart update if sections exist
	private fun onDataUpdated(collection: Collection<TrackerSession>?) {
		if (collection == null) return

		val adapter = requireViewModel().adapter
		adapter.removeAllSections()

		SummarySection().apply {
			addData(R.string.stats_sum_title) {
				showSummary()
			}

			addData(R.string.stats_weekly_title) {
				showLastSevenDays()
			}

			addData(R.string.wifilist_title) {
				startActivity<WifiBrowseActivity> { }
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

	override fun onCreateView(
			inflater: LayoutInflater,
			container: ViewGroup?,
			savedInstanceState: Bundle?
	): View? {
		val activity = requireActivity()
		val fragmentView = inflater.inflate(R.layout.fragment_stats, container, false)

		//weeklyStats.addToViewGroup(view.findViewById(R.id.statsLayout), hasRecentUpload ? 1 : 0, false, 0);

		//todo unify this in some way so it can be easily reused for any recycler currently also in FragmentGame
		val contentPadding = activity.resources.getDimension(
				com.adsamcik.tracker.shared.base.R.dimen.content_padding
		)
				.toInt()
		val statusBarHeight = DisplayAssist.getStatusBarHeight(activity)
		val navBarSize = DisplayAssist.getNavigationBarSize(activity)
		val navBarHeight = navBarSize.second.y

		val recyclerView = fragmentView.findViewById<RecyclerView>(R.id.recycler_stats).apply {
			val adapter = requireViewModel().adapter
			this.adapter = adapter
			val layoutManager = LinearLayoutManager(activity)
			this.layoutManager = layoutManager

			addItemDecoration(
					SectionedDividerDecoration(
							adapter,
							context,
							layoutManager.orientation
					)
			)
			addItemDecoration(
					MarginDecoration(
							verticalMargin = 0,
							horizontalMargin = 0,
							firstLineMargin = statusBarHeight + contentPadding,
							lastLineMargin = navBarHeight + contentPadding
					)
			)
		}

		styleController.watchRecyclerView(
				RecyclerStyleView(
						recyclerView,
						onlyChildren = true,
						childrenLayer = 2
				)
		)
		styleController.watchView(StyleView(fragmentView, layer = 1, maxDepth = 0))

		return fragmentView
	}

	private fun showSummaryDialog(statDataCollection: Collection<CacheStatData>, @StringRes titleRes: Int) {
		val activity = requireActivity()
		val adapter = SessionSummaryAdapter().apply { addAll(statDataCollection) }


		launch(Dispatchers.Main) {
			MaterialDialog(activity).show {
				title(res = titleRes)
				customListAdapter(adapter, LinearLayoutManager(activity)).getRecyclerView().apply {
					addItemDecoration(MarginDecoration())
				}

				dynamicStyle(styleController, DIALOG_LAYER)
			}
		}
	}

	private fun showSummary() {
		launch(Dispatchers.Default) {
			val activity = requireActivity()
			val database = AppDatabase.database(activity)
			val wifiDao = database.wifiDao()
			val cellDao = database.cellLocationDao()
			val locationDao = database.locationDao()
			val sessionDao = database.sessionDao()
			val sumSessionData = sessionDao.getSummary()

			val statList = listOf(
					CacheStatData(
							resources.getString(R.string.stats_time),
							sumSessionData.duration.formatAsDuration(activity)
					),
					CacheStatData(
							resources.getString(R.string.stats_collections),
							sumSessionData.collections.formatReadable()
					),
					CacheStatData(
							resources.getString(R.string.stats_distance_total),
							resources.formatDistance(
									sumSessionData.distanceInM, 1,
									Preferences.getLengthSystem(activity)
							)
					),
					CacheStatData(
							resources.getString(R.string.stats_location_count),
							locationDao.count().formatReadable()
					),
					CacheStatData(
							resources.getString(R.string.stats_wifi_count),
							wifiDao.count().formatReadable()
					),
					CacheStatData(
							resources.getString(R.string.stats_cell_count),
							cellDao.uniqueCount().formatReadable()
					),
					CacheStatData(
							resources.getString(R.string.stats_session_count),
							sessionDao.count().formatReadable()
					),
					CacheStatData(
							resources.getString(R.string.stats_steps),
							sumSessionData.steps.formatReadable()
					)
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

			val database = AppDatabase.database(activity)
			val sessionDao = database.sessionDao()
			val lastWeekSummary = sessionDao.getSummary(weekAgo, now)
			val statDataList = listOf(
					CacheStatData(
							resources.getString(R.string.stats_time),
							lastWeekSummary.duration.formatAsDuration(activity)
					),
					CacheStatData(
							resources.getString(R.string.stats_distance_total),
							resources.formatDistance(
									lastWeekSummary.distanceInM, 1,
									Preferences.getLengthSystem(activity)
							)
					),
					CacheStatData(
							resources.getString(R.string.stats_collections),
							lastWeekSummary.collections.formatReadable()
					),
					CacheStatData(
							resources.getString(R.string.stats_steps),
							lastWeekSummary.steps.formatReadable()
					)
					/*StatData(resources.getString(R.string.stats_location_count), locationDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_wifi_count), wifiDao.count().formatReadable()),
					StatData(resources.getString(R.string.stats_cell_count), cellDao.count().formatReadable())*/
			)

			showSummaryDialog(statDataList, R.string.stats_weekly_title)
		}
	}

	private fun addSessionData(sessionList: List<TrackerSession>, priority: AppendPriority) {
		val tableList = ArrayList<PrioritySortAdapter.PriorityWrap<TableCard>>(sessionList.size)

		sessionList.forEach { session ->
			val table = TableCard(false, 10)
			table.title = "${session.start.formatAsShortDateTime()} - ${session.end.formatAsShortDateTime()}"

			val resources = resources
			val lengthSystem = Preferences.getLengthSystem(requireContext())

			table.addData(
					resources.getString(R.string.stats_distance_total),
					resources.formatDistance(session.distanceInM, 1, lengthSystem)
			)
			table.addData(
					resources.getString(R.string.stats_collections),
					session.collections.formatReadable()
			)
			table.addData(resources.getString(R.string.stats_steps), session.steps.formatReadable())
			table.addData(
					resources.getString(R.string.stats_distance_on_foot),
					resources.formatDistance(session.distanceOnFootInM, 2, lengthSystem)
			)
			table.addData(
					resources.getString(R.string.stats_distance_in_vehicle),
					resources.formatDistance(session.distanceInVehicleInM, 1, lengthSystem)
			)

			table.addButton(resources.getString(R.string.stats_details), View.OnClickListener {
				startActivity<StatsDetailActivity> {
					putExtra(StatsDetailActivity.ARG_SESSION_ID, session.id)
				}
			})

			tableList.add(PrioritySortAdapter.PriorityWrap.create(table, priority))
		}

		//launch(Dispatchers.Main) { adapter.addAll(tableList) }
	}

	private fun generateStatData(index: Int): List<CacheStatData> {
		val list = ArrayList<CacheStatData>()
		for (i in 1..index) {
			list.add(CacheStatData("Title $i", i.toString()))
		}
		return list
	}

	override fun onResume() {
		super.onResume()
		if (isEntered) {
			viewModel?.updateSessionData()
		}
	}

	override fun onEnter(activity: FragmentActivity) {
		isEntered = true
		viewModel?.updateSessionData()
	}

	override fun onLeave(activity: FragmentActivity) = Unit

	override fun onPermissionResponse(requestCode: Int, success: Boolean) = Unit

	companion object {
		const val DIALOG_LAYER = 2
	}
}


