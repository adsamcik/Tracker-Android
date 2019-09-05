package com.adsamcik.tracker.tracker.notification

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.adapter.implementation.base.BaseRecyclerAdapter
import com.adsamcik.tracker.common.database.data.NotificationPreference
import com.adsamcik.tracker.common.style.marker.IViewChange
import com.adsamcik.tracker.tracker.R
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

class NotificationRecyclerAdapter : BaseRecyclerAdapter<NotificationPreferenceInstance, NotificationRecyclerAdapter.ViewHolder>(),
		IViewChange,
		CoroutineScope {
	override var onViewChangedListener: ((View) -> Unit)? = null

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val item = getItem(position)
		val context = holder.itemView.context
		holder.textView.setText(item.titleRes)
		//holder.checkBox.

		onViewChangedListener?.invoke(holder.itemView)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		val rootView = inflater.inflate(R.layout.notification_recycler_item, parent, false)
		val textView = rootView.findViewById<AppCompatTextView>(R.id.title)
		val checkbox = rootView.findViewById<MaterialCheckBox>(R.id.checkbox)
		return ViewHolder(rootView, textView, checkbox)
	}

	class ViewHolder(
			root: View,
			val textView: TextView,
			val checkBox: CheckBox
	) : RecyclerView.ViewHolder(root)
}
