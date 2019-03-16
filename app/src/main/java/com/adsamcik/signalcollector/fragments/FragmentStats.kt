package com.adsamcik.signalcollector.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.adapters.ChangeTableAdapter
import com.adsamcik.signalcollector.data.Stat
import com.adsamcik.signalcollector.data.StatData
import com.adsamcik.signalcollector.extensions.dpAsPx
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Preferences
import com.adsamcik.table.AppendBehaviors
import com.adsamcik.table.Table
import com.adsamcik.table.TableAdapter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class FragmentStats : Fragment(), IOnDemandView {
    private lateinit var fragmentView: View

    private var adapter: TableAdapter? = null

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private var refreshingCount = 0

    private lateinit var colorManager: ColorManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val activity = activity!!
        colorManager = ColorSupervisor.createColorManager(activity)

        val fragmentView = inflater.inflate(R.layout.fragment_stats, container, false)


        adapter = ChangeTableAdapter(activity, CARD_LIST_MARGIN, activity.packageManager.getActivityInfo(activity.componentName, 0).themeResource)

        Thread { DataStore.removeOldRecentUploads(activity) }.start()

        Preferences.checkStatsDay(activity)

        //weeklyStats.addToViewGroup(view.findViewById(R.id.statsLayout), hasRecentUpload ? 1 : 0, false, 0);

        swipeRefreshLayout = fragmentView.findViewById(R.id.swiperefresh_stats)
        swipeRefreshLayout.setOnRefreshListener { this.updateStats() }
        swipeRefreshLayout.setColorSchemeResources(R.color.color_primary)
        swipeRefreshLayout.setProgressViewOffset(true, 0, 40.dpAsPx)

        val listView = fragmentView!!.findViewById<ListView>(R.id.listview_stats)
        listView.setRecyclerListener { }
        listView.adapter = adapter
        updateStats()

        this.fragmentView = fragmentView

        colorManager.watchAdapterView(ColorView(listView, 1, recursive = true, rootIsBackground = true))

        return fragmentView
    }

    override fun onDestroyView() {
        ColorSupervisor.recycleColorManager(colorManager)
        super.onDestroyView()
    }

    private fun updateStats() {
        val activity = activity
        val appContext = activity!!.applicationContext
        val isRefresh = swipeRefreshLayout.isRefreshing

        adapter!!.clear()

        val r = activity.resources


        val weeklyStats = Table(4, false, CARD_LIST_MARGIN, AppendBehaviors.FirstFirst)
        weeklyStats.title = r.getString(R.string.stats_weekly_title)
        val weekStats = Preferences.countStats(activity)
        weeklyStats.addData(r.getString(R.string.stats_weekly_minutes), weekStats.minutes.toString())
        weeklyStats.addData(r.getString(R.string.stats_weekly_uploaded), Assist.humanReadableByteCount(weekStats.uploaded, true))
        weeklyStats.addData(r.getString(R.string.stats_weekly_collected_location), weekStats.locations.toString())
        weeklyStats.addData(r.getString(R.string.stats_weekly_collected_wifi), weekStats.wifi.toString())
        weeklyStats.addData(r.getString(R.string.stats_weekly_collected_cell), weekStats.cell.toString())
        adapter!!.add(weeklyStats)


        if (useMock) {
            generateMockData()
        } else {
            refreshingCount = 2
            //new stat loading
        }

        if (refreshingCount > 0) {
            GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
                swipeRefreshLayout.isRefreshing = true
            }
        }
    }

    private fun handleResponse(context: Context, value: Array<Stat>, @AppendBehaviors.AppendBehavior appendBehavior: Int) {
        refreshingCount--
        swipeRefreshLayout.post {
            if (refreshingCount == 0)
                swipeRefreshLayout.isRefreshing = false
        }

        GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
            addStatsTable(value, appendBehavior)
            adapter!!.sort()
        }
    }

    private fun generateMockData() {
        addStatsTable(generateMockStatList(), AppendBehaviors.Any)
    }

    private fun generateMockStatList(): Array<Stat> {
        val list = ArrayList<Stat>()
        for (i in 1..10) {
            list.add(generateMockStat(i))
        }
        return list.toTypedArray()
    }

    private fun generateMockStat(index: Int) = Stat("Mock $index", "donut", false, generateStatData(index))

    private fun generateStatData(index: Int): List<StatData> {
        val list = ArrayList<StatData>()
        for (i in 1..index) {
            list.add(StatData("Title $i", i.toString()))
        }
        return list
    }

    /**
     * Generates tables from list of stats
     *
     * @param stats stats
     */
    private fun addStatsTable(stats: Array<Stat>, @AppendBehaviors.AppendBehavior appendBehavior: Int) {
        for (s in stats) {
            val table = Table(s.data.size, s.showPosition, CARD_LIST_MARGIN, appendBehavior)
            table.title = s.name
            s.data.indices
                    .asSequence()
                    .map { s.data[it] }
                    .forEach { table.addData(it.id, it.value) }
            adapter!!.add(table)
        }
    }


    override fun onEnter(activity: FragmentActivity) {

    }

    override fun onLeave(activity: FragmentActivity) {
    }


    override fun onPermissionResponse(requestCode: Int, success: Boolean) {

    }

    companion object {
        private const val CARD_LIST_MARGIN = 16
    }
}

