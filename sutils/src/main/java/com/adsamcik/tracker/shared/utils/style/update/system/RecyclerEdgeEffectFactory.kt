package com.adsamcik.tracker.shared.utils.style.update.system

import android.widget.EdgeEffect
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.recyclerview.widget.RecyclerView

/**
 * Edge factory for recycler view
 */
class RecyclerEdgeEffectFactory : RecyclerView.EdgeEffectFactory() {
	@ColorInt
	var color: Int = 0

	override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
		return EdgeEffect(view.context).apply {
			color = this@RecyclerEdgeEffectFactory.color
		}
	}
}
