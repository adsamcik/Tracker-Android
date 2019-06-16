package com.adsamcik.signalcollector.common.color

import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.InvalidClassException

typealias ColorListener = (colorData: ColorData) -> Unit

data class ColorData(@ColorInt val baseColor: Int, @ColorInt val foregroundColor: Int, private val baseColorHSL: FloatArray, val perceivedLuminance: Byte) {

	val luminance get() = baseColorHSL[2]
	val saturation get() = baseColorHSL[1]
	val hue get() = baseColorHSL[0]

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as ColorData

		if (baseColor != other.baseColor) return false
		if (foregroundColor != other.foregroundColor) return false
		if (!baseColorHSL.contentEquals(other.baseColorHSL)) return false
		if (perceivedLuminance != other.perceivedLuminance) return false

		return true
	}

	override fun hashCode(): Int {
		var result = baseColor
		result = 31 * result + foregroundColor
		result = 31 * result + baseColorHSL.contentHashCode()
		result = 31 * result + perceivedLuminance
		return result
	}
}

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
					?: watchedViews.find { it.view.contains(view) }
		}

		if (find != null) {
			updateInternal(find!!)
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

		val adapter = colorView.view.adapter
		if (adapter is IViewChange) {
			adapter.onViewChangedListener = {
				updateStyle(it, colorView.layer + 1, colorView.maxDepth, backgroundColorFor(colorView), foregroundColorFor(colorView))
			}
		} else {
			colorView.view.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
				override fun onChildViewRemoved(parent: View, child: View) {}

				override fun onChildViewAdded(parent: View, child: View) {
					updateStyle(child, colorView.layer + 1, colorView.maxDepth, backgroundColorFor(colorView), foregroundColorFor(colorView))
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
			if (index >= 0) watchedViews.removeAt(index)
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
		val adapter = view.adapter
		if (adapter is IViewChange) {
			adapter.onViewChangedListener = null
		} else {
			view.setOnHierarchyChangeListener(null)
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
			colorListener.invoke(ColorManager.currentColorData)
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
		GlobalScope.launch(Dispatchers.Main) {
			synchronized(watchedViews) {
				watchedViews.forEach {
					updateInternal(it)
				}
			}
		}

		val colorData = ColorManager.currentColorData

		synchronized(colorChangeListeners) {
			colorChangeListeners.forEach { it.invoke(colorData) }
		}
	}

	private fun updateInternal(colorView: ColorView) {
		val backgroundColor = backgroundColorFor(colorView)
		val foregroundColor = foregroundColorFor(colorView)

		updateStyle(colorView.view, colorView.layer, colorView.maxDepth, backgroundColor, foregroundColor)
	}

	private fun updateStyle(view: View, layer: Int, depthLeft: Int, @ColorInt color: Int, @ColorInt fgColor: Int) {
		var newLayer = layer
		if (updateBackgroundDrawable(view, layerColor(color, layer))) newLayer++

		if (view is ViewGroup) {
			val newDepthLeft = depthLeft - 1
			if (newDepthLeft < 0) return

			for (i in 0 until view.childCount) {
				updateStyle(view.getChildAt(i), newLayer, newDepthLeft, color, fgColor)
			}
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
				if (view is CheckBox) {
					view.buttonTintList = ColorStateList.valueOf(fgColor)
				}

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