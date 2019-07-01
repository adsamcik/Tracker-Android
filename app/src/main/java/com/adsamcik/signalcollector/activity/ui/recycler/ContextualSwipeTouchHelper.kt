package com.adsamcik.signalcollector.activity.ui.recycler

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.signalcollector.common.color.ColorData
import com.adsamcik.signalcollector.common.color.ColorManager

class ContextualSwipeTouchHelper<T>(context: Context, val adapter: SortableAdapter<T, *>, private val canSwipeCallback: (T) -> Boolean) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
	private val icon: Drawable = context.getDrawable(com.adsamcik.signalcollector.common.R.drawable.ic_baseline_remove_circle_outline)!!

	private val colorController = ColorManager.createController()

	private val maskPaint = Paint(Color.BLACK).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
	private val backgroundPaint = Paint()
	private val foregroundPaint = Paint()

	init {
		colorController.addListener {
			updateColor(it)
		}
		updateColor(ColorManager.currentColorData)
	}

	private fun updateColor(colorData: ColorData) {
		val backgroundColor = colorData.backgroundColor(false)
		val foregroundColor = colorData.foregroundColor(false)

		backgroundPaint.color = ColorManager.layerColor(backgroundColor, 1)
		foregroundPaint.color = foregroundColor

		icon.setTint(foregroundColor)
	}

	fun onDestroy() {
		ColorManager.recycleController(colorController)
	}

	override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
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
				adapter.removeAt(position)
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

	override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
		drawBackground(c, viewHolder.itemView, dX)

		super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
	}
}