package com.adsamcik.tracker.statistics.detail.activity

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.observe
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.adapter.implementation.multitype.MultiTypeData
import com.adsamcik.recycler.adapter.implementation.sort.AppendBehavior
import com.adsamcik.recycler.adapter.implementation.sort.AppendPriority
import com.adsamcik.recycler.adapter.implementation.sort.PrioritySortAdapter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.base.extension.requireValue
import com.adsamcik.tracker.shared.base.extension.toCalendar
import com.adsamcik.tracker.shared.base.misc.Double2
import com.adsamcik.tracker.shared.preferences.Preferences

import com.adsamcik.tracker.shared.utils.activity.DetailActivity
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.extension.formatSpeed
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeAdapter
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.StatsFormat
import com.adsamcik.tracker.statistics.data.LocationExtractor
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.data.source.StatisticDataManager
import com.adsamcik.tracker.statistics.detail.SessionActivitySelection
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.detail.recycler.creator.InformationViewHolderCreator
import com.adsamcik.tracker.statistics.detail.recycler.creator.LineChartViewHolderCreator
import com.adsamcik.tracker.statistics.detail.recycler.creator.MapViewHolderCreator
import com.adsamcik.tracker.statistics.detail.recycler.data.InformationStatisticsData
import com.adsamcik.tracker.statistics.detail.recycler.data.LineChartStatisticsData
import com.adsamcik.tracker.statistics.detail.recycler.data.MapStatisticsData
import com.afollestad.materialdialogs.MaterialDialog
import com.github.mikephil.charting.data.Entry
import com.goebl.simplify.Simplify3D
import com.google.android.gms.maps.MapsInitializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

typealias StatsDetailAdapter = StyleMultiTypeAdapter<StatisticDisplayType, MultiTypeData<StatisticDisplayType>>

/**
 * Activity for statistic details
 */
class StatsDetailActivity : DetailActivity() {
	private lateinit var viewModel: ViewModel

	val recycler: RecyclerView by lazy { findViewById<RecyclerView>(R.id.recycler) }

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

		require(sessionId > 0L) { "Argument $ARG_SESSION_ID must be set with valid value!" }

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



