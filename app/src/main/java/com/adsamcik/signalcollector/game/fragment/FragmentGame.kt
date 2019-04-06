package com.adsamcik.signalcollector.game.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.adapter.IViewChange
import com.adsamcik.signalcollector.app.color.ColorManager
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulties
import com.adsamcik.signalcollector.game.challenge.data.instance.Challenge
import com.adsamcik.signalcollector.misc.extension.dpAsPx
import com.adsamcik.signalcollector.mock.useMock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class FragmentGame : Fragment(), IOnDemandView {
	private lateinit var listViewChallenges: ListView
	private lateinit var refreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

	private lateinit var colorManager: ColorManager

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val rootView = inflater.inflate(R.layout.fragment_game, container, false)

		listViewChallenges = rootView.findViewById(R.id.listview_challenges)

		refreshLayout = rootView.findViewById(R.id.swiperefresh_activites)
		refreshLayout.setColorSchemeResources(R.color.color_primary)
		refreshLayout.setProgressViewOffset(true, 0, 40.dpAsPx)
		//refreshLayout.setOnRefreshListener { this.updateData() }

		//updateData()

		val context = context!!
		listViewChallenges.adapter = ChallengesAdapter(context, arrayOf())
		//colorManager = ColorSupervisor.createColorManager(context)
		//colorManager.watchAdapterView(ColorView(listViewChallenges, 1, recursive = true, rootIsBackground = false))

		return rootView
	}

	private fun updateData() {
		val isRefresh = refreshLayout.isRefreshing
		val activity = activity!!
		val context = activity.applicationContext
		if (useMock) {
			val challenges = arrayOf(Challenge(Challenge.ChallengeType.AIsForAlphabet, "Hello world!", arrayOf("20", "h"), 0.5f, ChallengeDifficulties.MEDIUM),
					Challenge(Challenge.ChallengeType.AwfulExplorer, "Hello world!", arrayOf("6000"), 0f, ChallengeDifficulties.HARD),
					Challenge(Challenge.ChallengeType.Crowded, "Hello world!", arrayOf("50"), 1f, ChallengeDifficulties.EASY))

			challenges.forEach { it.generateTexts(context) }
			GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
				(listViewChallenges.adapter as ChallengesAdapter).updateData(challenges)
				refreshLayout.isRefreshing = false
			}
		} else {
			GlobalScope.launch(Dispatchers.Default, CoroutineStart.DEFAULT) {
				/*val challenges = ChallengeManager.getChallenges(activity, isRefresh)
				launch(Dispatchers.Main) {
					(listViewChallenges.adapter as ChallengesAdapter).updateData(challenges)
					refreshLayout.isRefreshing = false
				}*/
			}
		}
	}

	override fun onEnter(activity: FragmentActivity) {}

	override fun onLeave(activity: FragmentActivity) {}

	override fun onPermissionResponse(requestCode: Int, success: Boolean) {

	}

	private inner class ChallengesAdapter(mContext: Context, private var mDataSource: Array<Challenge>) : BaseAdapter(), IViewChange {
		private val mInflater: LayoutInflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

		fun updateData(challenges: Array<Challenge>) {
			this.mDataSource = challenges
			notifyDataSetInvalidated()
		}

		override var onViewChangedListener: ((View) -> Unit)? = null

		override fun getCount(): Int = mDataSource.size

		override fun getItem(i: Int): Any = mDataSource[i]

		override fun getItemId(i: Int): Long = i.toLong()

		override fun getView(i: Int, view: View?, viewGroup: ViewGroup): View {
			var fragmentView = view
			if (fragmentView == null)
				fragmentView = mInflater.inflate(R.layout.layout_challenge_small, viewGroup, false)

			val challenge = mDataSource[i]
			(fragmentView!!.findViewById<View>(R.id.challenge_title) as TextView).text = challenge.name
			(fragmentView.findViewById<View>(R.id.challenge_description) as TextView).text = challenge.descriptionTemplate

			val textViewDifficulty = fragmentView.findViewById<TextView>(R.id.challenge_difficulty)
			if (challenge.difficultyString == null)
				textViewDifficulty.visibility = View.GONE
			else
				textViewDifficulty.text = challenge.difficultyString

			(fragmentView.findViewById<View>(R.id.challenge_progress) as TextView).text = getString(R.string.challenge_progress, (challenge.progress * 100).toInt())
			val color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context!!, R.color.background_success), (challenge.progress * 254).roundToInt())
			fragmentView.setBackgroundColor(color)

			onViewChangedListener?.invoke(fragmentView)
			return fragmentView
		}
	}
}
