package com.adsamcik.signalcollector.common.misc

import android.os.Handler
import android.view.View
import androidx.annotation.IntDef
import androidx.annotation.StringRes
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.*
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * SnackMaker class is builder and manager class for Snackbars.
 * It can properly queue multiple Snackbars and display them in order.
 */
class SnackMaker(view: View) {
	@Retention(AnnotationRetention.SOURCE)
	@IntDef(LENGTH_INDEFINITE, LENGTH_LONG, LENGTH_SHORT)
	annotation class SnackDuration

	private val weakView: WeakReference<View> = WeakReference(view)

	private val queue = ConcurrentLinkedQueue<SnackbarRecipe>()
	private var current: Snackbar? = null
	private var handler: Handler? = null

	/**
	 * Adds message to SnackMaker queue
	 *
	 * @param message Message
	 * @param duration Duration that has to be one of [SnackDuration] values
	 */
	fun addMessage(message: String, @SnackDuration duration: Int = LENGTH_LONG, priority: SnackbarPriority = SnackbarPriority.QUEUE) {
		addSnackbar(SnackbarRecipe(message, duration, priority))
	}

	/**
	 * Adds message to SnackMaker queue
	 *
	 * @param messageRes Message resource
	 * @param duration Duration that has to be one of [SnackDuration] values
	 */
	fun addMessage(@StringRes messageRes: Int, @SnackDuration duration: Int = LENGTH_LONG, priority: SnackbarPriority = SnackbarPriority.QUEUE) {
		val message = weakView.get()!!.context.getString(messageRes)
		addSnackbar(SnackbarRecipe(message, duration, priority))
	}

	/**
	 * Adds message with action to SnackMaker queue
	 *
	 * @param messageRes Message string resource
	 * @param duration Duration that has to be one of [SnackDuration] values
	 * @param actionRes Action string resource
	 * @param onActionClick On action click listener
	 */
	fun addMessage(@StringRes messageRes: Int, @SnackDuration duration: Int = LENGTH_LONG, priority: SnackbarPriority, @StringRes actionRes: Int, onActionClick: View.OnClickListener) {
		val resources = weakView.get()!!.context.resources
		val action = resources.getString(actionRes)
		val message = resources.getString(messageRes)
		addSnackbar(SnackbarRecipe(message, duration, priority, action, onActionClick))
	}

	private fun addSnackbar(snackbarRecipe: SnackbarRecipe) {
		when (snackbarRecipe.priority) {
			SnackbarPriority.QUEUE -> {
				queue.add(snackbarRecipe)
				if (current == null)
					next()
			}
			SnackbarPriority.IMPORTANT -> show(weakView.get()!!, snackbarRecipe)
		}

	}

	private fun interrupt() {
		if (current != null) {
			current!!.dismiss()
			current = null
		}
		queue.clear()
		handler = null
	}

	private fun next() {
		if (!queue.isEmpty()) {
			val view = weakView.get()
			if (view == null)
				interrupt()
			else {
				val next = queue.remove()
				current = show(view, next)
			}
		} else
			current = null
	}

	private fun show(view: View, recipe: SnackbarRecipe): Snackbar {
		return make(view, recipe.message, recipe.duration).apply {
			if (recipe.action != null && recipe.onActionClick != null)
				setAction(recipe.action, recipe.onActionClick)

			addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
				override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
					super.onDismissed(transientBottomBar, event)
					next()
				}
			})
			show()
		}
	}


	private data class SnackbarRecipe(val message: String,
	                                  @SnackDuration val duration: Int,
	                                  val priority: SnackbarPriority,
	                                  val action: String? = null,
	                                  val onActionClick: View.OnClickListener? = null)

	enum class SnackbarPriority {
		QUEUE,
		IMPORTANT
	}
}
