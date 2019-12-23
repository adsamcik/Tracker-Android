package com.adsamcik.tracker.statistics.list.recycler

import android.content.Context
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.SessionActivity
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.extension.formatAsDuration
import com.adsamcik.tracker.common.extension.startActivity
import com.adsamcik.tracker.common.extension.toCalendar
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance

import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.StatsFormat
import com.adsamcik.tracker.statistics.detail.activity.StatsDetailActivity
import io.github.luizgrp.sectionedrecyclerviewadapter.Section
import io.github.luizgrp.sectionedrecyclerviewadapter.SectionParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.CoroutineContext

class SessionSection(private val time: Long, private val distance: Double) : Section(
		SectionParameters.builder()
				.headerResourceId(R.layout.layout_section_header_session)
				.itemResourceId(R.layout.layout_section_preview_session)
				.build()
), CoroutineScope {

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

		val distance = resources.formatDistance(
				session.distanceInM,
				1,
				Preferences.getLengthSystem(context)
		)
		builder.append(" | ").append(distance)

		return builder.toString()
	}

	override fun onBindHeaderViewHolder(holder: RecyclerView.ViewHolder?) {
		holder as HeaderViewHolder

		val resources = holder.itemView.resources
		val context = holder.itemView.context

		holder.time.text = DateUtils.getRelativeTimeSpanString(
				time,
				Time.todayMillis,
				DateUtils.DAY_IN_MILLIS
		)
		holder.distance.text = resources.formatDistance(
				distance,
				1,
				Preferences.getLengthSystem(context)
		)
	}

	override fun onBindItemViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		holder as ItemViewHolder
		val session = sessionList[position]
		val sessionId = session.id

		val timeFormat = SimpleDateFormat.getTimeInstance(
				SimpleDateFormat.MEDIUM,
				Locale.getDefault()
		)

		val context = holder.itemView.context

		val startDate = Date(session.start)
		val startCalendar = startDate.toCalendar()

		val endCalendar = Date(session.end).toCalendar()

		holder.time.text = timeFormat.format(startDate)
		holder.info.text = serializeInfo(context, session)

		val activityId = session.sessionActivityId
		holder.title.text = StatsFormat.createTitle(
				context,
				startCalendar,
				endCalendar,
				SessionActivity.UNKNOWN
		)
		if (activityId != null) {
			launch(Dispatchers.Default) {
				val activity = AppDatabase.database(context).activityDao().getLocalized(
						context,
						activityId
				) ?: SessionActivity.UNKNOWN

				holder.title.post {
					holder.title.text = StatsFormat.createTitle(
							context,
							startCalendar,
							endCalendar,
							activity
					)
				}
			}
		}

		holder.itemView.setOnClickListener {
			context.startActivity<StatsDetailActivity> {
				putExtra(
						StatsDetailActivity.ARG_SESSION_ID,
						sessionId
				)
			}
		}
	}

	override fun getItemViewHolder(view: View): RecyclerView.ViewHolder {
		return ItemViewHolder(
				view,
				view.findViewById(R.id.text_title),
				view.findViewById(R.id.text_time),
				view.findViewById(R.id.text_info)
		)
	}

	override fun getHeaderViewHolder(view: View): RecyclerView.ViewHolder {
		return HeaderViewHolder(
				view,
				view.findViewById(R.id.text_time),
				view.findViewById(R.id.text_distance)
		)
	}

	private class HeaderViewHolder(
			rootView: View,
			val time: TextView,
			val distance: TextView
	) : RecyclerView.ViewHolder(rootView)

	private class ItemViewHolder(
			rootView: View,
			val title: TextView,
			val time: TextView,
			val info: TextView
	) : RecyclerView.ViewHolder(rootView)
}

