package com.adsamcik.tracker.activity.ui.recycler

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.WorkerThread
import com.adsamcik.recycler.adapter.implementation.sort.BaseSortAdapter
import com.adsamcik.recycler.adapter.implementation.sort.callback.SortCallback
import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.common.data.SessionActivity
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class ActivityRecyclerAdapter(
		private val editCallback: (position: Int) -> Unit
) : BaseSortAdapter<SessionActivity, RecyclerActivityViewHolder>(SessionActivity::class.java),
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

		if (item.id >= 0) {
			holder.editButton.apply {
				tag = position
				setOnClickListener { editCallback(it.tag as Int) }
				visibility = View.VISIBLE
			}
		} else {
			holder.editButton.visibility = View.GONE
		}
	}

	override fun onViewAttachedToWindow(holder: RecyclerActivityViewHolder) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerActivityViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		val rootView = inflater.inflate(R.layout.layout_activity_item, parent, false)
		return RecyclerActivityViewHolder(
				rootView,
				rootView.findViewById(R.id.title),
				rootView.findViewById(R.id.edit)
		)
	}

	@WorkerThread
	fun removeAtPermanently(context: Context, index: Int): SessionActivity {
		val item = getItem(index)
		launch { removeAt(index) }
		AppDatabase.database(context).activityDao().delete(item.id)
		return item
	}

	@WorkerThread
	fun addItemPersistent(
			context: Context,
			item: SessionActivity
	) {
		val id = AppDatabase.database(context).activityDao().insert(item)
		item.id = id
		launch { add(item) }
	}

	@WorkerThread
	fun updateItemPersistent(context: Context, item: SessionActivity) {
		AppDatabase.database(context).activityDao().update(item)
		val index = indexOf { it.id == item.id }
		require(index >= 0)
		launch { updateAt(index, item) }
	}


	override val sortCallback: SortCallback<SessionActivity> = object : SortCallback<SessionActivity> {
		override fun areContentsTheSame(a: SessionActivity, b: SessionActivity): Boolean {
			return a == b
		}

		override fun areItemsTheSame(a: SessionActivity, b: SessionActivity): Boolean {
			return a.id == b.id
		}

		override fun compare(a: SessionActivity, b: SessionActivity): Int {
			return a.name.compareTo(b.name)
		}

	}
}

