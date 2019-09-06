package com.adsamcik.tracker.activity.ui.recycler

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecyclerActivityViewHolder(
		root: View,
		val textView: TextView,
		val editButton: Button
) : RecyclerView.ViewHolder(root)
