package com.adsamcik.tracker.game.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.observe
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.game.ui.compose.ChallengeUi
import com.adsamcik.tracker.game.ui.compose.GameScreen
import com.adsamcik.tracker.game.ui.compose.StepsSummaryUi
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.utils.fragment.CoreUIFragment
import kotlinx.coroutines.launch

/**
 * Compose-hosted Game fragment (Material 3 expressive)
 */
@Suppress("unused")
class FragmentGame : CoreUIFragment(), IOnDemandView {
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		val ctx = requireContext()

		// Ensure goal/challenge systems are initialized
		GoalTracker.initialize(ctx)
		ChallengeManager.initialize(ctx)

		return ComposeView(ctx).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				val pointsToday by PointsDatabase
					.database(ctx)
					.pointsAwardedDao()
					.countBetweenLive(Time.todayMillis, Time.tomorrowMillis)
					.observeAsState(initial = 0)

				val stepsToday by GoalTracker.stepsDay.observeAsState()
				val stepsWeek by GoalTracker.stepsWeek.observeAsState()
				val goalDay by GoalTracker.goalDay.observeAsState()
				val goalWeek by GoalTracker.goalWeek.observeAsState()

				val steps = if (stepsToday != null && stepsWeek != null && goalDay != null && goalWeek != null) {
					StepsSummaryUi(stepsToday!!, stepsWeek!!, goalDay!!, goalWeek!!)
				} else null

				val challengeLive = ChallengeManager.activeChallenges
				val challengeList by challengeLive.observeAsState(initial = emptyList())
				val challenges = challengeList.map { it.toUi(ctx) }

				GameScreen(
					pointsToday = pointsToday,
					steps = steps,
					challenges = challenges
				)
			}
		}
	}

	private fun ChallengeInstance<*, *>.toUi(context: Context): ChallengeUi = ChallengeUi(
		id = data.id,
		title = getTitle(context),
		description = getDescription(context),
		progress = progress.toFloat().coerceIn(0f, 1f)
	)

	override fun onEnter(activity: FragmentActivity) {}
	override fun onLeave(activity: FragmentActivity) {}
	override fun onPermissionResponse(requestCode: Int, success: Boolean) {}
}

