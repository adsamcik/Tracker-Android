package com.adsamcik.signalcollector.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.widget.SwipeRefreshLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.UploadReportsActivity
import com.adsamcik.signalcollector.adapters.TableAdapter
import com.adsamcik.signalcollector.data.Stat
import com.adsamcik.signalcollector.data.StatData
import com.adsamcik.signalcollector.data.StatDay
import com.adsamcik.signalcollector.data.UploadStats
import com.adsamcik.signalcollector.enums.AppendBehavior
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.interfaces.ITabFragment
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.network.NetworkLoader
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Failure
import com.adsamcik.signalcollector.utility.Preferences
import com.adsamcik.signalcollector.utility.SnackMaker
import com.adsamcik.signalcollector.utility.Table

import com.adsamcik.signalcollector.utility.Constants.DAY_IN_MINUTES

class FragmentStats : Fragment(), ITabFragment {
    private var fragmentView: View? = null

    private var adapter: TableAdapter? = null

    private var refreshLayout: SwipeRefreshLayout? = null

    private val CARD_LIST_MARGIN = 16

    private var refreshingCount = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        fragmentView = inflater.inflate(R.layout.fragment_stats, container, false)

        val activity = activity

        if (adapter == null)
            adapter = TableAdapter(activity!!, CARD_LIST_MARGIN)

        Thread { DataStore.removeOldRecentUploads(activity!!) }.start()

        Preferences.checkStatsDay(activity)

        //weeklyStats.addToViewGroup(view.findViewById(R.id.statsLayout), hasRecentUpload ? 1 : 0, false, 0);

        refreshLayout = view!!.findViewById(R.id.statsSwipeRefresh)
        refreshLayout!!.setOnRefreshListener({ this.updateStats() })
        refreshLayout!!.setColorSchemeResources(R.color.color_primary)
        refreshLayout!!.setProgressViewOffset(true, 0, Assist.dpToPx(activity!!, 40))

        val listView = view!!.findViewById<ListView>(R.id.stats_list_view)
        listView.adapter = adapter
        updateStats()
        return view
    }

    private fun updateStats() {
        val activity = activity
        val appContext = activity!!.applicationContext
        val isRefresh = refreshLayout!!.isRefreshing

        adapter!!.clear()

        val r = activity.resources

        val us = DataStore.loadLastFromAppendableJsonArray<UploadStats>(activity, DataStore.RECENT_UPLOADS_FILE, UploadStats::class.java)
        if (us != null && Assist.getAgeInDays(us.time) < 30) {
            val lastUpload = UploadReportsActivity.GenerateTableForUploadStat(us, context!!, resources.getString(R.string.most_recent_upload), AppendBehavior.FirstFirst)
            lastUpload.addButton(getString(R.string.more_uploads)) { _ ->
                val intent = Intent(context, UploadReportsActivity::class.java)
                startActivity(intent)
            }
            adapter!!.add(lastUpload)
        }

        val weeklyStats = Table(4, false, CARD_LIST_MARGIN, AppendBehavior.FirstFirst)
        weeklyStats.title = r.getString(R.string.stats_weekly_title)
        val weekStats = Preferences.countStats(activity)
        weeklyStats.addData(r.getString(R.string.stats_weekly_minutes), weekStats.minutes.toString())
        weeklyStats.addData(r.getString(R.string.stats_weekly_uploaded), Assist.humanReadableByteCount(weekStats.uploaded, true))
        weeklyStats.addData(r.getString(R.string.stats_weekly_collected_location), weekStats.locations.toString())
        weeklyStats.addData(r.getString(R.string.stats_weekly_collected_wifi), weekStats.wifi.toString())
        weeklyStats.addData(r.getString(R.string.stats_weekly_collected_cell), weekStats.cell.toString())
        adapter!!.add(weeklyStats)

        refreshingCount = 2
        Handler().postDelayed({
            if (refreshingCount > 0)
                activity.runOnUiThread { refreshLayout!!.isRefreshing = true }
        }, 100)

        NetworkLoader.request<Array<Stat>>(Network.URL_GENERAL_STATS, if (isRefresh) 0 else DAY_IN_MINUTES, context!!, Preferences.PREF_GENERAL_STATS, Array<Stat>::class.java) { state, value -> handleResponse(activity, state, value, AppendBehavior.FirstLast) }

        NetworkLoader.request<Array<Stat>>(Network.URL_STATS, if (isRefresh) 0 else DAY_IN_MINUTES, context!!, Preferences.PREF_STATS, Array<Stat>::class.java) { state, value -> handleResponse(activity, state, value, AppendBehavior.Any) }

        if (Signin.getUserID(appContext) != null) {
            refreshingCount++
            NetworkLoader.requestSigned<Array<Stat>>(Network.URL_USER_STATS, if (isRefresh) 0 else DAY_IN_MINUTES, appContext, Preferences.PREF_USER_STATS, Array<Stat>::class.java) { state, value ->
                if (value != null && value.size == 1 && value[0].name.isEmpty())
                    value[0] = Stat(appContext.getString(R.string.your_stats), value[0].type, value[0].showPosition, value[0].data)
                handleResponse(activity, state, value, AppendBehavior.First)
            }
        }
    }

    private fun handleResponse(activity: Activity, state: NetworkLoader.Source, value: Array<Stat>?, @AppendBehavior appendBehavior: Int) {
        if (!state.isSuccess)
            SnackMaker(activity).showSnackbar(state.toString(activity))
        refreshingCount--
        if (state.isDataAvailable)
            activity.runOnUiThread {
                addStatsTable(value!!, appendBehavior)
                adapter!!.sort()
                if (refreshingCount == 0 && refreshLayout != null)
                    refreshLayout!!.isRefreshing = false
            }
    }

    /**
     * Generates tables from list of stats
     *
     * @param stats stats
     */
    private fun addStatsTable(stats: Array<Stat>, @AppendBehavior appendBehavior: Int) {
        for (s in stats) {
            if (s.data != null) {
                val table = Table(s.data.size, s.showPosition, CARD_LIST_MARGIN, appendBehavior)
                table.title = s.name
                for (y in s.data.indices) {
                    val sd = s.data[y]
                    table.addData(sd.id, sd.value)
                }
                adapter!!.add(table)
            }
        }
    }

    override fun onEnter(activity: FragmentActivity, fabOne: FloatingActionButton, fabTwo: FloatingActionButton): Failure<String> {
        adapter = TableAdapter(activity, 16)
        return Failure()
    }

    override fun onLeave(activity: FragmentActivity) {
        if (refreshLayout != null && refreshLayout!!.isRefreshing)
            refreshLayout!!.isRefreshing = false
    }

    override fun onPermissionResponse(requestCode: Int, success: Boolean) {

    }

    override fun onHomeAction() {
        if (view != null) {
            (view!!.findViewById<View>(R.id.stats_list_view) as ListView).smoothScrollToPosition(0)
        }
    }

}

