package com.adsamcik.tracker.game.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.recycler.decoration.MarginDecoration
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.game.challenge.adapter.ChallengeAdapter
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.fragment.recycler.GameRecyclerType
import com.adsamcik.tracker.game.fragment.recycler.creator.ChallengeRecyclerCreator
import com.adsamcik.tracker.game.fragment.recycler.creator.PointsRecyclerCreator
import com.adsamcik.tracker.game.fragment.recycler.data.ChallengeRecyclerData
import com.adsamcik.tracker.game.fragment.recycler.data.GameRecyclerData
import com.adsamcik.tracker.game.fragment.recycler.data.PointsRecyclerData
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.DisplayAssist
import com.adsamcik.tracker.shared.utils.fragment.CoreUIFragment
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeAdapter
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

typealias GameAdapter = StyleMultiTypeAdapter<GameRecyclerType, GameRecyclerData>

/**
 * Root fragment for game component
 */
@Suppress("unused")
class FragmentGame : CoreUIFragment(), IOnDemandView {
	override fun onCreateView(
			inflater: LayoutInflater,
			container: ViewGroup?,
			savedInstanceState: Bundle?
	): View? {
		val rootView = inflater.inflate(R.layout.fragment_game, container, false)

		val recycler = rootView.findViewById<RecyclerView>(R.id.recycler)
		//updateChallenges()

		val context = requireContext()
		val adapter = GameAdapter(
				styleController
		).apply {
			registerType(GameRecyclerType.List, ChallengeRecyclerCreator())
			registerType(GameRecyclerType.Points, PointsRecyclerCreator(layer = 1))
		}.also { recycler.adapter = it }
		//recyclerView.adapter = ChallengeAdapter(context, arrayOf())
		recycler.layoutManager = LinearLayoutManager(context)


		val contentPadding = context.resources.getDimension(com.adsamcik.tracker.shared.base.R.dimen.content_padding)
				.toInt()
		val statusBarHeight = DisplayAssist.getStatusBarHeight(context)
		val navBarSize = DisplayAssist.getNavigationBarSize(context)
		val navBarHeight = navBarSize.second.y

		recycler.addItemDecoration(
				MarginDecoration(
						firstLineMargin = statusBarHeight + contentPadding,
						lastLineMargin = navBarHeight + contentPadding
				)
		)

		styleController.watchView(StyleView(rootView, layer = 1, maxDepth = 0))
		styleController.watchRecyclerView(
				RecyclerStyleView(
						recycler,
						onlyChildren = true,
						childrenLayer = 2
				)
		)
		adapter.add(PointsRecyclerData(-1))
		val pointsIndex = adapter.itemCount - 1
		PointsDatabase
				.database(context)
				.pointsAwardedDao()
				.countBetweenLive(Time.todayMillis, Time.tomorrowMillis)
				.observe(viewLifecycleOwner) { pointsEarned ->
					adapter.updateAt(pointsIndex, PointsRecyclerData(pointsEarned ?: 0))
				}


		val challengeAdapter = ChallengeAdapter(context, arrayOf())
		adapter.add(ChallengeRecyclerData(R.string.challenge_list_title, challengeAdapter))

		ChallengeManager.activeChallenges.observe(this) { updateChallenges(challengeAdapter, it) }

		val challengeList = ChallengeManager.activeChallenges.value
		if (challengeList.isNullOrEmpty()) {
			ChallengeManager.initialize(context)
		} else {
			updateChallenges(challengeAdapter, challengeList)
		}

		return rootView
	}

	private fun updateChallenges(
			challengeAdapter: ChallengeAdapter,
			challengeList: List<ChallengeInstance<*, *>>
	) {
		val challengeArray = challengeList.toTypedArray()

		launch(Dispatchers.Main) {
			challengeAdapter.updateData(challengeArray)
		}
	}

	override fun onEnter(activity: FragmentActivity) = Unit

	override fun onLeave(activity: FragmentActivity) = Unit

	override fun onPermissionResponse(requestCode: Int, success: Boolean) = Unit
}

