package com.adsamcik.tracker.activity.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.common.activity.DetailActivity
import com.adsamcik.tracker.common.debug.DebugDatabase
import com.adsamcik.tracker.common.debug.LogData
import com.adsamcik.tracker.common.extension.formatAsDateTime
import com.adsamcik.tracker.common.recycler.BaseRecyclerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ActivityDebugActivity : DetailActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val rootView = createFrameContentLayout(true)

		val recyclerView = RecyclerView(this).apply {
			layoutManager = LinearLayoutManager(this@ActivityDebugActivity)
			adapter = Adapter().also { adapter ->
				launch(Dispatchers.Default) {
					val data = DebugDatabase
							.getInstance(this@ActivityDebugActivity)
							.genericLogDao()
							.getAll()

					adapter.addAll(data)
				}
			}
		}
		rootView.addView(recyclerView)
	}

	class Adapter : BaseRecyclerAdapter<LogData, Adapter.ViewHolder>() {
		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			val inflater = LayoutInflater.from(parent.context)
			val rootView = inflater.inflate(R.layout.layout_debug_activity_item, parent, false)
			return ViewHolder(
					rootView,
					rootView.findViewById(R.id.textview_summary),
					rootView.findViewById(R.id.textview_description)
			)
		}

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val logData = get(position)
			@Suppress()
			holder.summaryText.text = "${logData.timeStamp.formatAsDateTime()} - ${logData.message}"
			holder.descriptionText.text = logData.data
		}

		class ViewHolder(
				rootView: View,
				val summaryText: TextView,
				val descriptionText: TextView
		) : RecyclerView.ViewHolder(rootView)
	}
}
