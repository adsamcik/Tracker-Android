package com.adsamcik.tracker.common.misc

import android.content.res.Resources
import android.os.Handler
import android.view.View
import androidx.annotation.AnyThread
import androidx.annotation.IntDef
import androidx.annotation.StringRes
import com.adsamcik.tracker.common.extension.require
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
import com.google.android.material.snackbar.Snackbar.make
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

/**
 * SnackMaker class is builder and manager class for Snackbars.
 * It can properly queue multiple Snackbars and display them in order.
 */
@AnyThread
class SnackMaker(view: View) : CoroutineScope {
	@Retention(AnnotationRetention.SOURCE)
	@IntDef(LENGTH_INDEFINITE, LENGTH_LONG, LENGTH_SHORT)
	annotation class SnackDuration

	private val weakView: WeakReference<View> = WeakReference(view)
	private val resources: Resources = view.resources

	private val queue = ConcurrentLinkedQueue<SnackbarRecipe>()
	private var current: Snackbar? = null
	private var handler: Handler? = null

	private val lock: ReentrantLock = ReentrantLock()

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	/**
	 * Adds message to SnackMaker queue.
	 *
	 * @param message Message
	 * @param duration Duration that has to be one of [SnackDuration] values
	 */
	fun addMessage(message: String, @SnackDuration duration: Int = LENGTH_LONG,
	               priority: SnackbarPriority = SnackbarPriority.QUEUE) {
		addSnackbar(SnackbarRecipe(message, duration, priority))
	}

	/**
	 * Adds message to SnackMaker queue.
	 *
	 * @param messageRes Message resource
	 * @param duration Duration that has to be one of [SnackDuration] values
	 */
	fun addMessage(@StringRes messageRes: Int, @SnackDuration duration: Int = LENGTH_LONG, priority: SnackbarPriority = SnackbarPriority.QUEUE) {
		val message = resources.getString(messageRes)
		addSnackbar(SnackbarRecipe(message, duration, priority))
	}

	/**
	 * Adds message with action to SnackMaker queue.
	 *
	 * @param messageRes Message string resource
	 * @param duration Duration that has to be one of [SnackDuration] values
	 * @param actionRes Action string resource
	 * @param onActionClick On action click listener
	 */
	fun addMessage(@StringRes messageRes: Int,
	               @SnackDuration duration: Int = LENGTH_LONG,
	               priority: SnackbarPriority,
	               @StringRes actionRes: Int,
	               onActionClick: View.OnClickListener
	) {
		val action = resources.getString(actionRes)
		val message = resources.getString(messageRes)
		addSnackbar(SnackbarRecipe(message, duration, priority, action, onActionClick))
	}

	fun addMessage(recipe: SnackbarRecipe) {
		addSnackbar(recipe)
	}

	private fun addSnackbar(snackbarRecipe: SnackbarRecipe) {
		when (snackbarRecipe.priority) {
			SnackbarPriority.QUEUE -> {
				queue.add(snackbarRecipe)
				lock.withLock {
					if (current == null) {
						next()
					}
				}
			}
			SnackbarPriority.IMPORTANT -> show(weakView.require(), snackbarRecipe)
		}

	}

	private fun interrupt() {
		var current: Snackbar? = null

		lock.withLock {
			current = this.current
			this.current = null
			handler = null
		}

		current?.dismiss()

		queue.clear()
	}

	private fun next() {
		if (!queue.isEmpty()) {
			val view = weakView.get()
			if (view == null) {
				interrupt()
			} else {
				val next = queue.remove()
				lock.withLock { current = show(view, next) }
			}
		} else {
			lock.withLock { current = null }
		}
	}

	private fun show(view: View, recipe: SnackbarRecipe): Snackbar {
		return make(view, recipe.message, recipe.duration).apply {
			if (recipe.action != null && recipe.onActionClick != null) {
				setAction(recipe.action, recipe.onActionClick)
			}

			addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
				override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
					super.onDismissed(transientBottomBar, event)

					if (event != DISMISS_EVENT_ACTION) {
						recipe.onDismissed?.invoke()
					}

					next()
				}
			})
			show()
		}
	}


	data class SnackbarRecipe(val message: String,
	                          @SnackDuration val duration: Int,
	                          val priority: SnackbarPriority,
	                          val action: String? = null,
	                          val onActionClick: View.OnClickListener? = null,
	                          val onDismissed: (() -> Unit)? = null
	)

	enum class SnackbarPriority {
		QUEUE,
		IMPORTANT
	}
}

