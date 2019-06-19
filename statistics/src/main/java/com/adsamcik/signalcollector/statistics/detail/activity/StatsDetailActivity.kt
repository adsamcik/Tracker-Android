package com.adsamcik.signalcollector.statistics.detail.activity

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.AppendBehavior
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.signalcollector.common.Constants
import com.adsamcik.signalcollector.common.activity.DetailActivity
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.data.LengthUnit
import com.adsamcik.signalcollector.common.data.Location
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import com.adsamcik.signalcollector.common.misc.extension.*
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeAdapter
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeData
import com.adsamcik.signalcollector.statistics.R
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailType
import com.adsamcik.signalcollector.statistics.detail.recycler.creator.InformationViewHolderCreator
import com.adsamcik.signalcollector.statistics.detail.recycler.creator.LineChartViewHolderCreator
import com.adsamcik.signalcollector.statistics.detail.recycler.creator.MapViewHolderCreator
import com.adsamcik.signalcollector.statistics.detail.recycler.data.InformationStatisticsData
import com.adsamcik.signalcollector.statistics.detail.recycler.data.LineChartStatisticsData
import com.adsamcik.signalcollector.statistics.detail.recycler.data.MapStatisticsData
import com.github.mikephil.charting.data.Entry
import com.google.android.gms.maps.MapsInitializer
import com.google.android.play.core.splitcompat.SplitCompat
import kotlinx.android.synthetic.main.activity_stats_detail.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

typealias StatsDetailAdapter = MultiTypeAdapter<StatisticDetailType, MultiTypeData<StatisticDetailType>>

class StatsDetailActivity : DetailActivity() {
	private lateinit var viewModel: ViewModel

	override fun attachBaseContext(newBase: Context?) {
		super.attachBaseContext(newBase)
		SplitCompat.install(this)
	}

	override fun onConfigure(configuration: Configuration) {
		configuration.elevation = 0
		configuration.titleBarLayer = 1
	}

	override fun onCreate(savedInstanceState: Bundle?) {
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
		//recycler.addItemDecoration(StatisticsDetailDecorator(16.dpAsPx, 0))
		val layoutManager = LinearLayoutManager(this)
		recycler.layoutManager = layoutManager

		(recycler.itemAnimator as? DefaultItemAnimator)?.apply {
			supportsChangeAnimations = false
		}

		val adapter = StatsDetailAdapter(colorController).apply {
			registerType(StatisticDetailType.Information, InformationViewHolderCreator())
			registerType(StatisticDetailType.Map, MapViewHolderCreator())
			registerType(StatisticDetailType.LineChart, LineChartViewHolderCreator())
			//todo add Wi-Fi and Cell

			addBasicStats(session, this)
			addLocationStats(session, this@apply)
		}

		adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
			override fun onChanged() {}

			override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {}

			override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {}

			override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
				if (layoutManager.findFirstCompletelyVisibleItemPosition() == 0)
					recycler.smoothScrollToPosition(0)
			}

			override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {}

