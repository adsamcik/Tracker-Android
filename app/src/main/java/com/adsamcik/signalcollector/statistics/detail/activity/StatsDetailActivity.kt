package com.adsamcik.signalcollector.statistics.detail.activity

import android.content.Context
import android.os.Bundle
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import com.adsamcik.cardlist.CardItemDecoration
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.activity.DetailActivity
import com.adsamcik.signalcollector.app.color.ColorView
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.misc.extension.*
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailType
import com.adsamcik.signalcollector.statistics.detail.recycler.StatsDetailAdapter
import com.adsamcik.signalcollector.statistics.detail.recycler.creator.InformationViewHolderCreator
import com.adsamcik.signalcollector.statistics.detail.recycler.data.InformationData
import com.adsamcik.signalcollector.tracker.data.session.TrackerSession
import kotlinx.android.synthetic.main.activity_stats_detail.*
import java.text.SimpleDateFormat
import java.util.*

class StatsDetailActivity : DetailActivity() {
	private lateinit var viewModel: ViewModel

	override fun onCreate(savedInstanceState: Bundle?) {
		titleBarLayer = 0
		super.onCreate(savedInstanceState)

		inflateContent(R.layout.activity_stats_detail)

		colorManager.watchView(ColorView(root_stats_detail, 0))

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
		recycler.adapter = StatsDetailAdapter().apply {
			registerType(StatisticDetailType.Information, InformationViewHolderCreator())

			val data = mutableListOf(
					InformationData(R.drawable.ic_directions_walk_black_24dp, R.string.stats_distance_on_foot, resources.formatDistance(session.distanceOnFootInM, 2, lengthSystem)),
					InformationData(R.drawable.ic_directions_walk_black_24dp, R.string.stats_steps, session.steps.formatReadable()),
					InformationData(R.drawable.ic_baseline_commute_24px, R.string.stats_distance_total, resources.formatDistance(session.distanceInM, 2, lengthSystem)),
					InformationData(R.drawable.ic_directions_car_white_24dp, R.string.stats_distance_in_vehicle, resources.formatDistance(session.distanceInVehicleInM, 2, lengthSystem)))

			addData(data)
			//todo add Wi-Fi and Cell
			//todo add map
		}

		colorManager.watchAdapterView(ColorView(recycler, 0, rootIsBackground = false))

		recycler.addItemDecoration(CardItemDecoration())
		recycler.layoutManager = LinearLayoutManager(this)

		val startDate = Date(session.start)
		val startCalendar = startDate.toCalendar()
		val title = createTitle(startCalendar, "run")

		SimpleDateFormat("E", Locale.getDefault()).format(startDate)
		findViewById<AppCompatTextView>(R.id.title).text = title

		date_time.text = Date(session.start).toString()
	}

	private fun formatRange(start: Calendar, end: Calendar): String {
		val today = Calendar.getInstance().toDate()
		val startDate = start.toDate()
		val endDate = end.toDate()
		TODO()

		if (startDate == endDate) {
			val pattern = if (startDate.get(Calendar.YEAR) == today.get(Calendar.YEAR))
				"d MMMM"
			else
				"d MMMM yyyy"

			return SimpleDateFormat(pattern, Locale.getDefault()).format(start.time)
		} else
			SimpleDateFormat()
	}

	//Todo replace with new activity object once ready
	private fun createTitle(date: Calendar, activity: String): String {
		val hour = date[Calendar.HOUR_OF_DAY]
		return if (hour < 6 || hour > 22)
			getString(R.string.stats_night, activity)
		else if (hour < 12)
			getString(R.string.stats_morning, activity)
		else
			getString(R.string.stats_evening, activity)
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
