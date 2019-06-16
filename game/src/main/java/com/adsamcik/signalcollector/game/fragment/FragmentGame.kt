package com.adsamcik.signalcollector.game.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.recycler.card.CardItemDecoration
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.fragment.CoreUIFragment
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeAdapter
import com.adsamcik.signalcollector.game.R
import com.adsamcik.signalcollector.game.challenge.ChallengeManager
import com.adsamcik.signalcollector.game.challenge.adapter.ChallengeAdapter
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.game.fragment.recycler.GameRecyclerType
import com.adsamcik.signalcollector.game.fragment.recycler.creator.ChallengeRecyclerCreator
import com.adsamcik.signalcollector.game.fragment.recycler.data.ChallengeRecyclerData
import com.adsamcik.signalcollector.game.fragment.recycler.data.GameRecyclerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

typealias GameAdapter = MultiTypeAdapter<GameRecyclerType, GameRecyclerData>

@Suppress("unused")
class FragmentGame : CoreUIFragment(), IOnDemandView {
	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val rootView = inflater.inflate(R.layout.fragment_game, container, false)

		val recycler = rootView.findViewById<RecyclerView>(R.id.recycler)
		//updateChallenges()

		val context = requireContext()
		@Suppress("unchecked_cast")
		val adapter = GameAdapter(colorController).apply {
			registerType(GameRecyclerType.List, ChallengeRecyclerCreator())
		}.also { recycler.adapter = it }
		//recyclerView.adapter = ChallengeAdapter(context, arrayOf())
		recycler.layoutManager = LinearLayoutManager(context)
		recycler.addItemDecoration(CardItemDecoration())

		colorController.watchView(ColorView(rootView, 1))
		colorController.watchRecyclerView(ColorView(recycler, 1))

		val challengeAdapter = ChallengeAdapter(context, arrayOf())
		adapter.add(ChallengeRecyclerData(R.string.challenge_list_title, challengeAdapter), AppendPriority.Any)

		ChallengeManager.activeChallenges.observe(this) { updateChallenges(challengeAdapter, it) }

		ChallengeManager.initialize(context)

		return rootView
	}

	private fun updateChallenges(challengeAdapter: ChallengeAdapter, challengeList: List<ChallengeInstance<*>>) {
		val challengeArray = challengeList.toTypedArray()

		launch(Dispatchers.Main) {
			challengeAdapter.updateData(challengeArray)
		}
	}

	override fun onEnter(activity: FragmentActivity) {}

	override fun onLeave(activity: FragmentActivity) {}

	override fun onPermissionResponse(requestCode: Int, success: Boolean) {

	}
}
