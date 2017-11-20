package com.adsamcik.signalcollector.fragments

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
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.data.Challenge
import com.adsamcik.signalcollector.interfaces.ITabFragment
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.ChallengeManager
import com.adsamcik.signalcollector.utility.Failure
import com.adsamcik.signalcollector.utility.SnackMaker

class FragmentActivities : Fragment(), ITabFragment {
    private var listViewChallenges: ListView? = null
    private var refreshLayout: SwipeRefreshLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val rootView = inflater.inflate(R.layout.fragment_activities, container, false)
        val activity = activity

        listViewChallenges = rootView.findViewById(R.id.listview_challenges)

        refreshLayout = rootView as SwipeRefreshLayout
        refreshLayout!!.setColorSchemeResources(R.color.color_primary)
        refreshLayout!!.setProgressViewOffset(true, 0, Assist.dpToPx(activity!!, 40))
        refreshLayout!!.setOnRefreshListener({ this.updateData() })

        updateData()

        return rootView
    }

    private fun updateData() {
        val isRefresh = refreshLayout != null && refreshLayout!!.isRefreshing
        val activity = activity
        val context = activity!!.applicationContext
        ChallengeManager.getChallenges(activity, isRefresh) { source, challenges ->
            if (!source.isSuccess)
                SnackMaker(activity).showSnackbar(R.string.error_connection_failed)
            else {
                activity.runOnUiThread { listViewChallenges!!.adapter = ChallengesAdapter(context, challenges!!) }
            }
            activity.runOnUiThread { refreshLayout!!.isRefreshing = false }
        }
    }

    override fun onEnter(activity: FragmentActivity, fabOne: FloatingActionButton, fabTwo: FloatingActionButton): Failure<String> =
            Failure()

    override fun onLeave(activity: FragmentActivity) {

    }

    override fun onPermissionResponse(requestCode: Int, success: Boolean) {

    }

    override fun onHomeAction() {

    }

    private inner class ChallengesAdapter(mContext: Context, private val mDataSource: Array<Challenge>) : BaseAdapter() {
        private val mInflater: LayoutInflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        override fun getCount(): Int = mDataSource.size

        override fun getItem(i: Int): Any = mDataSource[i]

        override fun getItemId(i: Int): Long = i.toLong()

        override fun getView(i: Int, view: View?, viewGroup: ViewGroup): View {
            var view = view
            if (view == null)
                view = mInflater.inflate(R.layout.layout_challenge_small, viewGroup, false)

            val challenge = mDataSource[i]
            (view!!.findViewById<View>(R.id.challenge_title) as TextView).text = challenge.title
            (view.findViewById<View>(R.id.challenge_description) as TextView).text = challenge.description

            val textViewDifficulty = view.findViewById<TextView>(R.id.challenge_difficulty)
            if (challenge.difficultyString == null)
                textViewDifficulty.visibility = View.GONE
            else
                textViewDifficulty.text = challenge.difficultyString

            (view.findViewById<View>(R.id.challenge_progress) as TextView).text = getString(R.string.challenge_progress, (challenge.progress * 100).toInt())

            val color = ContextCompat.getColor(context!!, R.color.background_success) and (Integer.MAX_VALUE shr 8) or ((challenge.progress * 255).toInt() shl 24)
            view.setBackgroundColor(color)
            return view
        }
    }
}
