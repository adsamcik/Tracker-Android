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
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.adapter.implementation.sort.callback.SortCallback
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.base.extension.requireValue
import com.adsamcik.tracker.shared.base.extension.toCalendar
import com.adsamcik.tracker.shared.utils.activity.DetailActivity
import com.adsamcik.tracker.shared.utils.multitype.StyleSortMultiTypeAdapter
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.StatsFormat
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.data.source.StatisticDataManager
import com.adsamcik.tracker.statistics.detail.SessionActivitySelection
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.detail.recycler.StatisticsDetailData
import com.adsamcik.tracker.statistics.detail.recycler.creator.InformationViewHolderCreator
import com.adsamcik.tracker.statistics.detail.recycler.creator.LineChartViewHolderCreator
import com.adsamcik.tracker.statistics.detail.recycler.creator.MapViewHolderCreator
import com.adsamcik.tracker.statistics.detail.recycler.data.InformationStatisticsData
import com.adsamcik.tracker.statistics.detail.recycler.data.LineChartStatisticsData
import com.adsamcik.tracker.statistics.detail.recycler.data.MapStatisticsData
import com.afollestad.materialdialogs.MaterialDialog
import com.github.mikephil.charting.data.Entry
import com.google.android.gms.maps.MapsInitializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

typealias StatsDetailAdapter = StyleSortMultiTypeAdapter<StatisticDisplayType, StatisticsDetailData>

/**
 * Activity for statistic details
 */
class StatsDetailActivity : DetailActivity() {
	private lateinit var viewModel: ViewModel

	val recycler: RecyclerView by lazy { findViewById(R.id.recycler) }

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



		addAction(
				com.adsamcik.tracker.shared.base.R.drawable.ic_baseline_edit,
				R.string.edit_session
		) {
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
		}

		styleController.forceUpdate()
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

		val callback = object : SortCallback<StatisticsDetailData> {
			override fun areContentsTheSame(
					a: StatisticsDetailData,
					b: StatisticsDetailData
			): Boolean {
				return areItemsTheSame(a, b)
			}

			override fun areItemsTheSame(
					a: StatisticsDetailData,
					b: StatisticsDetailData
			): Boolean = a == b

			override fun compare(a: StatisticsDetailData, b: StatisticsDetailData): Int {
				return a::class.java.simpleName.compareTo(b::class.java.simpleName)
			}

		}

		val adapter = StatsDetailAdapter(
				styleController,
				callback,
				StatisticsDetailData::class.java
		).apply {
			registerType(StatisticDisplayType.Information, InformationViewHolderCreator())
			registerType(StatisticDisplayType.Map, MapViewHolderCreator())
			registerType(StatisticDisplayType.LineChart, LineChartViewHolderCreator())
			//todo add Wi-Fi and Cell

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
				activityId < -1 -> NativeSessionActivity.values()
						.find { it.id == activityId }
						?.getSessionActivity(
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
	private fun convertToDisplayData(stat: Stat): StatisticsDetailData {
		return when (stat.displayType) {
			StatisticDisplayType.Information -> InformationStatisticsData(
					stat.iconRes,
					stat.nameRes,
					stat.data.toString()
			)
			StatisticDisplayType.Map -> MapStatisticsData(stat.data as List<Location>)
			StatisticDisplayType.LineChart -> LineChartStatisticsData(
					stat.iconRes,
					stat.nameRes,
					stat.data as List<Entry>
			)
		}
	}


	/**
	 * View model for statistics detail activity
	 */
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
		/**
		 * Session id argument identifier
		 */
		const val ARG_SESSION_ID = "session_id"
		private const val HEADER_ROOT_PADDING = 16
	}
}