			override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {}
		})

		recycler.adapter = adapter

		colorController.watchRecyclerView(ColorView(recycler, 0))

		val startDate = Date(session.start)
		val endDate = Date(session.end)
		val startCalendar = startDate.toCalendar()
		val endCalendar = endDate.toCalendar()
		val title = createTitle(startCalendar, "run")

		setTitle(title)

		date_time.text = formatRange(startCalendar, endCalendar)
	}

	private fun addBasicStats(session: TrackerSession, adapter: StatsDetailAdapter) {
		val resources = resources
		val lengthSystem = Preferences.getLengthSystem(this)


		val data = mutableListOf(
				InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_outline_directions_24px, R.string.stats_distance_total, resources.formatDistance(session.distanceInM, 2, lengthSystem)),
				InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_directions_walk_white_24dp, R.string.stats_distance_on_foot, resources.formatDistance(session.distanceOnFootInM, 2, lengthSystem)),
				InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_shoe_print, R.string.stats_steps, session.steps.formatReadable()),
				InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_baseline_commute_24px, R.string.stats_distance_in_vehicle, resources.formatDistance(session.distanceInVehicleInM, 2, lengthSystem)))

		adapter.addAll(data.map { SortableAdapter.SortableData<StatisticDetailData>(it) })
	}

	private fun addLocationStats(session: TrackerSession, adapter: StatsDetailAdapter) {
		launch(Dispatchers.Default) {
			val database = AppDatabase.getDatabase(this@StatsDetailActivity)
			val locations = database.locationDao().getAllBetween(session.start, session.end)
			if (locations.isNotEmpty()) {
				addLocationMap(locations, adapter)
				launch(Dispatchers.Default) { addElevationStats(locations, adapter) }
				launch(Dispatchers.Default) { addSpeedStats(locations, adapter) }
			}
		}
	}

	private fun addLocationMap(locations: List<DatabaseLocation>, adapter: StatsDetailAdapter) {
		val locationData = SortableAdapter.SortableData<StatisticDetailData>(MapStatisticsData(locations), AppendPriority(AppendBehavior.Start))
		launch(Dispatchers.Main) {
			adapter.add(locationData)
		}
	}

	private fun addSpeedStats(locations: List<DatabaseLocation>, adapter: StatsDetailAdapter) {
		if (locations.isEmpty()) return

		var maxSpeed = 0.0
		var speedSum = 0.0
		var timeSum = 0L

		var previous = locations[0]
		var previousAltitude = previous.altitude
		for (i in 1 until locations.size) {
			val current = locations[i]
			val currentAltitude = current.altitude
			val timeDifference = current.time - previous.time
			val secondsElapsed = timeDifference.toDouble() / Constants.SECOND_IN_MILLISECONDS.toDouble()

			if (secondsElapsed <= 70.0) {
				val distance = if (currentAltitude != null && previousAltitude != null) {
					Location.distance(previous.latitude, previous.longitude, previousAltitude, current.latitude, current.longitude, currentAltitude, LengthUnit.Meter)
				} else {
					Location.distance(previous.latitude, previous.longitude, current.latitude, current.longitude, LengthUnit.Meter)
				}

				val speed = distance / secondsElapsed

				if (speed > maxSpeed) maxSpeed = speed
				speedSum += speed
				timeSum += timeDifference
			}

			previous = current
			previousAltitude = currentAltitude
		}

		val avgSpeed = speedSum / (timeSum / Constants.SECOND_IN_MILLISECONDS)
		val lengthSystem = Preferences.getLengthSystem(this)
		val speedFormat = Preferences.getSpeedFormat(this)
		val dataList = listOf(
				InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_speedometer,
						R.string.stats_max_speed,
						resources.formatSpeed(maxSpeed, 1, lengthSystem, speedFormat)),

				InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_speedometer,
						R.string.stats_avg_speed,
						resources.formatSpeed(avgSpeed, 1, lengthSystem, speedFormat))
		)

		addStatisticsData(adapter, dataList, AppendPriority(AppendBehavior.Any))
	}

	private fun addElevationStats(locations: List<DatabaseLocation>, adapter: StatsDetailAdapter) {
		val firstTime = locations.first().time
		var descended = 0.0
		var ascended = 0.0
		var previousAltitude = locations.first { it.altitude != null }.altitude!!

		var maxAltitude = previousAltitude
		var minAltitude = previousAltitude

		val elevationList = locations.mapNotNull {
			val altitude = it.altitude ?: return@mapNotNull null

			if (altitude > maxAltitude) {
				maxAltitude = altitude
			} else if (altitude < minAltitude) {
				minAltitude = altitude
			}

			val diff = altitude - previousAltitude
			if (diff > 0) {
				ascended += diff
			} else {
				descended -= diff
			}
			previousAltitude = altitude

			Entry((it.time - firstTime).toFloat(), altitude.toFloat())
		}

		val resources = resources
		val lengthSystem = Preferences.getLengthSystem(this)

		val altitudeStatisticsList = listOf(
				InformationStatisticsData(R.drawable.arrow_top_right_bold_outline, R.string.stats_ascended, resources.formatDistance(ascended, 1, lengthSystem)),
				InformationStatisticsData(R.drawable.arrow_bottom_right_bold_outline, R.string.stats_descended, resources.formatDistance(descended, 1, lengthSystem)),
				InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_outline_terrain, R.string.stats_elevation_max, resources.formatDistance(maxAltitude, 1, lengthSystem)),
				InformationStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_outline_terrain, R.string.stats_elevation_min, resources.formatDistance(minAltitude, 1, lengthSystem)),
				LineChartStatisticsData(com.adsamcik.signalcollector.common.R.drawable.ic_outline_terrain, R.string.stats_elevation, elevationList)
		)

		addStatisticsData(adapter, altitudeStatisticsList, AppendPriority(AppendBehavior.Any))
	}

	private fun addStatisticsData(adapter: StatsDetailAdapter, data: List<StatisticDetailData>, appendPriority: AppendPriority) {
		launch(Dispatchers.Main) {
			adapter.addAll(data.map { SortableAdapter.SortableData(it, appendPriority) })
		}
	}

	//todo improve localization support
	private fun formatRange(start: Calendar, end: Calendar): String {
		val today = Calendar.getInstance().toDate()
		val startDate = start.time
		val endDate = end.time

		val timePattern = "hh:mm"

		return if ((startDate.time / Constants.DAY_IN_MILLISECONDS) == (endDate.time / Constants.DAY_IN_MILLISECONDS)) {
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
			session = database.sessionDao().getLive(sessionId)
		}
	}

	companion object {
		const val ARG_SESSION_ID = "session_id"
	}
}
