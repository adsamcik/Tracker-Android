package com.adsamcik.signalcollector.common.color

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.IdRes
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.alpha
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.color.ColorManager.backgroundColorFor
import com.adsamcik.signalcollector.common.color.ColorManager.foregroundColorFor
import com.adsamcik.signalcollector.common.color.ColorManager.layerColor
import com.adsamcik.signalcollector.common.misc.extension.contains
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

//Cannot be annotated with ColorInt yet
typealias ColorListener = (luminance: Byte, foregroundColor: Int, backgroundColor: Int) -> Unit

/**
 * ColorController class that handles color updates of views in a given Activity or Fragment
 */
class ColorController {
	private val watchedViews = ArrayList<ColorView>(5)

	/**
	 * Colors listener array. Holds all listeners.
	 *
	 */
	private val colorChangeListeners = ArrayList<ColorListener>(0)

	/**
	 * Add given [colorView] to the list of watched Views
	 *
	 */
	fun watchView(colorView: ColorView) {
		synchronized(watchedViews) {
			watchedViews.add(colorView)
		}
		updateInternal(colorView)
	}

	/**
	 * Notifies [ColorController] that change has occurred on given view. View needs to be subscribed to color updates.
	 * It is recommended to pass root View of ColorView because it does not trigger recursive lookup.
	 *
	 * @param view root View of ColorView
	 */
	fun notifyChangeOn(view: View) {
		var find: ColorView? = null
		synchronized(watchedViews) {
			find = watchedViews.find { it.view == view }
			if (find == null)
				find = watchedViews.find { it.view.contains(view) }
		}

		if (find != null)
			updateInternal(find!!)
		else
			throw IllegalArgumentException("View is not subscribed")
	}

	/**
	 * Add given [ColorView] that must derive from [AdapterView] to the list of watched view. Provides additional support for recycling so recycled views are styled properly.
	 *
	 * Adapter needs to implement [IViewChange] interface for the best and most reliable color updating.
	 * However it will somehow work even without it, but it might not be reliable.
	 */
	fun watchAdapterView(colorView: ColorView) {
		if (!colorView.recursive)
			throw RuntimeException("Recycler view cannot be non recursive")

		colorView.view as RecyclerView
		val adapter = colorView.view.adapter
		if (adapter is IViewChange) {
			adapter.onViewChangedListener = {
				updateStyleRecursive(it, backgroundColorFor(colorView), foregroundColorFor(colorView), colorView.layer + 1)
			}
		} else {
			colorView.view.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
				override fun onChildViewRemoved(parent: View, child: View) {
				}

				override fun onChildViewAdded(parent: View, child: View) {
					updateStyleRecursive(child, backgroundColorFor(colorView), foregroundColorFor(colorView), colorView.layer + 1)
				}

			})
		}
		watchView(colorView)
	}

	/**
	 * Stop watching [ColorView] based on predicate. This allows more advanced and unpredictable ColorView removals.
	 * Only the first [ColorView] that matches the predicate will be removed.
	 */
	fun stopWatchingView(predicate: (ColorView) -> Boolean) {
		synchronized(watchedViews) {
			val index = watchedViews.indexOfFirst(predicate)
			if (index >= 0)
				watchedViews.removeAt(index)
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
	 * This is required to call if AdapterView was added with [watchAdapterView] function, otherwise it will not be unsubscribed properly.
	 *
	 * @param view AdapterView to unsubscribe
	 */
	fun stopWatchingAdapterView(view: AdapterView<*>) {
		val adapter = view.adapter
		if (adapter is IViewChange)
			adapter.onViewChangedListener = null
		else
			view.setOnHierarchyChangeListener(null)
		stopWatchingView(view)
	}

	/**
	 * Request to stop watching adapter view.
	 * This is required to call if AdapterView was added with [watchAdapterView] function, otherwise it will not be unsubscribed properly.
	 *
	 * @param id Id of the AdapterView to unsubscribe
	 */
	fun stopWatchingAdapterView(@IdRes id: Int) {
		synchronized(watchedViews) {
			val index = watchedViews.indexOfFirst { it.view.id == id }
			if (index >= 0) {
				(watchedViews[index].view as ViewGroup).setOnHierarchyChangeListener(null)
				watchedViews.removeAt(index)
			}
		}
	}

	/**
	 * Triggers cleanup of all watched [ColorView] and [ColorListener] removing them from watch lists.
	 */
	fun cleanup() {
		synchronized(watchedViews) {
			watchedViews.clear()
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
			colorListener.invoke(ColorManager.currentLuminance, backgroundColorFor(true), backgroundColorFor(false))
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
	internal fun update() {
		GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT) {
			synchronized(watchedViews) {
				watchedViews.forEach {
					updateInternal(it)
				}
			}
		}

		synchronized(colorChangeListeners) {
			colorChangeListeners.forEach { it.invoke(ColorManager.currentLuminance, backgroundColorFor(true), backgroundColorFor(false)) }
		}
	}

	private fun updateInternal(colorView: ColorView) {
		val backgroundColor = backgroundColorFor(colorView)
		val foregroundColor = foregroundColorFor(colorView)

		if (!colorView.ignoreRoot) {
			val layerColor = layerColor(backgroundColor, colorView.layer)
			if (colorView.rootIsBackground)
				colorView.view.setBackgroundColor(layerColor)
			else
				updateBackgroundDrawable(colorView.view, layerColor)

			updateStyleForeground(colorView.view, foregroundColor)
		}

		if (colorView.recursive && colorView.view is ViewGroup) {
			val layer = if (!colorView.ignoreRoot) colorView.layer + 1 else colorView.layer
			for (i in 0 until colorView.view.childCount)
				updateStyleRecursive(colorView.view.getChildAt(i), backgroundColor, foregroundColor, layer)
		}
	}

	private fun updateStyleRecursive(view: View, @ColorInt color: Int, @ColorInt fgColor: Int, layer: Int) {
		var newLayer = layer
		if (updateBackgroundDrawable(view, layerColor(color, layer)))
			newLayer++
		if (view is ViewGroup) {
			for (i in 0 until view.childCount)
				updateStyleRecursive(view.getChildAt(i), color, fgColor, newLayer)
		} else {
			updateStyleForeground(view, fgColor)
		}
	}

	private fun updateStyleForeground(view: View, @ColorInt fgColor: Int) {
		when (view) {
			is ImageView -> {
				view.setColorFilter(fgColor)
			}
			is TextView -> {
				if (view is CheckBox)
					view.buttonTintList = ColorStateList.valueOf(fgColor)

				val alpha = view.currentTextColor.alpha
				val newTextColor = ColorUtils.setAlphaComponent(fgColor, alpha)
				view.setTextColor(newTextColor)
				view.setHintTextColor(brightenColor(newTextColor, 1))
				view.compoundDrawables.forEach { it?.setTint(fgColor) }
			}
		}
	}

	private fun updateBackgroundDrawable(view: View, @ColorInt bgColor: Int): Boolean {
		val background = view.background
		if (view is androidx.cardview.widget.CardView) {
			view.setCardBackgroundColor(bgColor)
			return true
		} else if (background?.isVisible == true) {
			if (background.alpha < 255)
				return false

			background.setTint(bgColor)
			background.colorFilter = PorterDuffColorFilter(bgColor, PorterDuff.Mode.SRC_IN)
			return true
		}
		return false
	}
}