package com.adsamcik.tracker.tracker.notification

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.WorkerThread
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isGone
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.adapter.implementation.base.BaseRecyclerAdapter
import com.adsamcik.tracker.common.database.PreferenceDatabase
import com.adsamcik.tracker.common.database.data.NotificationPreference
import com.adsamcik.tracker.common.style.marker.IViewChange
import com.adsamcik.tracker.tracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min

internal class NotificationRecyclerAdapter(
		private val dragStartListener: OnStartDragListener,
		private val editCallback: (position: Int) -> Unit
) : BaseRecyclerAdapter<TrackerNotificationComponent, NotificationRecyclerAdapter.ViewHolder>(),
		IViewChange,
		CoroutineScope {

	override var onViewChangedListener: ((View) -> Unit)? = null

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	@SuppressLint("ClickableViewAccessibility")
	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val item = getItem(position)
		holder.textView.setText(item.titleRes)
		holder.dragButton.setOnTouchListener { view, motionEvent ->
			when (motionEvent.actionMasked) {
				MotionEvent.ACTION_DOWN -> dragStartListener.onStartDrag(holder)
				else -> view.onTouchEvent(motionEvent)
			}
			return@setOnTouchListener true
		}

		holder.editButton.setOnClickListener {
			editCallback(holder.adapterPosition)
		}

		val preference = item.preference
		holder.imageTitle.isGone = !preference.isInTitle
		holder.imageContent.isGone = !preference.isInContent
	}

	override fun onViewAttachedToWindow(holder: ViewHolder) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		val rootView = inflater.inflate(R.layout.notification_recycler_item, parent, false)
		val textView = rootView.findViewById<AppCompatTextView>(R.id.title)
		val editButton = rootView.findViewById<Button>(R.id.edit)
		val dragButton = rootView.findViewById<ImageView>(R.id.drag_button)
		val imageTitle = rootView.findViewById<ImageView>(R.id.icon_title)
		val imageContent = rootView.findViewById<ImageView>(R.id.icon_content)
		return ViewHolder(rootView, textView, dragButton, editButton, imageTitle, imageContent)
	}

	@WorkerThread
	fun updateItemPersistent(context: Context, item: NotificationPreference) {
		PreferenceDatabase.database(context).notificationDao.upsert(item)
		val index = indexOf { it.id == item.id }
		require(index >= 0)
		launch {
			getItem(index).preference = item
			notifyItemChanged(index)
		}
	}

	@WorkerThread
	fun moveItemPersistent(context: Context, from: Int, to: Int) {
		launch {
			move(from, to)

			launch(Dispatchers.Default) {
				val fromIndex = min(from, to)
				val toIndex = max(from, to)

				val updateOrderList = ArrayList<NotificationPreference>(toIndex - fromIndex + 1)
				for (i in fromIndex..toIndex) {
					val item = getItem(i).preference
					updateOrderList.add(
							NotificationPreference(
									item.id,
									i,
									item.isInTitle,
									item.isInContent
							)
					)
				}

				PreferenceDatabase.database(context).notificationDao.upsert(updateOrderList)
			}
		}
	}

	class ViewHolder(
			root: View,
			val textView: TextView,
			val dragButton: ImageView,
			val editButton: Button,
			val imageTitle: ImageView,
			val imageContent: ImageView
	) : RecyclerView.ViewHolder(root)
}
