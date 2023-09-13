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
import com.adsamcik.tracker.shared.utils.style.SunSetRise
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.StatsFormat
import com.adsamcik.tracker.statistics.detail.activity.StatsDetailActivity
import com.adsamcik.tracker.statistics.dialog.StatisticSummaryDialog
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
 * Sectioned recycler adapter for sessions with paging.
 */
internal class SessionSectionedRecyclerAdapter :
	PagingDataAdapter<SessionUiModel,
			RecyclerView.ViewHolder>(SessionModeComparator),
	IViewChange {

	private val sunSetRise = SunSetRise()

	override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
		super.onAttachedToRecyclerView(recyclerView)
		sunSetRise.initialize(recyclerView.context)
	}

	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		val item = getItem(position)
		when (holder) {
			is SessionModelViewHolder -> holder.bind(item as SessionUiModel.SessionModel)
			is SessionListHeaderViewHolder -> holder.bind(item as SessionUiModel.ListHeader)
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
				rootView.findViewById(R.id.text_info),
				sunSetRise
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
		is SessionUiModel.ListHeader -> R.layout.layout_section_header_list
		is SessionUiModel.SessionHeader -> R.layout.layout_section_header_session
		else -> throw IllegalStateException("Unknown view")
	}

}

internal class SessionModelViewHolder(
	root: ViewGroup,
	val time: AppCompatTextView,
	val title: AppCompatTextView,
	val info: AppCompatTextView,
	val sunSetRise: SunSetRise,
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
			SessionActivity.UNKNOWN,
			sunSetRise
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
						activity,
						sunSetRise
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

internal open class SessionHeaderViewHolder(
	root: ViewGroup,
	private val time: AppCompatTextView,
	private val distance: AppCompatTextView
) : RecyclerView.ViewHolder(root) {
	fun bind(header: SessionUiModel.SessionHeader) {
		time.text = DateUtils.getRelativeTimeSpanString(
			header.date,
			Time.todayMillis,
			DateUtils.DAY_IN_MILLIS
		)
	}
}

internal class SessionListHeaderViewHolder(
	root: ViewGroup,
	time: AppCompatTextView,
	distance: AppCompatTextView,
	private val buttonSummary: AppCompatButton,
	private val buttonWeek: AppCompatButton,
	private val buttonWifi: AppCompatButton
) : SessionHeaderViewHolder(root, time, distance) {
	fun bind(header: SessionUiModel.ListHeader) {
		super.bind(header)

		buttonSummary.setOnClickListener {
			StatisticSummaryDialog().show(
				it.context,
				R.string.stats_sum_title,
				SummaryGenerator::buildSummary
			)
		}
		buttonWeek.setOnClickListener {
			StatisticSummaryDialog().show(
				it.context,
				R.string.stats_weekly_title,
				SummaryGenerator::buildSevenDaySummary
			)
		}
		buttonWifi.setOnClickListener { it.context.startActivity<WifiBrowseActivity> { } }
	}
}

/**
 * Session UI models
 */
internal sealed class SessionUiModel {
	/**
	 * Session data UI model.
	 */
	class SessionModel(val session: TrackerSession) : SessionUiModel()

	/**
	 * Session header UI model.
	 * Groups sessions by days.
	 */
	open class SessionHeader(val date: Long) : SessionUiModel()

	/**
	 * List header UI model.
	 * Shown at the top of the session list.
	 */
	class ListHeader(date: Long) : SessionHeader(date)
}

/**
 * Comparator for session UI models
 */
internal object SessionModeComparator : DiffUtil.ItemCallback<SessionUiModel>() {
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

