package com.adsamcik.tracker.app.activity.debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.adapter.implementation.base.BaseRecyclerAdapter
import com.adsamcik.recycler.decoration.MarginDecoration
import com.adsamcik.tracker.R
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.LogDatabase
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import com.adsamcik.tracker.shared.utils.activity.DetailActivity
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Activity for activity recognition debugging
 */
internal class LogViewerActivity : DetailActivity() {

	override fun onConfigure(configuration: Configuration) {
		configuration.useColorControllerForContent = true
		configuration.titleBarLayer = 1
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val rootView = createFrameContentLayout(false)

		val recyclerView = RecyclerView(this).apply {
			layoutManager = LinearLayoutManager(this@LogViewerActivity)
			addItemDecoration(MarginDecoration())
			adapter = Adapter().also { adapter ->
				launch(Dispatchers.Default) {
					val data = LogDatabase
							.database(this@LogViewerActivity)
							.genericLogDao()
							.getLastOrderedDesc(1000)

					this@apply.post {
						adapter.addAll(data)
					}
				}
			}
		}
		rootView.addView(recyclerView)

		styleController.watchRecyclerView(RecyclerStyleView(recyclerView))
	}

	internal class Adapter : BaseRecyclerAdapter<LogData, Adapter.ViewHolder>() {
		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			val inflater = LayoutInflater.from(parent.context)
			val rootView = inflater.inflate(R.layout.layout_log_item, parent, false)
			return ViewHolder(
					rootView,
					rootView.findViewById(R.id.textview_summary),
					rootView.findViewById(R.id.textview_description)
			)
		}

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val logData = getItem(position)
			@SuppressLint("SetTextI18n")
			holder.summaryText.text = "${logData.timeStamp.formatAsDateTime()} ${logData.source} - ${logData.message}"
			holder.descriptionText.text = logData.data
		}

		internal class ViewHolder(
				rootView: View,
				val summaryText: TextView,
				val descriptionText: TextView
		) : RecyclerView.ViewHolder(rootView)
	}
}
