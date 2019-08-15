package com.adsamcik.signalcollector.statistics.list.recycler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import io.github.luizgrp.sectionedrecyclerviewadapter.SectionedRecyclerViewAdapter
import kotlin.math.roundToInt

//Needs to inherit DividerItemDecoration for StyleController
class SectionedDividerDecoration(private val adapter: SectionedRecyclerViewAdapter,
                                 context: Context,
                                 orientation: Int) : DividerItemDecoration(context, orientation) {

	companion object {
		const val HORIZONTAL = LinearLayout.HORIZONTAL
		const val VERTICAL = LinearLayout.VERTICAL
		private const val TAG = "DividerItem"
		private val ATTRS = intArrayOf(android.R.attr.listDivider)
	}

	private var mDivider: Drawable? = null

	/**
	 * Current orientation. Either [.HORIZONTAL] or [.VERTICAL].
	 */
	private var mOrientation: Int = 0

	private val mBounds = Rect()

	private fun requireDivider(): Drawable {
		return mDivider ?: throw NullPointerException("Divider cannot be null")
	}

	init {
		val a = context.obtainStyledAttributes(ATTRS)
		mDivider = a.getDrawable(0)
		if (mDivider == null) {
			Log.w(TAG,
					"@android:attr/listDivider was not set in the theme used for this " + "DividerItemDecoration. Please set that attribute all call setDrawable()")
		}
		a.recycle()
		setOrientation(orientation)
	}

	/**
	 * Sets the orientation for this divider. This should be called if
	 * [RecyclerView.LayoutManager] changes orientation.
	 *
	 * @param orientation [.HORIZONTAL] or [.VERTICAL]
	 */
	override fun setOrientation(orientation: Int) {
		if (orientation != HORIZONTAL && orientation != VERTICAL) {
			throw IllegalArgumentException(
					"Invalid orientation. It should be either HORIZONTAL or VERTICAL")
		}
		mOrientation = orientation
	}

	/**
	 * Sets the [Drawable] for this divider.
	 *
	 * @param drawable Drawable that should be used as a divider.
	 */
	override fun setDrawable(drawable: Drawable) {
		mDivider = drawable
	}

	/**
	 * @return the [Drawable] for this divider.
	 */
	override fun getDrawable(): Drawable? {
		return mDivider
	}

	override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
		if (parent.layoutManager == null || mDivider == null) {
			return
		}

		if (mOrientation == VERTICAL) {
			drawVertical(c, parent)
		} else {
			drawHorizontal(c, parent)
		}
	}

	private fun shouldDecorate(view: View, parent: RecyclerView): Boolean {
		val position = parent.getChildAdapterPosition(view)
		return adapter.getSectionItemViewType(position) == SectionedRecyclerViewAdapter.VIEW_TYPE_HEADER
	}

	private fun drawVertical(canvas: Canvas, parent: RecyclerView) {
		val divider = requireDivider()

		canvas.save()
		val left: Int
		val right: Int

		if (parent.clipToPadding) {
			left = parent.paddingLeft
			right = parent.width - parent.paddingRight
			canvas.clipRect(left, parent.paddingTop, right,
					parent.height - parent.paddingBottom)
		} else {
			left = 0
			right = parent.width
		}

		val childCount = parent.childCount
		for (i in 0 until childCount) {
			val child = parent.getChildAt(i)

			if (shouldDecorate(child, parent)) {
				parent.getDecoratedBoundsWithMargins(child, mBounds)
				val bottom = mBounds.bottom + child.translationY.roundToInt()
				val top = bottom - divider.intrinsicHeight
				divider.setBounds(left, top, right, bottom)
				divider.draw(canvas)
			}
		}
		canvas.restore()
	}

	private fun drawHorizontal(canvas: Canvas, parent: RecyclerView) {
		val divider = requireDivider()

		canvas.save()
		val top: Int
		val bottom: Int

		if (parent.clipToPadding) {
			top = parent.paddingTop
			bottom = parent.height - parent.paddingBottom
			canvas.clipRect(parent.paddingLeft, top,
					parent.width - parent.paddingRight, bottom)
		} else {
			top = 0
			bottom = parent.height
		}

		val childCount = parent.childCount
		val layoutManager = parent.layoutManager!!
		for (i in 0 until childCount) {
			val child = parent.getChildAt(i)
			if (shouldDecorate(child, parent)) {
				layoutManager.getDecoratedBoundsWithMargins(child, mBounds)
				val right = mBounds.right + child.translationX.roundToInt()
				val left = right - divider.intrinsicWidth
				divider.setBounds(left, top, right, bottom)
				divider.draw(canvas)
			}
		}
		canvas.restore()
	}

	override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView,
	                            state: RecyclerView.State
	) {
		val divider = mDivider
		if (divider == null) {
			outRect.set(0, 0, 0, 0)
			return
		}
		if (mOrientation == VERTICAL) {
			outRect.set(0, 0, 0, divider.intrinsicHeight)
		} else {
			outRect.set(0, 0, divider.intrinsicWidth, 0)
		}
	}
}
