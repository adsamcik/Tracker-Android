package com.adsamcik.signalcollector.common.style

import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
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
import androidx.core.view.children
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.style.StyleManager.layerColor
import com.adsamcik.signalcollector.common.style.StyleManager.styleData
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

typealias OnStyleChangeListener = (styleData: StyleData) -> Unit

/**
 * StyleController class that handles color updates of views in a given Activity or Fragment.
 */
@AnyThread
//todo add support for local custom Views
//todo refactor so the class is smaller
class StyleController : CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	private var isDisposed = false

	private val viewList = mutableListOf<StyleView>()
	private val recyclerList = mutableListOf<RecyclerStyleView>()

	/**
	 * Colors listener array. Holds all listeners.
	 *
	 */
	private val styleChangeListeners = ArrayList<OnStyleChangeListener>(0)

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
					update(styleData)
				}
			}
		}

	/**
	 * Add given [styleView] to the list of watched Views
	 *
	 */
	fun watchView(styleView: StyleView) {
		synchronized(viewList) {
			viewList.add(styleView)
		}
		updateInternal(styleView, styleData)
	}

	/**
	 * Add given [StyleView] that must derive from [AdapterView] to the list of watched view. Provides additional support for recycling so recycled views are styled properly.
	 *
	 * Adapter needs to implement [IViewChange] interface for the best and most reliable color updating.
	 * However it will somehow work even without it, but it might not be reliable.
	 */
	fun watchRecyclerView(styleView: RecyclerStyleView) {
		synchronized(recyclerList) {
			recyclerList.add(styleView)
		}

		launch(Dispatchers.Main) {
			val adapter = styleView.view.adapter
			if (adapter is IViewChange) {
				adapter.onViewChangedListener = {
					val styleData = styleData
					val backgroundColor = styleData.backgroundColor(styleView.isInverted)
					val foregroundColor = styleData.foregroundColor(styleView.isInverted)
					updateStyle(backgroundColor,
							foregroundColor,
							it,
							styleView.childrenLayer,
							styleView.maxDepth)
				}
			} else {
				styleView.view.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
					override fun onChildViewRemoved(parent: View, child: View) {}

					override fun onChildViewAdded(parent: View, child: View) {
						val styleData = styleData
						updateStyle(styleData.backgroundColor(styleView.isInverted),
								styleData.foregroundColor(styleView.isInverted),
								child,
								styleView.childrenLayer,
								styleView.maxDepth)
					}
				})
			}

			updateInternal(styleView, styleData)
		}
	}

	/**
	 * Stop watching [StyleView] based on predicate. This allows more advanced and unpredictable StyleView removals.
	 * Only the first [StyleView] that matches the predicate will be removed.
	 */
	private fun <T> stopWatching(list: MutableList<T>, predicate: (T) -> Boolean) {
		synchronized(list) {
			val index = list.indexOfFirst(predicate)
			if (index >= 0) list.removeAt(index)
		}
	}

	/**
	 * Stop watching [StyleView] based on predicate. This allows more advanced and unpredictable StyleView removals.
	 * Only the first [StyleView] that matches the predicate will be removed.
	 */
	fun stopWatchingView(predicate: (StyleView) -> Boolean) {
		stopWatching(viewList, predicate)
	}

	/**
	 * Stop watching [StyleView] based on predicate. This allows more advanced and unpredictable StyleView removals.
	 * Only the first [StyleView] that matches the predicate will be removed.
	 */
	fun stopWatchingRecyclerView(predicate: (RecyclerStyleView) -> Boolean) {
		stopWatching(recyclerList, predicate)
	}

	/**
	 * Request to stop watching view.
	 *
	 * @param view RootView of StyleView to remove. No child lookups are performed.
	 */
	fun stopWatchingView(view: View) {
		stopWatchingView { it.view == view }
	}

	/**
	 * Request to stop watching view with given id
	 *
	 * @param id Id of the RootView of StyleView. No child lookups are performed.
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
		stopWatchingRecyclerView { it.view == view }
	}

	/**
	 * Request to stop watching adapter view.
	 * This is required to call if AdapterView was added with [watchRecyclerView] function, otherwise it will not be unsubscribed properly.
	 *
	 * @param id Id of the AdapterView to unsubscribe
	 */
	fun stopWatchingRecyclerView(@IdRes id: Int) {
		synchronized(recyclerList) {
			val index = recyclerList.indexOfFirst { it.view.id == id }
			if (index >= 0) {
				(recyclerList[index].view as ViewGroup).setOnHierarchyChangeListener(null)
				recyclerList.removeAt(index)
			}
		}
	}

	/**
	 * Triggers dispose of all watched [StyleView] and [OnStyleChangeListener] removing them from watch lists.
	 */
	internal fun dispose() {
		synchronized(viewList) {
			viewList.clear()
		}

		synchronized(recyclerList) {
			recyclerList.clear()
		}

		synchronized(styleChangeListeners) {
			styleChangeListeners.clear()
		}

		isDisposed = true
	}

	/**
	 * Adds color listener which is called on change. It is not guaranteed to be called on UI thread.
	 * For views [watchView] should be used.
	 * Listener returns only luminance and background color
	 */
	fun addListener(onStyleChangeListener: OnStyleChangeListener) {
		synchronized(styleChangeListeners) {
			styleChangeListeners.add(onStyleChangeListener)
			onStyleChangeListener.invoke(styleData)
		}
	}

	/**
	 * Removes color listener
	 */
	fun removeListener(onStyleChangeListener: OnStyleChangeListener) {
		synchronized(styleChangeListeners) {
			styleChangeListeners.remove(onStyleChangeListener)
		}
	}

	/**
	 * Internal update function which should be called only by StyleManager
	 */
	internal fun update(styleData: StyleData) {
		if (isSuspended) {
			updateRequestedWhileSuspended = true
			return
		}

		launch(Dispatchers.Main) {
			synchronized(viewList) {
				viewList.forEach { styleView ->
					updateInternal(styleView, styleData)
				}
			}

			synchronized(recyclerList) {
				recyclerList.forEach { styleView ->
					updateInternal(styleView, styleData)
				}
			}
		}
		synchronized(styleChangeListeners) {
			styleChangeListeners.forEach { it.invoke(styleData) }
		}
	}

	private fun updateInternal(styleView: RecyclerStyleView, styleData: StyleData) {
		val backgroundColor = styleData.backgroundColorFor(styleView)
		val foregroundColor = styleData.foregroundColorFor(styleView)

		launch(Dispatchers.Main) {
			updateStyle(styleView, backgroundColor, foregroundColor)
		}
	}

	private fun updateInternal(styleView: StyleView, styleData: StyleData) {
		val backgroundColor = styleData.backgroundColorFor(styleView)
		val foregroundColor = styleData.foregroundColorFor(styleView)

		launch(Dispatchers.Main) {
			updateStyle(backgroundColor, foregroundColor, styleView.view, styleView.layer, styleView.maxDepth)
		}
	}

	@MainThread
	private fun updateStyle(styleData: RecyclerStyleView,
	                        @ColorInt backgroundColor: Int,
	                        @ColorInt foregroundColor: Int) {
		if (!styleData.onlyChildren) {
			updateStyleForeground(styleData.view, foregroundColor)
			updateStyle(backgroundColor, foregroundColor, styleData.view, styleData.layer, 0)
		}

		val iterator = styleData.view.children.iterator()

		for (item in iterator) {
			updateStyle(backgroundColor, foregroundColor, item, styleData.childrenLayer, Int.MAX_VALUE)
		}
	}

	@MainThread
	private fun updateStyle(@ColorInt backgroundColor: Int,
	                        @ColorInt foregroundColor: Int,
	                        view: View,
	                        layer: Int,
	                        depthLeft: Int) {
		var newLayer = layer

		val wasBackgroundUpdated = updateBackgroundDrawable(view, layerColor(backgroundColor, layer))
		if (wasBackgroundUpdated) newLayer++

		if (view is ViewGroup) {
			if (depthLeft <= 0 || view is RecyclerView) return

			val newDepthLeft = depthLeft - 1

			for (i in 0 until view.childCount) {
				updateStyle(backgroundColor, foregroundColor, view.getChildAt(i), newLayer, newDepthLeft)
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
		view.setHintTextColor(brightenColor(newTextColor, LIGHTNESS_PER_LEVEL))
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
			is StyleableView -> view.onStyleChanged(styleData)
			is ImageView -> view.setColorFilter(foregroundColor)
			is TextView -> updateStyleForeground(view, foregroundColor)
			is SeekBar -> updateStyleForeground(view, foregroundColor)
		}
	}

	@MainThread
	private fun updateBackgroundDrawable(view: View, @ColorInt bgColor: Int): Boolean {
		val background = view.background
		when {
			view is MaterialButton -> {
				val nextLevel = brightenColor(bgColor, LIGHTNESS_PER_LEVEL)
				view.rippleColor = ColorStateList.valueOf(nextLevel)
				view.setBackgroundColor(bgColor)
			}
			background?.isVisible == true -> {
				if (background is RippleDrawable) {
					val nextLevel = brightenColor(bgColor, LIGHTNESS_PER_LEVEL)
					background.setColor(Assist.getPressedState(nextLevel))
					background.setTint(bgColor)
				} else {
					if (background.alpha < 255) return false

					background.setTint(bgColor)
					background.colorFilter = if (Build.VERSION.SDK_INT >= 29) {
						BlendModeColorFilter(bgColor, BlendMode.SRC_IN)
					} else {
						@Suppress("DEPRECATION")
						PorterDuffColorFilter(bgColor, PorterDuff.Mode.SRC_IN)
					}
				}
				return true
			}
		}
		return false
	}

	companion object {
		const val LIGHTNESS_PER_LEVEL = 25
	}
}