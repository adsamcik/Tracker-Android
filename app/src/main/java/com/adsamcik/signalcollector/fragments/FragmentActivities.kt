package com.adsamcik.signalcollector.fragments

import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.data.Challenge
import com.adsamcik.signalcollector.enums.ChallengeDifficulties
import com.adsamcik.signalcollector.extensions.dpAsPx
import com.adsamcik.signalcollector.interfaces.IViewChange
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.utility.ChallengeManager
import com.adsamcik.signalcollector.utility.SnackMaker
import kotlinx.android.synthetic.main.fragment_activities.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import kotlin.math.roundToInt

class FragmentActivities : androidx.fragment.app.Fragment(), IOnDemandView {
    private lateinit var listViewChallenges: ListView
    private lateinit var refreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

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
        colorManager.watchAdapterView(ColorView(listViewChallenges, 1, true, false))

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
            launch(UI) {
                (listViewChallenges.adapter as ChallengesAdapter).updateData(challenges)
                refreshLayout.isRefreshing = false
            }
        } else {
            launch {
                val (source, challenges) = ChallengeManager.getChallenges(activity, isRefresh)
                if (!source.success)
                    SnackMaker(activities_root).showSnackbar(R.string.error_connection_failed)
                else {
                    launch(UI) {
                        (listViewChallenges.adapter as ChallengesAdapter).updateData(challenges!!)
                    }
                }
                launch(UI) { refreshLayout.isRefreshing = false }
            }
        }
    }

    override fun onEnter(activity: AppCompatActivity) {}

    override fun onLeave(activity: AppCompatActivity) {}

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
            (fragmentView!!.findViewById<View>(R.id.challenge_title) as TextView).text = challenge.title
            (fragmentView.findViewById<View>(R.id.challenge_description) as TextView).text = challenge.description

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