		addAction(com.adsamcik.tracker.shared.base.R.drawable.ic_baseline_edit,
		          R.string.edit_session,
		          View.OnClickListener {
			          val addItemLayout = findViewById<View>(R.id.add_item_layout)
			          val headerRoot = findViewById<ViewGroup>(R.id.header_root)
			          if (addItemLayout.isVisible) {
				          addItemLayout.visibility = View.GONE
				          headerRoot.updatePadding(top = 0)
			          } else {
				          addItemLayout.visibility = View.VISIBLE
				          headerRoot.updatePadding(top = HEADER_ROOT_PADDING.dp)
				          findViewById<View>(
						          R.id.button_change_activity
				          ).setOnClickListener { showActivitySelectionDialog() }
				          findViewById<View>(R.id.button_remove_session).setOnClickListener { showDeleteConfirmDialog() }
			          }
		          })
	}

	private fun showDeleteConfirmDialog() {
		MaterialDialog(this)
				.message(
						text = getString(
								com.adsamcik.tracker.shared.base.R.string.alert_confirm,
								getString(R.string.remove_session)
						)
				)
				.title(com.adsamcik.tracker.shared.base.R.string.alert_confirm_generic)
				.positiveButton(com.adsamcik.tracker.shared.base.R.string.yes) { removeSession() }
				.negativeButton(com.adsamcik.tracker.shared.base.R.string.no)
				.show()
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
			registerType(StatisticDisplayType.Information, InformationViewHolderCreator())
			registerType(StatisticDisplayType.Map, MapViewHolderCreator())
			registerType(StatisticDisplayType.LineChart, LineChartViewHolderCreator())
			//todo add Wi-Fi and Cell

			//addBasicStats(session, this)
			//addLocationStats(session, this@apply)
			addStats(session, this)
		}

		adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
			override fun onChanged() = Unit

			override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = Unit

			override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = Unit

			override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
				if (layoutManager.findFirstCompletelyVisibleItemPosition() == 0) {
					recycler.smoothScrollToPosition(0)
				}
			}

			override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = Unit

			override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
					Unit
		})

		val recycler = findViewById<RecyclerView>(R.id.recycler)
		recycler.adapter = adapter

		styleController.watchRecyclerView(RecyclerStyleView(recycler, 0))

		val endCalendar = Date(session.end).toCalendar()
		val startCalendar = Date(session.start).toCalendar()

		setTitle(session)

		findViewById<TextView>(R.id.date_time).text = StatsFormat.formatRange(
				startCalendar,
				endCalendar
		)
	}

	private fun addStats(session: TrackerSession, adapter: StatsDetailAdapter) {
		val context = this
		launch(Dispatchers.Default) {
			StatisticDataManager().getForSession(context, session.id) {
				launch(Dispatchers.Main) {
					adapter.add(convertToDisplayData(it))
				}
			}
		}
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
				findViewById<ImageView>(R.id.activity).setImageDrawable(drawable)
			}
		}
	}

	@Suppress("UNCHECKED_CAST")
	private fun convertToDisplayData(stat: Stat): StatisticDetailData {
		return when (stat.displayType) {
			StatisticDisplayType.Information -> InformationStatisticsData(
					stat.iconRes,
					stat.nameRes,
					stat.data as String
			)
			StatisticDisplayType.Map -> MapStatisticsData(stat.data as List<Location>)
			StatisticDisplayType.LineChart -> LineChartStatisticsData(
					stat.iconRes,
					stat.nameRes,
					stat.data as List<Entry>
			)
		}
	}


	private fun addBasicStats(session: TrackerSession, adapter: StatsDetailAdapter) {
		val resources = resources
		val lengthSystem = Preferences.getLengthSystem(this)


		val data = mutableListOf(
				InformationStatisticsData(
						com.adsamcik.tracker.shared.base.R.drawable.ic_outline_directions_24px,
						R.string.stats_distance_total,
						resources.formatDistance(session.distanceInM, 2, lengthSystem)
				),
				InformationStatisticsData(
						com.adsamcik.tracker.shared.base.R.drawable.ic_directions_walk_white,
						R.string.stats_distance_on_foot,
						resources.formatDistance(session.distanceOnFootInM, 2, lengthSystem)
				),
				InformationStatisticsData(
						com.adsamcik.tracker.shared.base.R.drawable.ic_shoe_print,
						R.string.stats_steps, session.steps.formatReadable()
				),
				InformationStatisticsData(
						com.adsamcik.tracker.shared.base.R.drawable.ic_baseline_commute,
						R.string.stats_distance_in_vehicle,
						resources.formatDistance(session.distanceInVehicleInM, 2, lengthSystem)
				)
		)

		adapter.addAllWrap(data.map {
			PrioritySortAdapter.PriorityWrap.create<StatisticDetailData>(it)
		})
	}

	private fun addLocationStats(session: TrackerSession, adapter: StatsDetailAdapter) {
		launch(Dispatchers.Default) {
			val database = AppDatabase.database(this@StatsDetailActivity)
			val locations = database.locationDao().getAllBetween(session.start, session.end)

			if (locations.size >= 2) {

				val simplify = Simplify3D<Location>(emptyArray(), LocationExtractor())
				val simplifiedLocations = simplify.simplify(
						locations.map { it.location }.toTypedArray(),
						POSITION_TOLERANCE,
						false
				)

				addLocationMap(simplifiedLocations, adapter)
				launch(Dispatchers.Default) { addElevationStats(simplifiedLocations, adapter) }
				launch(Dispatchers.Default) { addSpeedStats(locations, adapter) }
			}
		}
	}

	private fun addLocationMap(locations: Array<Location>, adapter: StatsDetailAdapter) {
		val locationData = PrioritySortAdapter.PriorityWrap.create<StatisticDetailData>(
				MapStatisticsData(locations),
				AppendPriority(AppendBehavior.Start)
		)
		launch(Dispatchers.Main) {
			adapter.addWrap(locationData)
		}
	}

	private fun getSpeed(previous: Location, current: Location, secondsElapsed: Double): Double {
		val recordedSpeed = current.speed
		return if (recordedSpeed != null) {
			recordedSpeed.toDouble()
		} else {
			val previousAltitude = previous.altitude
			val currentAltitude = current.altitude
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
	}

	private data class SpeedStats(val avgSpeed: Double, val maxSpeed: Double)

	private fun calculateSpeedStats(locations: List<DatabaseLocation>): SpeedStats {
		var maxSpeed = 0.0
		var speedSum = 0.0
		var speedCount = 0

		var previous = locations[0]
		for (i in 1 until locations.size) {
			val current = locations[i]
			val timeDifference = current.time - previous.time
			val secondsElapsed = timeDifference.toDouble() / Time.SECOND_IN_MILLISECONDS.toDouble()

			if (secondsElapsed <= MAX_SECONDS_ELAPSED_FOR_CONTINUOUS_PATH) {
				val speed = getSpeed(previous.location, current.location, secondsElapsed)

				if (speed > maxSpeed) maxSpeed = speed
				speedSum += speed
				speedCount++
			}

			previous = current
		}

		val avgSpeed = speedSum / speedCount

		return SpeedStats(avgSpeed, maxSpeed)
	}

	private fun addSpeedStats(locations: List<DatabaseLocation>, adapter: StatsDetailAdapter) {
		if (locations.isEmpty()) return

		val speedStats = calculateSpeedStats(locations)

		val lengthSystem = Preferences.getLengthSystem(this)
		val speedFormat = Preferences.getSpeedFormat(this)
		val dataList = listOf(
				InformationStatisticsData(
						com.adsamcik.tracker.shared.base.R.drawable.ic_speedometer,
						R.string.stats_max_speed,
						resources.formatSpeed(speedStats.maxSpeed, 1, lengthSystem, speedFormat)
				),

				InformationStatisticsData(
						com.adsamcik.tracker.shared.base.R.drawable.ic_speedometer,
						R.string.stats_avg_speed,
						resources.formatSpeed(speedStats.avgSpeed, 1, lengthSystem, speedFormat)
				)
		)

		addStatisticsData(adapter, dataList, AppendPriority(AppendBehavior.Any))
	}

	@Suppress("LongMethod")
	private fun addElevationStats(locations: Array<Location>, adapter: StatsDetailAdapter) {
		val firstTime = locations.first().time
		var descended = 0.0
		var ascended = 0.0

		val firstWithAltitude = locations.firstOrNull { it.altitude != null } ?: return

		val altitudeList = locations
				.mapNotNull {
					val altitude = it.altitude
					if (altitude != null) {
						Double2((it.time - firstTime).toDouble(), altitude)
					} else {
						null
					}
				}


		val elevationList = altitudeList
		//.simplifyRDP(HEIGHT_FILTER)

		var previousAltitude = requireNotNull(firstWithAltitude.altitude)

		var maxAltitude = previousAltitude
		var minAltitude = previousAltitude

		elevationList.forEach {
			val altitude = it.y
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
						com.adsamcik.tracker.shared.base.R.drawable.ic_outline_terrain,
						R.string.stats_elevation_max,
						resources.formatDistance(maxAltitude, 1, lengthSystem)
				),
				InformationStatisticsData(
						com.adsamcik.tracker.shared.base.R.drawable.ic_outline_terrain,
						R.string.stats_elevation_min,
						resources.formatDistance(minAltitude, 1, lengthSystem)
				),
				LineChartStatisticsData(
						com.adsamcik.tracker.shared.base.R.drawable.ic_outline_terrain,
						R.string.stats_elevation,
						elevationList.map { Entry(it.x.toFloat(), it.y.toFloat()) }
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
			adapter.addAllWrap(data.map {
				PrioritySortAdapter.PriorityWrap.create(it, appendPriority)
			})
		}
	}


	class ViewModel : androidx.lifecycle.ViewModel() {
		private var initialized = false
		private val sessionMutable: MutableLiveData<TrackerSession?> = MutableLiveData()

		/**
		 * Returns LiveData containing tracker sessions
		 */
		val session: LiveData<TrackerSession?> get() = sessionMutable

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
		private const val HEADER_ROOT_PADDING = 16
		private const val MAX_SECONDS_ELAPSED_FOR_CONTINUOUS_PATH = 70.0
		private const val POSITION_TOLERANCE = 500.0
	}
}

