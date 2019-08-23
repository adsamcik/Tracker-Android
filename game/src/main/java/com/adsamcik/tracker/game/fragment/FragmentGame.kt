package com.adsamcik.tracker.game.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.tracker.common.Assist
import com.adsamcik.tracker.common.fragment.CoreUIFragment
import com.adsamcik.tracker.common.recycler.decoration.SimpleMarginDecoration
import com.adsamcik.tracker.common.recycler.multitype.MultiTypeAdapter
import com.adsamcik.tracker.common.style.RecyclerStyleView
import com.adsamcik.tracker.common.style.StyleView
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.game.challenge.adapter.ChallengeAdapter
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.fragment.recycler.GameRecyclerType
import com.adsamcik.tracker.game.fragment.recycler.creator.ChallengeRecyclerCreator
import com.adsamcik.tracker.game.fragment.recycler.data.ChallengeRecyclerData
import com.adsamcik.tracker.game.fragment.recycler.data.GameRecyclerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

typealias GameAdapter = MultiTypeAdapter<GameRecyclerType, GameRecyclerData>

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
		val adapter = GameAdapter(styleController).apply {
			registerType(GameRecyclerType.List, ChallengeRecyclerCreator())
		}.also { recycler.adapter = it }
		//recyclerView.adapter = ChallengeAdapter(context, arrayOf())
		recycler.layoutManager = LinearLayoutManager(context)


		val contentPadding = context.resources.getDimension(com.adsamcik.tracker.common.R.dimen.content_padding)
				.toInt()
		val statusBarHeight = Assist.getStatusBarHeight(context)
		val navBarSize = Assist.getNavigationBarSize(context)
		val navBarHeight = navBarSize.second.y

		recycler.addItemDecoration(
				SimpleMarginDecoration(
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

		val challengeAdapter = ChallengeAdapter(context, arrayOf())
		adapter.add(
				ChallengeRecyclerData(R.string.challenge_list_title, challengeAdapter),
				AppendPriority.Any
		)

		ChallengeManager.activeChallenges.observe(this) { updateChallenges(challengeAdapter, it) }

		ChallengeManager.initialize(context)

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

	override fun onEnter(activity: FragmentActivity) {}

	override fun onLeave(activity: FragmentActivity) {}

	override fun onPermissionResponse(requestCode: Int, success: Boolean) {}
}

