package com.adsamcik.signalcollector.game.challenge.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.color.ColorManager
import com.adsamcik.signalcollector.common.color.ColorSupervisor
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.game.challenge.ChallengeManager
import com.adsamcik.signalcollector.game.challenge.adapter.ChallengeAdapter
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.common.misc.extension.dpAsPx
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class FragmentGame : Fragment(), IOnDemandView {
	private lateinit var recyclerViewChallenges: RecyclerView
	private lateinit var refreshLayout: SwipeRefreshLayout

	private lateinit var colorManager: ColorManager

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val rootView = inflater.inflate(R.layout.fragment_game, container, false)

		recyclerViewChallenges = rootView.findViewById(R.id.recyclerview_challenges)

		refreshLayout = rootView.findViewById(R.id.swiperefresh_activites)
		refreshLayout.setColorSchemeResources(R.color.color_primary)
		refreshLayout.setProgressViewOffset(true, 0, 40.dpAsPx)
		refreshLayout.setOnRefreshListener { this.updateData(ChallengeManager.activeChallenges.value) }

		//updateData()

		val context = context!!
		recyclerViewChallenges.adapter = ChallengeAdapter(context, arrayOf())
		recyclerViewChallenges.layoutManager = LinearLayoutManager(context)
		colorManager = ColorSupervisor.createColorManager(context)
		colorManager.watchAdapterView(ColorView(recyclerViewChallenges, 1, recursive = true, rootIsBackground = false))

		ChallengeManager.activeChallenges.observe(this) { updateData(it) }

		ChallengeManager.initialize(context)

		return rootView
	}

	private fun updateData(challengeList: List<ChallengeInstance<*>>) {
		val challengeArray = challengeList.toTypedArray()
		GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
			(recyclerViewChallenges.adapter as ChallengeAdapter).updateData(challengeArray)
			refreshLayout.isRefreshing = false
		}
	}

	override fun onEnter(activity: FragmentActivity) {}

	override fun onLeave(activity: FragmentActivity) {}

	override fun onPermissionResponse(requestCode: Int, success: Boolean) {

	}
}
