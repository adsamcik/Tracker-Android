package com.adsamcik.tracker.statistics.list.recycler

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.startActivity
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.StatsFormat
import com.adsamcik.tracker.statistics.detail.activity.StatsDetailActivity
import com.adsamcik.tracker.statistics.summary.SummaryGenerator
import com.adsamcik.tracker.statistics.wifi.WifiBrowseActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.CoroutineContext


/**
 * Sectioned recycler adapter
 */
/*class SectionedRecyclerAdapter<T : AdapterSection<VH>, VH : RecyclerView.ViewHolder>(
		diffCallback: DiffUtil.ItemCallback<T>,
		mainDispatcher: CoroutineDispatcher,
		workerDispatcher: CoroutineDispatcher
) : PagingDataAdapter<T, VH>(diffCallback, mainDispatcher, workerDispatcher), IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onViewAttachedToWindow(holder: VH) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	override fun onBindViewHolder(holder: VH, position: Int) {
		TODO("Not yet implemented")
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
		TODO("Not yet implemented")
	}

	override fun getItemViewType(position: Int): Int = when(getItem(position)) {
		is
	}

}

interface ViewHandler<VH : RecyclerView.ViewHolder> {
	fun onBindHeader(holder: VH)
	fun onCreateHeader(parent: ViewGroup): VH
	fun onBindItem(holder: VH, position: Int)
	fun onCreateItem(parent: ViewGroup, position: Int): VH
}*/

class SessionSectionedRecyclerAdapter :
		PagingDataAdapter<SessionUiModel,
				RecyclerView.ViewHolder>(SessionModeComparator),
		IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		val item = getItem(position)
		when (holder) {
			is SessionModelViewHolder -> holder.bind(item as SessionUiModel.SessionModel)
			is SessionHeaderViewHolder -> holder.bind(item as SessionUiModel.SessionHeader)
		}

	}


	override fun onCreateViewHolder(
			parent: ViewGroup,
			viewType: Int
	): RecyclerView.ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		val rootView = inflater.inflate(viewType, parent, false) as ViewGroup
		return when (viewType) {
			R.layout.layout_section_preview_session -> SessionModelViewHolder(
					rootView,
					rootView.findViewById(R.id.text_time),
					rootView.findViewById(R.id.text_title),
					rootView.findViewById(R.id.text_info)
			)
			R.layout.layout_section_header_list -> SessionListHeaderViewHolder(
					rootView,
					rootView.findViewById(R.id.text_time),
					rootView.findViewById(R.id.text_distance),
					rootView.findViewById(R.id.button_stats_summary),
					rootView.findViewById(R.id.button_stats_week),
					rootView.findViewById(R.id.button_stats_wifi_list)
			)
			else -> SessionHeaderViewHolder(
					rootView,
					rootView.findViewById(R.id.text_time),
					rootView.findViewById(R.id.text_distance)
			)
		}
	}


	override fun getItemViewType(position: Int): Int = when (getItem(position)) {
		is SessionUiModel.SessionModel -> R.layout.layout_section_preview_session
		is SessionUiModel.SessionHeader -> R.layout.layout_section_header_session
		is SessionUiModel.ListHeader -> R.layout.layout_section_header_list
		else -> throw IllegalStateException("Unknown view")
	}

}

class SessionModelViewHolder(
		root: ViewGroup,
		val time: AppCompatTextView,
		val title: AppCompatTextView,
		val info: AppCompatTextView
) : RecyclerView.ViewHolder(root), CoroutineScope {

	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

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

	fun bind(model: SessionUiModel.SessionModel) {
		val session = model.session
		val timeFormat = SimpleDateFormat.getTimeInstance(
				SimpleDateFormat.MEDIUM,
				Locale.getDefault()
		)

		val context = itemView.context

		val startDate = Date(session.start)

		time.text = timeFormat.format(startDate)
		info.text = serializeInfo(context, session)

		val activityId = session.sessionActivityId
		title.text = StatsFormat.createTitle(
				context,
				session.start,
				session.end,
				SessionActivity.UNKNOWN
		)
		if (activityId != null) {
			launch {
				val activity = AppDatabase.database(context).activityDao().getLocalized(
						context,
						activityId
				) ?: SessionActivity.UNKNOWN

				title.post {
					title.text = StatsFormat.createTitle(
							context,
							session.start,
							session.end,
							activity
					)
				}
			}
		}

		itemView.tag = session.id
		itemView.setOnClickListener {
			it.context.startActivity<StatsDetailActivity> {
				putExtra(
						StatsDetailActivity.ARG_SESSION_ID,
						it.tag as Long
				)
			}
		}
	}
}

open class SessionHeaderViewHolder(
		root: ViewGroup,
		val time: AppCompatTextView,
		val distance: AppCompatTextView
) : RecyclerView.ViewHolder(root) {
	fun bind(header: SessionUiModel.SessionHeader) {
		time.text = DateUtils.getRelativeTimeSpanString(
				header.date,
				Time.todayMillis,
				DateUtils.DAY_IN_MILLIS
		)
	}
}

class SessionListHeaderViewHolder(
		root: ViewGroup,
		time: AppCompatTextView,
		distance: AppCompatTextView,
		val buttonSummary: AppCompatButton,
		val buttonWeek: AppCompatButton,
		val buttonWifi: AppCompatButton
) : SessionHeaderViewHolder(root, time, distance) {
	fun bind(header: SessionUiModel.ListHeader) {
		super.bind(header)
		time.text = DateUtils.getRelativeTimeSpanString(
				header.date,
				Time.todayMillis,
				DateUtils.DAY_IN_MILLIS
		)

		buttonSummary.setOnClickListener { SummaryGenerator.buildSummary(it.context) }
		buttonWeek.setOnClickListener { SummaryGenerator.buildSevenDaySummary(it.context) }
		buttonWifi.setOnClickListener { it.context.startActivity<WifiBrowseActivity> { } }
	}
}

sealed class SessionUiModel {
	class SessionModel(val session: TrackerSession) : SessionUiModel()
	open class SessionHeader(val date: Long) : SessionUiModel()
	class ListHeader(date: Long) : SessionUiModel.SessionHeader(date)
}

object SessionModeComparator : DiffUtil.ItemCallback<SessionUiModel>() {
	override fun areItemsTheSame(
			oldItem: SessionUiModel,
			newItem: SessionUiModel
	): Boolean {
		val isSameRepoItem = oldItem is SessionUiModel.SessionModel
				&& newItem is SessionUiModel.SessionModel
				&& oldItem.session.id == newItem.session.id

		val isSameSeparatorItem = oldItem is SessionUiModel.SessionHeader
				&& newItem is SessionUiModel.SessionHeader
				&& oldItem.date == newItem.date

		return isSameRepoItem || isSameSeparatorItem
	}

	override fun areContentsTheSame(
			oldItem: SessionUiModel,
			newItem: SessionUiModel
	) = oldItem == newItem
}

