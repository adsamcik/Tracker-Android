package com.adsamcik.tracker.game.challenge.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.toIntPercent
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange
import java.util.*

/**
 * Adapter for challenges in Game tracker tab.
 */
class ChallengeAdapter(
		mContext: Context,
		private var mDataSource: Array<ChallengeInstance<*, *>>
) : RecyclerView.Adapter<ChallengeAdapter.ViewHolder>(),
		IViewChange {

	/**
	 * Challenges view holder.
	 */
	@Suppress("LongParameterList")
	class ViewHolder(
			itemView: View,
			val titleTextView: TextView,
			val descriptionTextView: TextView,
			val difficultyTextView: TextView,
			val timeTextView: TextView,
			val progressBar: ProgressBar,
			val progressText: TextView
	) : RecyclerView.ViewHolder(itemView)


	private val mInflater: LayoutInflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

	/**
	 * Updates data source.
	 */
	fun updateData(challenges: Array<ChallengeInstance<*, *>>) {
		this.mDataSource = challenges
		notifyDataSetChanged()
	}

	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val view = mInflater.inflate(R.layout.layout_challenge_list_item, parent, false)
		return ViewHolder(
				view,
				view.findViewById(R.id.challenge_title),
				view.findViewById(R.id.challenge_description),
				view.findViewById(R.id.challenge_difficulty),
				view.findViewById(R.id.challenge_time_left),
				view.findViewById(R.id.challenge_progress),
				view.findViewById(R.id.challenge_progress_text)
		)
	}

	override fun getItemCount(): Int = mDataSource.size

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val context = holder.itemView.context
		val challenge = mDataSource[position]
		holder.titleTextView.text = challenge.getTitle(context)
		holder.descriptionTextView.text = challenge.getDescription(context)
		holder.difficultyTextView.text = context.getString(challenge.difficulty.difficultyStringRes)

		val progress = challenge.progress.toIntPercent()
		holder.progressBar.progress = progress
		holder.progressText.text = String.format(Locale.getDefault(), "%d%%", progress)

		holder.timeTextView.text = (challenge.endTime - Time.nowMillis).formatAsDuration(holder.itemView.context)
	}

	override fun onViewAttachedToWindow(holder: ViewHolder) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}
}
