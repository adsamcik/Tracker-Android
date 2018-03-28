package com.adsamcik.signalcollector.fragments

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.content.ContextCompat
import android.support.v4.widget.SwipeRefreshLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.data.Challenge
import com.adsamcik.signalcollector.enums.ChallengeDifficulty
import com.adsamcik.signalcollector.interfaces.ITabFragment
import com.adsamcik.signalcollector.interfaces.IViewChange
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.uitools.dpAsPx
import com.adsamcik.signalcollector.utility.ChallengeManager
import com.adsamcik.signalcollector.utility.SnackMaker
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch

class FragmentNewActivities : Fragment(), ITabFragment, IOnDemandView {
    private lateinit var listViewChallenges: ListView
    private lateinit var refreshLayout: SwipeRefreshLayout

    private lateinit var colorManager: ColorManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val rootView = inflater.inflate(R.layout.fragment_activities, container, false)

        listViewChallenges = rootView.findViewById(R.id.listview_challenges)

        refreshLayout = rootView.findViewById(R.id.swiperefresh_activites)
        refreshLayout.setColorSchemeResources(R.color.color_primary)
        refreshLayout.setProgressViewOffset(true, 0, 40.dpAsPx)
        refreshLayout.setOnRefreshListener({ this.updateData() })

        updateData()

        val context = context!!
        listViewChallenges.adapter = ChallengesAdapter(context, arrayOf())
        colorManager = ColorSupervisor.createColorManager(context)
        colorManager.watchRecycler(ColorView(listViewChallenges, 1, true, false))

        return rootView
    }

    private fun updateData() {
        val isRefresh = refreshLayout.isRefreshing
        val activity = activity!!
        val context = activity.applicationContext
        if (useMock) {
            val challenges = arrayOf(Challenge(Challenge.ChallengeType.AIsForAlphabet, "Hello world!", arrayOf("20", "h"), 0.5f, ChallengeDifficulty.MEDIUM),
                    Challenge(Challenge.ChallengeType.AwfulExplorer, "Hello world!", arrayOf("6000"), 0f, ChallengeDifficulty.HARD),
                    Challenge(Challenge.ChallengeType.Crowded, "Hello world!", arrayOf("50"), 1f, ChallengeDifficulty.EASY))

            challenges.forEach { it.generateTexts(context) }
            launch(UI) {
                (listViewChallenges.adapter as ChallengesAdapter).updateData(challenges)
                refreshLayout.isRefreshing = false
            }
        } else {
            launch {
                val (source, challenges) = ChallengeManager.getChallenges(activity, isRefresh)
                if (!source.success)
                    SnackMaker(activity).showSnackbar(R.string.error_connection_failed)
                else {
                    launch(UI) {
                        (listViewChallenges.adapter as ChallengesAdapter).updateData(challenges!!)
                    }
                }
                launch(UI) { refreshLayout.isRefreshing = false }
            }
        }
    }

    override fun onEnter(activity: FragmentActivity, fabOne: FloatingActionButton, fabTwo: FloatingActionButton) {}

    override fun onLeave(activity: FragmentActivity) {}

    override fun onEnter(activity: Activity) {}

    override fun onLeave(activity: Activity) {}

    override fun onPermissionResponse(requestCode: Int, success: Boolean) {

    }

    override fun onHomeAction() {

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
            (fragmentView!!.findViewById<View>(R.id.challenge_title) as TextView).text = challenge.title
            (fragmentView.findViewById<View>(R.id.challenge_description) as TextView).text = challenge.description

            val textViewDifficulty = fragmentView.findViewById<TextView>(R.id.challenge_difficulty)
            if (challenge.difficultyString == null)
                textViewDifficulty.visibility = View.GONE
            else
                textViewDifficulty.text = challenge.difficultyString

            (fragmentView.findViewById<View>(R.id.challenge_progress) as TextView).text = getString(R.string.challenge_progress, (challenge.progress * 100).toInt())

            val color = ContextCompat.getColor(context!!, R.color.background_success) and (Integer.MAX_VALUE shr 8) or ((challenge.progress * 255).toInt() shl 24)
            fragmentView.setBackgroundColor(color)

            onViewChangedListener?.invoke(fragmentView)
            return fragmentView
        }
    }
}
