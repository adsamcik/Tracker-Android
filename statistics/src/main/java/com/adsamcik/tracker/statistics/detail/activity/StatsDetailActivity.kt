package com.adsamcik.tracker.statistics.detail.activity

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.adapter.implementation.sortable.AppendBehavior
import com.adsamcik.recycler.adapter.implementation.sortable.AppendPriority
import com.adsamcik.recycler.adapter.implementation.sortable.SortableAdapter
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.activity.DetailActivity
import com.adsamcik.tracker.common.data.LengthUnit
import com.adsamcik.tracker.common.data.Location
import com.adsamcik.tracker.common.data.NativeSessionActivity
import com.adsamcik.tracker.common.data.SessionActivity
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.database.data.DatabaseLocation
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.common.extension.formatDistance
import com.adsamcik.tracker.common.extension.formatReadable
import com.adsamcik.tracker.common.extension.formatSpeed
import com.adsamcik.tracker.common.extension.observe
import com.adsamcik.tracker.common.extension.requireValue
import com.adsamcik.tracker.common.extension.toCalendar
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.common.recycler.multitype.MultiTypeAdapter
import com.adsamcik.tracker.common.recycler.multitype.MultiTypeData
import com.adsamcik.tracker.common.style.RecyclerStyleView
import com.adsamcik.tracker.common.style.StyleView
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.StatsFormat
import com.adsamcik.tracker.statistics.detail.SessionActivitySelection
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDetailType
import com.adsamcik.tracker.statistics.detail.recycler.creator.InformationViewHolderCreator
import com.adsamcik.tracker.statistics.detail.recycler.creator.LineChartViewHolderCreator
import com.adsamcik.tracker.statistics.detail.recycler.creator.MapViewHolderCreator
import com.adsamcik.tracker.statistics.detail.recycler.data.InformationStatisticsData
import com.adsamcik.tracker.statistics.detail.recycler.data.LineChartStatisticsData
import com.adsamcik.tracker.statistics.detail.recycler.data.MapStatisticsData
import com.github.mikephil.charting.data.Entry
import com.google.android.gms.maps.MapsInitializer
import com.google.android.play.core.splitcompat.SplitCompat
import kotlinx.android.synthetic.main.activity_stats_detail.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

		val rootContentView = inflateContent<ViewGroup>(R.layout.activity_stats_detail)

		styleController.watchView(
				StyleView(
						rootContentView.findViewById(R.id.root_stats_detail),
						0
				)
		)

		val sessionId = intent.getLongExtra(ARG_SESSION_ID, -1)

		if (sessionId <= 0L) throw IllegalArgumentException("Argument $ARG_SESSION_ID must be set with valid value!")

		viewModel = ViewModelProvider(this)[ViewModel::class.java].also {
			launch(Dispatchers.Default) {
				it.initialize(this@StatsDetailActivity, sessionId)
			}
		}

		viewModel.run {
			session.observe(this@StatsDetailActivity) {
				if (it == null) {
					finish()
					return@observe
				}

				initializeSessionData(it)
			}
		}



		addAction(com.adsamcik.tracker.common.R.drawable.ic_baseline_edit, R.string.edit_session,
		          View.OnClickListener {
			          if (add_item_layout.isVisible) {
				          add_item_layout.visibility = View.GONE
				          header_root.updatePadding(top = 0)
			          } else {
				          add_item_layout.visibility = View.VISIBLE
				          header_root.updatePadding(top = 16.dp)
				          findViewById<View>(
						          R.id.button_change_activity
				          ).setOnClickListener { showActivitySelectionDialog() }
				          findViewById<View>(R.id.button_remove_session).setOnClickListener { removeSession() }
			          }
		          })
	}

	private fun removeSession() {
		launch(Dispatchers.Default) {
			val dao = AppDatabase.database(this@StatsDetailActivity).sessionDao()
			dao.delete(viewModel.session.requireValue)
			finish()
		}
	}

	private fun showActivitySelectionDialog() {
		launch(Dispatchers.Default) {
			val activities = SessionActivity.getAll(this@StatsDetailActivity)
			SessionActivitySelection(
					this@StatsDetailActivity,
					activities,
					viewModel.session.requireValue
			)
					.showActivitySelectionDialog()
		}
	}

	@MainThread
	private fun initializeSessionData(session: TrackerSession) {
		//recycler.addItemDecoration(StatisticsDetailDecorator(16.dpAsPx, 0))
		val layoutManager = LinearLayoutManager(this)
		recycler.layoutManager = layoutManager

		(recycler.itemAnimator as? DefaultItemAnimator)?.apply {
			supportsChangeAnimations = false
		}

		val adapter = StatsDetailAdapter(styleController).apply {
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
				if (layoutManager.findFirstCompletelyVisibleItemPosition() == 0) {
					recycler.smoothScrollToPosition(0)
				}
			}

			override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {}

			override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {}
		})

		recycler.adapter = adapter

		styleController.watchRecyclerView(RecyclerStyleView(recycler, 0))

		val endCalendar = Date(session.end).toCalendar()
		val startCalendar = Date(session.start).toCalendar()

		setTitle(session)

		date_time.text = StatsFormat.formatRange(startCalendar, endCalendar)
	}

	private fun setTitle(session: TrackerSession) {
		val startCalendar = Date(session.start).toCalendar()
		val endCalendar = Date(session.end).toCalendar()

		val activityId = session.sessionActivityId

		launch(Dispatchers.Default) {
			val sessionActivity = when {
				activityId == null -> null
				activityId < -1 -> NativeSessionActivity.values().find { it.id == activityId }?.getSessionActivity(
						this@StatsDetailActivity
				)
				else -> if (activityId == 0L || activityId == -1L) {
					null
				} else {
					val activityDao = AppDatabase.database(this@StatsDetailActivity)
							.activityDao()
					activityDao.get(activityId)
				}
			} ?: SessionActivity.UNKNOWN

			val title = StatsFormat.createTitle(
					this@StatsDetailActivity,
					startCalendar,
					endCalendar,
					sessionActivity
			)

			val drawable = sessionActivity.getIcon(this@StatsDetailActivity)

			launch(Dispatchers.Main) {
				setTitle(title)
				activity.setImageDrawable(drawable)
			}
		}
	}


	private fun addBasicStats(session: TrackerSession, adapter: StatsDetailAdapter) {
		val resources = resources
		val lengthSystem = Preferences.getLengthSystem(this)


		val data = mutableListOf(
				InformationStatisticsData(
						com.adsamcik.tracker.common.R.drawable.ic_outline_directions_24px,
						R.string.stats_distance_total,
						resources.formatDistance(session.distanceInM, 2, lengthSystem)
				),
				InformationStatisticsData(
						com.adsamcik.tracker.common.R.drawable.ic_directions_walk_white_24dp,
						R.string.stats_distance_on_foot,
						resources.formatDistance(session.distanceOnFootInM, 2, lengthSystem)
				),
				InformationStatisticsData(
						com.adsamcik.tracker.common.R.drawable.ic_shoe_print,
						R.string.stats_steps, session.steps.formatReadable()
				),
				InformationStatisticsData(
						com.adsamcik.tracker.common.R.drawable.ic_baseline_commute,
						R.string.stats_distance_in_vehicle,
						resources.formatDistance(session.distanceInVehicleInM, 2, lengthSystem)
				)
		)

		adapter.addAll(data.map { SortableAdapter.SortableData<StatisticDetailData>(it) })
	}

	private fun addLocationStats(session: TrackerSession, adapter: StatsDetailAdapter) {
		launch(Dispatchers.Default) {
			val database = AppDatabase.database(this@StatsDetailActivity)
			val locations = database.locationDao().getAllBetween(session.start, session.end)
			if (locations.isNotEmpty()) {
				addLocationMap(locations, adapter)
				launch(Dispatchers.Default) { addElevationStats(locations, adapter) }
				launch(Dispatchers.Default) { addSpeedStats(locations, adapter) }
			}
		}
	}

	private fun addLocationMap(locations: List<DatabaseLocation>, adapter: StatsDetailAdapter) {
		val locationData = SortableAdapter.SortableData<StatisticDetailData>(
				MapStatisticsData(locations),
				AppendPriority(AppendBehavior.Start)
		)
		launch(Dispatchers.Main) {
			adapter.add(locationData)
		}
	}

	private fun addSpeedStats(locations: List<DatabaseLocation>, adapter: StatsDetailAdapter) {
		if (locations.isEmpty()) return

		var maxSpeed = 0.0
		var speedSum = 0.0
		var speedCount = 0

		var previous = locations[0]
		var previousAltitude = previous.altitude
		for (i in 1 until locations.size) {
			val current = locations[i]
			val currentAltitude = current.altitude
			val timeDifference = current.time - previous.time
			val secondsElapsed = timeDifference.toDouble() / Time.SECOND_IN_MILLISECONDS.toDouble()

			if (secondsElapsed <= 70.0) {
				val speed: Double

				val recordedSpeed = current.location.speed
				speed = if (recordedSpeed != null) {
					recordedSpeed.toDouble()
				} else {
					val distance = if (currentAltitude != null && previousAltitude != null) {
						Location.distance(
								previous.latitude,
								previous.longitude,
								previousAltitude,
								current.latitude,
								current.longitude,
								currentAltitude,
								LengthUnit.Meter
						)
					} else {
						Location.distance(
								previous.latitude,
								previous.longitude,
								current.latitude,
								current.longitude,
								LengthUnit.Meter
						)
					}

					distance / secondsElapsed
				}

				if (speed > maxSpeed) maxSpeed = speed
				speedSum += speed
				speedCount++
			}

			previous = current
			previousAltitude = currentAltitude
		}

		val avgSpeed = speedSum / speedCount
		val lengthSystem = Preferences.getLengthSystem(this)
		val speedFormat = Preferences.getSpeedFormat(this)
		val dataList = listOf(
				InformationStatisticsData(
						com.adsamcik.tracker.common.R.drawable.ic_speedometer,
						R.string.stats_max_speed,
						resources.formatSpeed(maxSpeed, 1, lengthSystem, speedFormat)
				),

				InformationStatisticsData(
						com.adsamcik.tracker.common.R.drawable.ic_speedometer,
						R.string.stats_avg_speed,
						resources.formatSpeed(avgSpeed, 1, lengthSystem, speedFormat)
				)
		)

		addStatisticsData(adapter, dataList, AppendPriority(AppendBehavior.Any))
	}

	private fun addElevationStats(locations: List<DatabaseLocation>, adapter: StatsDetailAdapter) {
		val firstTime = locations.first().time
		var descended = 0.0
		var ascended = 0.0
		var previousAltitude = locations.firstOrNull { it.altitude != null }?.altitude ?: return

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
				InformationStatisticsData(
						R.drawable.arrow_top_right_bold_outline, R.string.stats_ascended,
						resources.formatDistance(ascended, 1, lengthSystem)
				),
				InformationStatisticsData(
						R.drawable.arrow_bottom_right_bold_outline, R.string.stats_descended,
						resources.formatDistance(descended, 1, lengthSystem)
				),
				InformationStatisticsData(
						com.adsamcik.tracker.common.R.drawable.ic_outline_terrain,
						R.string.stats_elevation_max,
						resources.formatDistance(maxAltitude, 1, lengthSystem)
				),
				InformationStatisticsData(
						com.adsamcik.tracker.common.R.drawable.ic_outline_terrain,
						R.string.stats_elevation_min,
						resources.formatDistance(minAltitude, 1, lengthSystem)
				),
				LineChartStatisticsData(
						com.adsamcik.tracker.common.R.drawable.ic_outline_terrain,
						R.string.stats_elevation, elevationList
				)
		)

		addStatisticsData(adapter, altitudeStatisticsList, AppendPriority(AppendBehavior.Any))
	}

	private fun addStatisticsData(
			adapter: StatsDetailAdapter,
			data: List<StatisticDetailData>,
			appendPriority: AppendPriority
	) {
		launch(Dispatchers.Main) {
			adapter.addAll(data.map { SortableAdapter.SortableData(it, appendPriority) })
		}
	}


	class ViewModel : androidx.lifecycle.ViewModel() {
		private var initialized = false
		private val sessionMutable: MutableLiveData<TrackerSession> = MutableLiveData()

		val session: LiveData<TrackerSession> get() = sessionMutable

		@WorkerThread
		fun initialize(context: Context, sessionId: Long) {
			if (initialized) return
			initialized = true

			val database = AppDatabase.database(context)
			sessionMutable.postValue(database.sessionDao().get(sessionId))
		}
	}

	companion object {
		const val ARG_SESSION_ID = "session_id"
	}
}

