package com.adsamcik.signalcollector.statistics.list.recycler

import android.content.Context
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.data.SessionActivity
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.extension.*
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.statistics.R
import com.adsamcik.signalcollector.statistics.StatsFormat
import io.github.luizgrp.sectionedrecyclerviewadapter.SectionParameters
import io.github.luizgrp.sectionedrecyclerviewadapter.StatelessSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.CoroutineContext

class SessionSection(private val time: Long, private val distance: Double) : StatelessSection(SectionParameters.builder()
		.headerResourceId(R.layout.layout_section_header_session)
		.itemResourceId(R.layout.layout_section_preview_session)
		.build()), CoroutineScope {

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	private val sessionList = mutableListOf<TrackerSession>()

	override fun getContentItemsTotal(): Int = sessionList.size

	fun addAll(sessionCollection: Collection<TrackerSession>) {
		sessionList.addAll(sessionCollection)
	}

	//todo improve localization
	private fun serializeInfo(context: Context, session: TrackerSession): String {
		val builder = StringBuilder()
		val resources = context.resources

		val time = (session.end - session.start).formatAsDuration(context)
		builder.append(time)

		val distance = resources.formatDistance(session.distanceInM, 1, Preferences.getLengthSystem(context))
		builder.append(" | ").append(distance)

		return builder.toString()
	}

	override fun onBindHeaderViewHolder(holder: RecyclerView.ViewHolder?) {
		holder as HeaderViewHolder

		val resources = holder.itemView.resources
		val context = holder.itemView.context

		holder.time.text = DateUtils.getRelativeDateTimeString(context, time, DateUtils.DAY_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0)
		holder.distance.text = resources.formatDistance(distance, 1, Preferences.getLengthSystem(context))
	}

	override fun onBindItemViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		holder as ItemViewHolder
		val session = sessionList[position]

		val timeFormat = SimpleDateFormat.getTimeInstance(SimpleDateFormat.MEDIUM, Locale.getDefault())

		val context = holder.itemView.context

		val startDate = Date(session.start)
		val startCalendar = startDate.toCalendar()

		holder.time.text = timeFormat.format(startDate)
		holder.info.text = serializeInfo(context, session)

		val activityId = session.sessionActivityId
		if (activityId == null) {
			holder.title.text = StatsFormat.createTitle(context, startCalendar, SessionActivity.empty)
		} else {
			launch {
				val activity = AppDatabase.getDatabase(context).activityDao().get(activityId)
						?: SessionActivity.empty
				holder.title.text = StatsFormat.createTitle(context, startCalendar, activity)
			}
		}
	}

	override fun getItemViewHolder(view: View): RecyclerView.ViewHolder {
		return ItemViewHolder(view,
				view.findViewById(R.id.text_title),
				view.findViewById(R.id.text_time),
				view.findViewById(R.id.text_info))
	}

	override fun getHeaderViewHolder(view: View): RecyclerView.ViewHolder {
		return HeaderViewHolder(view,
				view.findViewById(R.id.text_time),
				view.findViewById(R.id.text_distance))
	}

	private class HeaderViewHolder(rootView: View,
	                               val time: TextView,
	                               val distance: TextView) : RecyclerView.ViewHolder(rootView)

	private class ItemViewHolder(rootView: View,
	                             val title: TextView,
	                             val time: TextView,
	                             val info: TextView) : RecyclerView.ViewHolder(rootView)
}