package com.adsamcik.signalcollector.statistics.detail.activity

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import com.adsamcik.recycler.AppendBehavior
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.signalcollector.common.activity.DetailActivity
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.misc.extension.*
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.statistics.R
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailType
import com.adsamcik.signalcollector.statistics.detail.recycler.StatsDetailAdapter
import com.adsamcik.signalcollector.statistics.detail.recycler.creator.InformationViewHolderCreator
import com.adsamcik.signalcollector.statistics.detail.recycler.creator.MapViewHolderCreator
import com.adsamcik.signalcollector.statistics.detail.recycler.data.InformationStatisticsData
import com.adsamcik.signalcollector.statistics.detail.recycler.data.MapStatisticsData
import com.adsamcik.signalcollector.tracker.data.session.TrackerSession
import com.google.android.gms.maps.MapsInitializer
import kotlinx.android.synthetic.main.activity_stats_detail.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class StatsDetailActivity : DetailActivity() {
	private lateinit var viewModel: ViewModel

	override fun onCreate(savedInstanceState: Bundle?) {
		titleBarLayer = 0
		super.onCreate(savedInstanceState)

		MapsInitializer.initialize(this)

		inflateContent(R.layout.activity_stats_detail)

		colorController.watchView(ColorView(root_stats_detail, 0))

		val sessionId = intent.getLongExtra(ARG_SESSION_ID, -1)

		if (sessionId <= 0L) throw IllegalArgumentException("Argument $ARG_SESSION_ID must be set with valid value!")

		viewModel = ViewModelProviders.of(this)[ViewModel::class.java].also { it.initialize(this, sessionId) }

		viewModel.run {
			session.observe(this@StatsDetailActivity) {
				if (it == null) {
					finish()
					return@observe
				}

				initializeSessionData(it)
			}
		}
	}

	private fun initializeSessionData(session: TrackerSession) {
		val resources = resources
		val lengthSystem = Preferences.getLengthSystem(this)

		//recycler.addItemDecoration(StatisticsDetailDecorator(16.dpAsPx, 0))
		recycler.layoutManager = LinearLayoutManager(this)

		recycler.adapter = StatsDetailAdapter().apply {
			registerType(StatisticDetailType.Information, InformationViewHolderCreator())
			registerType(StatisticDetailType.Map, MapViewHolderCreator())

			val data = mutableListOf(
					InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_directions_walk_black_24dp, R.string.stats_distance_on_foot, resources.formatDistance(session.distanceOnFootInM, 2, lengthSystem)),
					InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_shoe_print, R.string.stats_steps, session.steps.formatReadable()),
					InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_outline_directions_24px, R.string.stats_distance_total, resources.formatDistance(session.distanceInM, 2, lengthSystem)),
					InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_baseline_commute_24px, R.string.stats_distance_in_vehicle, resources.formatDistance(session.distanceInVehicleInM, 2, lengthSystem)))

			addAll(data.map { SortableAdapter.SortableData<StatisticDetailData>(it) })
			//todo add Wi-Fi and Cell

			GlobalScope.launch {
				val database = AppDatabase.getDatabase(this@StatsDetailActivity)
				val locations = database.locationDao().getAllBetween(session.start, session.end)
				if (locations.isNotEmpty()) {
					val sortableData = SortableAdapter.SortableData<StatisticDetailData>(MapStatisticsData(locations), AppendPriority(AppendBehavior.Start))
					GlobalScope.launch(Dispatchers.Main) {
						add(sortableData)
					}
				}
			}
		}

		colorController.watchAdapterView(ColorView(recycler, 0, rootIsBackground = false))

		val startDate = Date(session.start)
		val endDate = Date(session.end)
		val startCalendar = startDate.toCalendar()
		val endCalendar = endDate.toCalendar()
		val title = createTitle(startCalendar, "run")

		setTitle(title)

		date_time.text = formatRange(startCalendar, endCalendar)
	}

	//todo improve localization support
	private fun formatRange(start: Calendar, end: Calendar): String {
		val today = Calendar.getInstance().toDate()
		val startDate = start.time
		val endDate = end.time

		val timePattern = "hh:mm"

		return if ((startDate.time / com.adsamcik.signalcollector.common.Constants.DAY_IN_MILLISECONDS) == (endDate.time / com.adsamcik.signalcollector.common.Constants.DAY_IN_MILLISECONDS)) {
			val dateFormat = SimpleDateFormat("d MMMM", Locale.getDefault())
			val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())
			"${dateFormat.format(startDate)}, ${timeFormat.format(startDate)} - ${timeFormat.format(endDate)}"
		} else {
			val datePattern = if (start.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "d MMMM"
			else "d MMMM yyyy"

			val format = SimpleDateFormat("$datePattern $timePattern", Locale.getDefault())
			"${format.format(startDate)} - ${format.format(endDate)}"
		}
	}

	//Todo replace with new activity object once ready
	private fun createTitle(date: Calendar, activity: String): String {
		val hour = date[Calendar.HOUR_OF_DAY]
		val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(date.time).capitalize()
		return if (hour < 6 || hour > 22) {
			getString(R.string.stats_night, day, activity)
		} else if (hour < 12) {
			getString(R.string.stats_morning, day, activity)
		} else if (hour < 17) {
			getString(R.string.stats_afternoon, day, activity)
		} else {
			getString(R.string.stats_evening, day, activity)
		}
	}


	class ViewModel : androidx.lifecycle.ViewModel() {
		private var initialized = false
		lateinit var session: LiveData<TrackerSession>

		fun initialize(context: Context, sessionId: Long) {
			if (initialized) return
			initialized = true

			val database = AppDatabase.getDatabase(context)
			session = database.sessionDao().get(sessionId)
		}
	}

	companion object {
		const val ARG_SESSION_ID = "session_id"
	}
}
