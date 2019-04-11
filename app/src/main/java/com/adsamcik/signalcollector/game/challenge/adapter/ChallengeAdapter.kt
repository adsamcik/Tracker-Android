package com.adsamcik.signalcollector.game.challenge.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.adapter.IViewChange
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.misc.extension.formatAsDuration
import kotlinx.android.synthetic.main.layout_challenge_list_item.view.*

class ChallengeAdapter(mContext: Context, private var mDataSource: Array<ChallengeInstance<*>>) : RecyclerView.Adapter<ChallengeAdapter.ViewHolder>(), IViewChange {

	class ViewHolder(itemView: View,
	                 val titleTextView: AppCompatTextView,
	                 val descriptionTextView: AppCompatTextView,
	                 val difficultyTextView: AppCompatTextView,
	                 val timeTextView: AppCompatTextView,
	                 val progressBar: ProgressBar) : RecyclerView.ViewHolder(itemView)


	private val mInflater: LayoutInflater = mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

	fun updateData(challenges: Array<ChallengeInstance<*>>) {
		this.mDataSource = challenges
		notifyDataSetChanged()
	}

	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val view = LayoutInflater.from(parent.context).inflate(R.layout.layout_challenge_list_item, parent, false)
		return ViewHolder(view, view.challenge_title, view.challenge_description, view.challenge_difficulty, view.challenge_time_left, view.challenge_progress)
	}

	override fun getItemCount(): Int = mDataSource.size

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val challenge = mDataSource[position]
		holder.titleTextView.text = challenge.title
		holder.descriptionTextView.text = challenge.description
		holder.difficultyTextView.text = challenge.difficulty.name.replace('_', ' ').toLowerCase()

		holder.progressBar.progress = challenge.progress
		holder.timeTextView.text = (challenge.endTime - System.currentTimeMillis()).formatAsDuration(holder.itemView.context)

		//val color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context!!, R.color.background_success), (challenge.progress * 25.5).roundToInt())
		//fragmentView.setBackgroundColor(color)

		onViewChangedListener?.invoke(holder.itemView)
	}


}