package com.adsamcik.tracker.common.style

import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.annotation.AnyThread
import androidx.annotation.IdRes
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.common.style.StyleManager.styleData
import com.adsamcik.tracker.common.style.marker.IViewChange
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
@Suppress("Unused", "WeakerAccess")
class StyleController : CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	private var isDisposed = false

	private val viewList = mutableListOf<StyleView>()
	private val recyclerList = mutableListOf<RecyclerStyleView>()

	private val styleUpdater = StyleUpdater()

	/**
	 * Colors listener array. Holds all listeners.
	 *
	 */
	private val styleChangeListeners = mutableListOf<OnStyleChangeListener>()

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
		styleUpdater.updateSingle(styleView, styleData)
	}

	/**
	 * Add given [StyleView] that must derive from [AdapterView] to the list of watched view.
	 * Provides additional support for recycling so recycled views are styled properly.
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
					//todo consider wrapping this in an object
					val backgroundColor = styleData.backgroundColorFor(styleView)
					val foregroundColor = styleData.foregroundColorFor(styleView)
					val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)
					styleUpdater.updateSingle(
							backgroundColor,
							foregroundColor,
							perceivedLuminance,
							it,
							styleView.childrenLayer,
							styleView.maxDepth
					)
				}
			} else {
				styleView.view.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
					override fun onChildViewRemoved(parent: View, child: View) = Unit

					override fun onChildViewAdded(parent: View, child: View) {
						val styleData = styleData
						styleUpdater.updateSingle(
								styleData.backgroundColorFor(styleView),
								styleData.foregroundColorFor(styleView),
								styleData.perceivedLuminanceFor(styleView),
								child,
								styleView.childrenLayer,
								styleView.maxDepth
						)
					}
				})
			}

			styleUpdater.updateSingle(styleView, styleData)
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
	 * This is required to call if AdapterView was added with [watchRecyclerView] function,
	 * otherwise it will not be unsubscribed properly.
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
	 * This is required to call if AdapterView was added with [watchRecyclerView] function,
	 * otherwise it will not be unsubscribed properly.
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

		synchronized(viewList) {
			viewList.forEach { styleView ->
				styleUpdater.updateSingle(styleView, styleData)
			}
		}

		synchronized(recyclerList) {
			recyclerList.forEach { styleView ->
				styleUpdater.updateSingle(styleView, styleData)
			}
		}

		synchronized(styleChangeListeners) {
			styleChangeListeners.forEach { it.invoke(styleData) }
		}
	}
}

