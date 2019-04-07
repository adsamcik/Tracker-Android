package com.adsamcik.signalcollector.game.fragment

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
import com.adsamcik.signalcollector.app.color.ColorManager
import com.adsamcik.signalcollector.app.color.ColorSupervisor
import com.adsamcik.signalcollector.app.color.ColorView
import com.adsamcik.signalcollector.game.challenge.ChallengeManager
import com.adsamcik.signalcollector.game.challenge.adapter.ChallengeAdapter
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.misc.extension.dpAsPx
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
		refreshLayout.setOnRefreshListener { this.updateData() }

		//updateData()

		val context = context!!
		ChallengeManager.initialize(context)
		recyclerViewChallenges.adapter = ChallengeAdapter(context, arrayOf())
		recyclerViewChallenges.layoutManager = LinearLayoutManager(context)
		colorManager = ColorSupervisor.createColorManager(context)
		colorManager.watchAdapterView(ColorView(recyclerViewChallenges, 1, recursive = true, rootIsBackground = false))

		updateData()

		return rootView
	}

	private fun updateData() {
		GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
			(recyclerViewChallenges.adapter as ChallengeAdapter).updateData(ChallengeManager.activeChallenges.toTypedArray())
			refreshLayout.isRefreshing = false
		}
	}

	override fun onEnter(activity: FragmentActivity) {}

	override fun onLeave(activity: FragmentActivity) {}

	override fun onPermissionResponse(requestCode: Int, success: Boolean) {

	}
}
