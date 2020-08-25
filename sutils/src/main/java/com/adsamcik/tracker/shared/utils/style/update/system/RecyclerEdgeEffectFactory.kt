package com.adsamcik.tracker.shared.utils.style.update.system

import android.widget.EdgeEffect
import androidx.annotation.ColorRes
import androidx.recyclerview.widget.RecyclerView

class RecyclerEdgeEffectFactory : RecyclerView.EdgeEffectFactory() {
	@ColorRes
	var color: Int = 0

	override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
		return EdgeEffect(view.context).apply { color = color }
	}
}
