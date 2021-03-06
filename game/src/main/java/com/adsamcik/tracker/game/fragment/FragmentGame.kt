package com.adsamcik.tracker.game.fragment

import android.content.Context
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
import com.adsamcik.tracker.game.fragment.recycler.creator.StepsCreator
import com.adsamcik.tracker.game.fragment.recycler.data.ChallengeRecyclerData
import com.adsamcik.tracker.game.fragment.recycler.data.PointsRecyclerData
import com.adsamcik.tracker.game.fragment.recycler.data.StepsRecyclerData
import com.adsamcik.tracker.game.fragment.recycler.data.abstraction.GameRecyclerData
import com.adsamcik.tracker.game.goals.GoalTracker
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
		recycler.clipToOutline = false
		//updateChallenges()

		val context = requireContext()
		val adapter = GameAdapter(
				styleController
		).apply {
			registerType(GameRecyclerType.List, ChallengeRecyclerCreator())
			registerType(GameRecyclerType.Points, PointsRecyclerCreator(layer = 1))
			registerType(GameRecyclerType.Steps, StepsCreator(layer = 1))
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

		initializeStyle(rootView, recycler)
		initializePoints(context, adapter)
		initializeGoals(adapter)
		initializeChallenges(context, adapter)

		return rootView
	}

	private fun initializeStyle(rootView: View, recycler: RecyclerView) {
		styleController.watchView(StyleView(rootView, layer = 1, maxDepth = 0))
		styleController.watchRecyclerView(
				RecyclerStyleView(
						recycler,
						onlyChildren = true,
						childrenLayer = 2
				)
		)
	}

	private fun initializePoints(context: Context, adapter: GameAdapter) {
		adapter.add(PointsRecyclerData(-1))
		val pointsIndex = adapter.itemCount - 1
		PointsDatabase
				.database(context)
				.pointsAwardedDao()
				.countBetweenLive(Time.todayMillis, Time.tomorrowMillis)
				.observe(viewLifecycleOwner) { pointsEarned ->
					adapter.updateAt(pointsIndex, PointsRecyclerData(pointsEarned ?: 0))
				}
	}

	private fun initializeGoals(adapter: GameAdapter) {
		adapter.add(StepsRecyclerData(0, 0, 0, 0))
		val pointsIndex = adapter.itemCount - 1

		GoalTracker.stepsDay.observe(this) {
			adapter.updateGoals(pointsIndex, stepsToday = it)
		}

		GoalTracker.stepsWeek.observe(this) {
			adapter.updateGoals(pointsIndex, stepsWeek = it)
		}

		GoalTracker.goalDay.observe(this) {
			adapter.updateGoals(pointsIndex, goalDay = it)
		}

		GoalTracker.goalWeek.observe(this) {
			adapter.updateGoals(pointsIndex, goalWeek = it)
		}
	}

	private fun GameAdapter.updateGoals(
			index: Int,
			stepsToday: Int = GoalTracker.stepsDay.value,
			stepsWeek: Int = GoalTracker.stepsWeek.value,
			goalDay: Int = GoalTracker.goalDay.value,
			goalWeek: Int = GoalTracker.goalWeek.value
	) = updateAt(index, StepsRecyclerData(stepsToday, stepsWeek, goalDay, goalWeek))

	private fun initializeChallenges(context: Context, adapter: GameAdapter) {
		val challengeAdapter = ChallengeAdapter(context, arrayOf())
		adapter.add(ChallengeRecyclerData(R.string.challenge_list_title, challengeAdapter))

		ChallengeManager.activeChallenges.observe(this) { updateChallenges(challengeAdapter, it) }

		val challengeList = ChallengeManager.activeChallenges.value
		if (challengeList.isNullOrEmpty()) {
			ChallengeManager.initialize(context)
		} else {
			updateChallenges(challengeAdapter, challengeList)
		}
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

	override fun onEnter(activity: FragmentActivity): Unit = Unit

	override fun onLeave(activity: FragmentActivity): Unit = Unit

	override fun onPermissionResponse(requestCode: Int, success: Boolean): Unit = Unit
}

