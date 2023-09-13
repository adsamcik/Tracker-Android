package com.adsamcik.tracker.statistics.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ViewFlipper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.recycler.decoration.MarginDecoration
import com.adsamcik.tracker.shared.base.assist.DisplayAssist
import com.adsamcik.tracker.shared.utils.extension.isEmpty
import com.adsamcik.tracker.shared.utils.fragment.CoreUIFragment
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.list.recycler.SessionSectionedRecyclerAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment containing summary list of recent tracker sessions.
 */
@Suppress("unused")
class FragmentStats : CoreUIFragment(), IOnDemandView {
	private var viewModel: StatsViewModel? = null

	private fun requireViewModel() = requireNotNull(viewModel)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		viewModel = ViewModelProvider(this)[StatsViewModel::class.java]
	}

	override fun onCreateView(
			inflater: LayoutInflater,
			container: ViewGroup?,
			savedInstanceState: Bundle?
	): View {
		val activity = requireActivity()
		val fragmentView = inflater.inflate(R.layout.fragment_stats, container, false)
		val flipper = fragmentView as ViewFlipper

		val contentPadding = activity.resources.getDimension(
				com.adsamcik.tracker.shared.base.R.dimen.content_padding
		).toInt()
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

		adapter.addLoadStateListener { loadState ->
			if (loadState.append.endOfPaginationReached && adapter.isEmpty) {
				if (flipper.currentView == recyclerView) {
					flipper.showNext()
				}
			} else if (!adapter.isEmpty && flipper.currentView != recyclerView) {
				flipper.showPrevious()
			}
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
		styleController.watchView(StyleView(fragmentView, layer = 1))

		return fragmentView
	}


	override fun onEnter(activity: FragmentActivity): Unit = Unit

	override fun onLeave(activity: FragmentActivity): Unit = Unit

	override fun onPermissionResponse(requestCode: Int, success: Boolean): Unit = Unit
}


