package com.adsamcik.signalcollector.game.challenge.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.color.IViewChange
import com.adsamcik.signalcollector.common.extension.formatAsDuration
import com.adsamcik.signalcollector.game.R
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import kotlinx.android.synthetic.main.layout_challenge_list_item.view.*
import java.util.*

class ChallengeAdapter(mContext: Context, private var mDataSource: Array<ChallengeInstance<*, *>>) : RecyclerView.Adapter<ChallengeAdapter.ViewHolder>(), IViewChange {

	class ViewHolder(itemView: View,
	                 val titleTextView: TextView,
	                 val descriptionTextView: TextView,
	                 val difficultyTextView: TextView,
	                 val timeTextView: TextView,
	                 val progressBar: ProgressBar,
	                 val progressText: TextView) : RecyclerView.ViewHolder(itemView)


	private val mInflater: LayoutInflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

	fun updateData(challenges: Array<ChallengeInstance<*, *>>) {
		this.mDataSource = challenges
		notifyDataSetChanged()
	}

	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val view = mInflater.inflate(R.layout.layout_challenge_list_item, parent, false)
		return ViewHolder(view,
				view.challenge_title,
				view.challenge_description,
				view.challenge_difficulty,
				view.challenge_time_left,
				view.challenge_progress,
				view.challenge_progress_text)
	}

	override fun getItemCount(): Int = mDataSource.size

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val context = holder.itemView.context
		val challenge = mDataSource[position]
		holder.titleTextView.text = challenge.getTitle(context)
		holder.descriptionTextView.text = challenge.getDescription(context)
		holder.difficultyTextView.text = context.getString(challenge.difficulty.difficultyStringRes)

		val progress = (challenge.progress * 100.0).toInt()
		holder.progressBar.progress = progress
		holder.progressText.text = String.format(Locale.getDefault(), "%d%%", progress)

		holder.timeTextView.text = (challenge.endTime - Time.nowMillis).formatAsDuration(holder.itemView.context)

		onViewChangedListener?.invoke(holder.itemView)
	}


}