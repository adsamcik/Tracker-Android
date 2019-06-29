package com.adsamcik.signalcollector.activity.ui.recycler

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.signalcollector.common.color.ColorData
import com.adsamcik.signalcollector.common.color.ColorManager
import kotlin.math.min

class ContextualSwipeTouchHelper<T>(context: Context, val adapter: SortableAdapter<T, *>, private val canSwipeCallback: (T) -> Boolean) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
	private val icon: Drawable = context.getDrawable(com.adsamcik.signalcollector.common.R.drawable.ic_baseline_remove_circle_outline)!!

	private val background: ColorDrawable = ColorDrawable()

	private val colorController = ColorManager.createController()

	init {
		colorController.addListener {
			updateColor(it)
		}
		updateColor(ColorManager.currentColorData)
	}

	private fun updateColor(colorData: ColorData) {
		val backgroundColor = colorData.backgroundColor(false)
		val foregroundColor = colorData.foregroundColor(false)

		background.color = ColorManager.layerColor(backgroundColor, 1)
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

	override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
		super.onChildDraw(c, recyclerView, viewHolder, dX,
				dY, actionState, isCurrentlyActive)
		val itemView = viewHolder.itemView
		val itemViewLeft = itemView.left
		val itemViewRight = itemView.right
		val backgroundCornerOffset = 20

		val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
		val iconTop = itemView.top + (itemView.height - icon.intrinsicHeight) / 2
		val iconBottom = iconTop + icon.intrinsicHeight

		when {
			dX > 0 -> { // Swiping to the right
				val visibleSize = dX.toInt() + backgroundCornerOffset
				val iconLeft = itemViewLeft + iconMargin
				//todo replace with masking
				val iconRight = itemViewLeft + min(iconMargin + icon.intrinsicWidth, visibleSize)
				icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)

				background.setBounds(itemViewLeft, itemView.top,
						itemViewLeft + visibleSize,
						itemView.bottom)
			}
			dX < 0 -> { // Swiping to the left
				val visibleSize = -(dX.toInt() - backgroundCornerOffset)
				val iconLeft = itemViewRight - min(iconMargin + icon.intrinsicWidth, visibleSize)
				val iconRight = itemViewRight - iconMargin
				icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)

				background.setBounds(itemViewRight - visibleSize,
						itemView.top, itemViewRight, itemView.bottom)
			}
			else -> return
		}

		background.draw(c)
		icon.draw(c)
	}
}