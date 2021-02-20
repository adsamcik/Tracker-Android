package com.adsamcik.tracker.statistics.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.recycler.adapter.implementation.card.table.TableCard
import com.adsamcik.recycler.adapter.implementation.sort.AppendPriority
import com.adsamcik.recycler.adapter.implementation.sort.PrioritySortAdapter
import com.adsamcik.recycler.decoration.MarginDecoration
import com.adsamcik.tracker.shared.base.assist.DisplayAssist
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.formatAsShortDateTime
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.base.extension.startActivity
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.fragment.CoreUIFragment
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.detail.activity.StatsDetailActivity
import com.adsamcik.tracker.statistics.list.recycler.SessionSectionedRecyclerAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*

/**
 * Fragment containing summary list of recent tracker sessions.
 */
@Suppress("unused")
class FragmentStats : CoreUIFragment(), IOnDemandView {
	private var viewModel: StatsViewModel? = null

	private var isEntered = false

	private fun requireViewModel() = requireNotNull(viewModel)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		viewModel = ViewModelProvider(this).get(StatsViewModel::class.java)/*.also { viewModel ->
			viewModel.sessionLiveData.observe(this, this::onDataUpdated)
		}*/
	}

	override fun onCreateView(
			inflater: LayoutInflater,
			container: ViewGroup?,
			savedInstanceState: Bundle?
	): View? {
		val activity = requireActivity()
		val fragmentView = inflater.inflate(R.layout.fragment_stats, container, false)

		val contentPadding = activity.resources.getDimension(
				com.adsamcik.tracker.shared.base.R.dimen.content_padding
		)
				.toInt()
		val statusBarHeight = DisplayAssist.getStatusBarHeight(activity)
		val navBarSize = DisplayAssist.getNavigationBarSize(activity)
		val navBarHeight = navBarSize.second.y

		val adapter = SessionSectionedRecyclerAdapter()
		val recyclerView = fragmentView.findViewById<RecyclerView>(R.id.recycler_stats).apply {
			this.adapter = adapter
			val layoutManager = LinearLayoutManager(activity)
			this.layoutManager = layoutManager

			addItemDecoration(
					MarginDecoration(
							verticalMargin = 0,
							horizontalMargin = 0,
							firstLineMargin = statusBarHeight,
							lastLineMargin = navBarHeight + contentPadding * 2
					)
			)
		}

		requireViewModel().viewModelScope.launch {
			requireViewModel().sessionFlow.collectLatest {
				adapter.submitData(it)
			}
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

			table.addButton(resources.getString(R.string.stats_details)) {
				startActivity<StatsDetailActivity> {
					putExtra(StatsDetailActivity.ARG_SESSION_ID, session.id)
				}
			}

			tableList.add(PrioritySortAdapter.PriorityWrap.create(table, priority))
		}

		//launch(Dispatchers.Main) { adapter.addAll(tableList) }
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
}


