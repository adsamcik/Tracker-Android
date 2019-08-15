package com.adsamcik.signalcollector.activity.ui.recycler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.data.SessionActivity
import com.adsamcik.signalcollector.common.style.ColorFunctions
import com.adsamcik.signalcollector.common.style.StyleData
import com.adsamcik.signalcollector.common.style.StyleManager

class ContextualSwipeTouchHelper(context: Context,
                                 val adapter: ActivityRecyclerAdapter,
                                 private val canSwipeCallback: (SessionActivity) -> Boolean) : ItemTouchHelper.SimpleCallback(
		0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
	private val icon: Drawable = requireNotNull(
			context.getDrawable(com.adsamcik.signalcollector.common.R.drawable.ic_baseline_remove_circle_outline))

	private val colorController = StyleManager.createController()

	private val backgroundPaint = Paint()
	private val foregroundPaint = Paint()

	var onSwipedCallback: ((position: Int) -> Unit)? = null

	init {
		colorController.addListener {
			updateColor(it)
		}
		updateColor(StyleManager.styleData)
	}

	private fun updateColor(styleData: StyleData) {
		val backgroundColor = styleData.backgroundColor(false)
		val foregroundColor = styleData.foregroundColor(false)
		val luminance = styleData.perceivedLuminance(false)

		backgroundPaint.color = ColorFunctions.getBackgroundLayerColor(backgroundColor, luminance, 1)
		foregroundPaint.color = foregroundColor

		icon.setTint(foregroundColor)
	}

	fun onDestroy() {
		StyleManager.recycleController(colorController)
	}

	override fun onMove(recyclerView: RecyclerView,
	                    viewHolder: RecyclerView.ViewHolder,
	                    target: RecyclerView.ViewHolder): Boolean {
		return false
	}

	override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
		return if (canSwipeCallback.invoke(adapter.getItem(viewHolder.adapterPosition))) {
			super.getSwipeDirs(recyclerView, viewHolder)
		} else {
			0
		}
	}

	override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
		when (direction) {
			ItemTouchHelper.LEFT, ItemTouchHelper.RIGHT -> {
				val position = viewHolder.adapterPosition
				onSwipedCallback?.invoke(position)
			}
		}
	}

	private fun drawBackground(canvas: Canvas, itemView: View, dX: Float) {
		if (dX == 0f) return

		val itemViewLeft = itemView.left
		val itemViewRight = itemView.right
		val backgroundCornerOffset = 20

		val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
		val iconTop = itemView.top + (itemView.height - icon.intrinsicHeight) / 2
		val iconBottom = iconTop + icon.intrinsicHeight

		val bounds: Rect
		when {
			dX > 0 -> { // Swiping to the right
				val visibleSize = dX.toInt() + backgroundCornerOffset
				val iconLeft = itemViewLeft + iconMargin
				val iconRight = itemViewLeft + iconMargin + icon.intrinsicWidth
				icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)

				bounds = Rect(itemViewLeft, itemView.top,
						itemViewLeft + visibleSize,
						itemView.bottom)
			}
			else -> { // Swiping to the left
				val visibleSize = -(dX.toInt() - backgroundCornerOffset)
				val iconLeft = itemViewRight - iconMargin - icon.intrinsicWidth
				val iconRight = itemViewRight - iconMargin
				icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)

				bounds = Rect(itemViewRight - visibleSize,
						itemView.top, itemViewRight, itemView.bottom)
			}
		}


		//todo add ripple animation like in https://material.io/design/components/lists.html#gestures
		canvas.clipRect(bounds)
		canvas.drawRect(bounds, backgroundPaint)

		icon.draw(canvas)
	}

	override fun onChildDraw(c: Canvas,
	                         recyclerView: RecyclerView,
	                         viewHolder: RecyclerView.ViewHolder,
	                         dX: Float,
	                         dY: Float,
	                         actionState: Int,
	                         isCurrentlyActive: Boolean) {
		drawBackground(c, viewHolder.itemView, dX)

		super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
	}
}
