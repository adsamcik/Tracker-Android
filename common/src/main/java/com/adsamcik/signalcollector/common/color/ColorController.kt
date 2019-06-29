package com.adsamcik.signalcollector.common.color

import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.AnyThread
import androidx.annotation.ColorInt
import androidx.annotation.IdRes
import androidx.annotation.MainThread
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.alpha
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.color.ColorManager.currentColorData
import com.adsamcik.signalcollector.common.color.ColorManager.layerColor
import com.adsamcik.signalcollector.common.extension.contains
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.InvalidClassException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

typealias ColorListener = (colorData: ColorData) -> Unit

/**
 * ColorController class that handles color updates of views in a given Activity or Fragment.
 */
@AnyThread
//todo add support for local custom Views
//todo refactor so the class is smaller
class ColorController : CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	private val watchedViewList = mutableListOf<ColorView>()

	/**
	 * Colors listener array. Holds all listeners.
	 *
	 */
	private val colorChangeListeners = ArrayList<ColorListener>(0)

	private var suspendLock = ReentrantLock()
	private var updateRequestedWhileSuspended: Boolean = false
		get() {
			suspendLock.withLock {
				return field
			}
		}
		set(value) {
			suspendLock.withLock {
				field = value
			}
		}

	/**
	 * Is suspended controls whether color controller updates it's views or not.
	 * It is useful when views are temporarily invisible so they do not need to be resubscribed.
	 */
	var isSuspended: Boolean = false
		get() {
			suspendLock.withLock {
				return field
			}
		}
		set(value) {
			suspendLock.withLock {
				field = value
				if (!value && updateRequestedWhileSuspended) {
					updateRequestedWhileSuspended = false
					update(currentColorData)
				}
			}
		}

	/**
	 * Add given [colorView] to the list of watched Views
	 *
	 */
	fun watchView(colorView: ColorView) {
		synchronized(watchedViewList) {
			watchedViewList.add(colorView)
		}
		updateInternal(colorView, currentColorData)
	}

	/**
	 * Notifies [ColorController] that change has occurred on given view. View needs to be subscribed to color updates.
	 * It is recommended to pass root View of ColorView because it does not trigger recursive lookup.
	 *
	 * @param view root View of ColorView
	 */
	fun notifyChangeOn(view: View) {
		var find: ColorView? = null
		synchronized(watchedViewList) {
			find = watchedViewList.find { it.view == view }
					?: watchedViewList.find { it.view.contains(view) }
		}

		if (find != null) {
			updateInternal(find!!, currentColorData)
		} else {
			throw IllegalArgumentException("View is not subscribed")
		}
	}

	/**
	 * Add given [ColorView] that must derive from [AdapterView] to the list of watched view. Provides additional support for recycling so recycled views are styled properly.
	 *
	 * Adapter needs to implement [IViewChange] interface for the best and most reliable color updating.
	 * However it will somehow work even without it, but it might not be reliable.
	 */
	fun watchRecyclerView(colorView: ColorView) {
		if (colorView.view !is RecyclerView) throw InvalidClassException("Color view must be of type ${RecyclerView::class}")

		launch(Dispatchers.Main) {
			val adapter = colorView.view.adapter
			if (adapter is IViewChange) {
				adapter.onViewChangedListener = {
					val colorData = currentColorData
					updateStyle(colorData,
							colorData.backgroundColorFor(colorView),
							colorData.foregroundColorFor(colorView),
							it,
							colorView.layer + 1,
							colorView.maxDepth)
				}
			} else {
				colorView.view.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
					override fun onChildViewRemoved(parent: View, child: View) {}

					override fun onChildViewAdded(parent: View, child: View) {
						val colorData = currentColorData
						updateStyle(colorData,
								colorData.backgroundColorFor(colorView),
								colorData.foregroundColorFor(colorView),
								child,
								colorView.layer + 1,
								colorView.maxDepth)
					}
				})
			}
		}
		watchView(colorView)
	}

	/**
	 * Stop watching [ColorView] based on predicate. This allows more advanced and unpredictable ColorView removals.
	 * Only the first [ColorView] that matches the predicate will be removed.
	 */
	fun stopWatchingView(predicate: (ColorView) -> Boolean) {
		synchronized(watchedViewList) {
			val index = watchedViewList.indexOfFirst(predicate)
			if (index >= 0) watchedViewList.removeAt(index)
		}
	}

	/**
	 * Request to stop watching view.
	 *
	 * @param view RootView of ColorView to remove. No child lookups are performed.
	 */
	fun stopWatchingView(view: View) {
		stopWatchingView { it.view == view }
	}

	/**
	 * Request to stop watching view with given id
	 *
	 * @param id Id of the RootView of ColorView. No child lookups are performed.
	 */
	fun stopWatchingView(@IdRes id: Int) {
		stopWatchingView { it.view.id == id }
	}

	/**
	 * Request to stop watching adapter view.
	 * This is required to call if AdapterView was added with [watchRecyclerView] function, otherwise it will not be unsubscribed properly.
	 *
	 * @param view AdapterView to unsubscribe
	 */
	fun stopWatchingRecyclerView(view: RecyclerView) {
		launch(Dispatchers.Main) {
			val adapter = view.adapter
			if (adapter is IViewChange) {
				adapter.onViewChangedListener = null
			} else {
				view.setOnHierarchyChangeListener(null)
			}
		}
		stopWatchingView(view)
	}

	/**
	 * Request to stop watching adapter view.
	 * This is required to call if AdapterView was added with [watchRecyclerView] function, otherwise it will not be unsubscribed properly.
	 *
	 * @param id Id of the AdapterView to unsubscribe
	 */
	fun stopWatchingRecyclerView(@IdRes id: Int) {
		synchronized(watchedViewList) {
			val index = watchedViewList.indexOfFirst { it.view.id == id }
			if (index >= 0) {
				(watchedViewList[index].view as ViewGroup).setOnHierarchyChangeListener(null)
				watchedViewList.removeAt(index)
			}
		}
	}

	/**
	 * Triggers cleanup of all watched [ColorView] and [ColorListener] removing them from watch lists.
	 */
	fun cleanup() {
		synchronized(watchedViewList) {
			watchedViewList.clear()
		}

		synchronized(colorChangeListeners) {
			colorChangeListeners.clear()
		}
	}

	/**
	 * Adds color listener which is called on change. It is not guaranteed to be called on UI thread.
	 * For views [watchView] should be used.
	 * Listener returns only luminance and background color
	 */
	fun addListener(colorListener: ColorListener) {
		synchronized(colorChangeListeners) {
			colorChangeListeners.add(colorListener)
			colorListener.invoke(currentColorData)
		}
	}

	/**
	 * Removes color listener
	 */
	fun removeListener(colorListener: ColorListener) {
		synchronized(colorChangeListeners) {
			colorChangeListeners.remove(colorListener)
		}
	}

	/**
	 * Internal update function which should be called only by ColorManager
	 */
	internal fun update(colorData: ColorData) {
		if (isSuspended) {
			updateRequestedWhileSuspended = true
			return
		}

		launch(Dispatchers.Main) {
			synchronized(watchedViewList) {
				watchedViewList.forEach { colorView ->
					updateInternal(colorView, colorData)
				}
			}
		}
		synchronized(colorChangeListeners) {
			colorChangeListeners.forEach { it.invoke(colorData) }
		}
	}

	private fun updateInternal(colorView: ColorView, colorData: ColorData) {
		val backgroundColor = colorData.backgroundColorFor(colorView)
		val foregroundColor = colorData.foregroundColorFor(colorView)

		launch(Dispatchers.Main) {
			updateStyle(colorData, backgroundColor, foregroundColor, colorView.view, colorView.layer, colorView.maxDepth)
		}
	}

	@MainThread
	private fun updateStyle(colorData: ColorData,
	                        @ColorInt backgroundColor: Int,
	                        @ColorInt foregroundColor: Int,
	                        view: View,
	                        layer: Int,
	                        depthLeft: Int) {
		var newLayer = layer

		val wasBackgroundUpdated = updateBackgroundDrawable(view, layerColor(backgroundColor, layer))
		if (wasBackgroundUpdated) newLayer++

		if (view is ViewGroup) {
			val newDepthLeft = depthLeft - 1
			if (newDepthLeft < 0) return

			for (i in 0 until view.childCount) {
				updateStyle(colorData, backgroundColor, foregroundColor, view.getChildAt(i), newLayer, newDepthLeft)
			}
		} else {
			updateStyleForeground(view, foregroundColor)
		}
	}

	@MainThread
	private fun updateStyleForeground(drawable: Drawable, @ColorInt foregroundColor: Int) {
		drawable.setTint(foregroundColor)
	}

	@MainThread
	private fun updateStyleForeground(view: TextView, @ColorInt foregroundColor: Int) {
		if (view is CheckBox) {
			view.buttonTintList = ColorStateList.valueOf(foregroundColor)
		}

		val alpha = view.currentTextColor.alpha
		val newTextColor = ColorUtils.setAlphaComponent(foregroundColor, alpha)
		view.setTextColor(newTextColor)
		view.setHintTextColor(brightenColor(newTextColor, 1))
		view.compoundDrawables.forEach { if (it != null) updateStyleForeground(it, foregroundColor) }
	}

	@MainThread
	private fun updateStyleForeground(view: SeekBar, @ColorInt foregroundColor: Int) {
		view.thumbTintList = ColorStateList(
				arrayOf(
						intArrayOf(-android.R.attr.state_enabled),
						intArrayOf(android.R.attr.state_enabled),
						intArrayOf(android.R.attr.state_pressed)
				),
				intArrayOf(
						ColorUtils.setAlphaComponent(foregroundColor, 128),
						foregroundColor,
						ColorUtils.setAlphaComponent(foregroundColor, 255)))
	}

	@MainThread
	private fun updateStyleForeground(view: RecyclerView, @ColorInt foregroundColor: Int) {
		for (i in 0 until view.itemDecorationCount) {
			when (val decoration = view.getItemDecorationAt(i)) {
				is DividerItemDecoration -> {
					val drawable = decoration.drawable
					if (drawable != null) updateStyleForeground(drawable, foregroundColor)
				}
			}
		}
	}

	@MainThread
	private fun updateStyleForeground(view: View, @ColorInt foregroundColor: Int) {
		when (view) {
			is ColorableView -> view.onColorChanged(currentColorData)
			is ImageView -> view.setColorFilter(foregroundColor)
			is TextView -> updateStyleForeground(view, foregroundColor)
			is RecyclerView -> updateStyleForeground(view, foregroundColor)
			is SeekBar -> updateStyleForeground(view, foregroundColor)
		}
	}

	@MainThread
	private fun updateBackgroundDrawable(view: View, @ColorInt bgColor: Int): Boolean {
		val background = view.background
		if (view is androidx.cardview.widget.CardView) {
			view.setCardBackgroundColor(bgColor)
			return true
		} else if (background?.isVisible == true) {
			if (background.alpha < 255) return false

			background.setTint(bgColor)
			background.colorFilter = if (Build.VERSION.SDK_INT >= 29) {
				BlendModeColorFilter(bgColor, BlendMode.SRC_IN)
			} else {
				@Suppress("DEPRECATION")
				PorterDuffColorFilter(bgColor, PorterDuff.Mode.SRC_IN)
			}
			return true
		}
		return false
	}
}