package com.adsamcik.tracker.activity.ui.recycler

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.WorkerThread
import com.adsamcik.recycler.AppendPriority
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.common.data.SessionActivity
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.style.IViewChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class ActivityRecyclerAdapter : SortableAdapter<SessionActivity, RecyclerActivityViewHolder>(),
		IViewChange,
		CoroutineScope {
	override var onViewChangedListener: ((View) -> Unit)? = null

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	override fun onBindViewHolder(holder: RecyclerActivityViewHolder, position: Int) {
		val item = getItem(position)
		val context = holder.itemView.context
		holder.textView.apply {
			text = item.name
			val icon = item.getIcon(context)
			setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
		}
		onViewChangedListener?.invoke(holder.itemView)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerActivityViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		val rootView = inflater.inflate(R.layout.layout_activity_item, parent, false)
		return RecyclerActivityViewHolder(rootView, rootView as TextView)
	}

	@WorkerThread
	fun removeAtPermanently(context: Context, index: Int): SessionActivity {
		val item = getItem(index)
		launch { removeAt(index) }
		AppDatabase.getDatabase(context).activityDao().delete(item.id)
		return item
	}

	@WorkerThread
	fun addItemPersistent(context: Context, item: SessionActivity, priority: AppendPriority) {
		val id = AppDatabase.getDatabase(context).activityDao().insert(item)
		item.id = id
		launch { add(item, priority) }
	}
}

