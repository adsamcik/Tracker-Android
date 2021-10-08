package com.adsamcik.tracker.shared.utils.module

import android.content.Context
import android.view.View
import androidx.annotation.MainThread
import com.adsamcik.tracker.shared.utils.extension.dynamicStyle
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.afollestad.materialdialogs.MaterialDialog

/**
 * Type alias for on first run done listener.
 */
typealias OnDoneListener = (Context, isCloseRequested: Boolean) -> Unit

/**
 * Abstraction of first run.
 * Ensures code is run only on first run.
 */
@Suppress("unused")
abstract class FirstRun {
	companion object {
		private const val DIALOG_LAYER = 2
	}

	private val styleController = StyleManager.createController()

	/**
	 * Called on first run.
	 *
	 * @param context Context
	 * @param onDoneListener Listener called when the first run is done.
	 */
	abstract fun onFirstRun(context: Context, onDoneListener: OnDoneListener)

	/**
	 * Creates styled dialog
	 *
	 * @param context Context
	 * @param creator Dialog creator
	 */
	@MainThread
	protected fun createDialog(
			context: Context,
			creator: MaterialDialog.() -> Unit
	) {
		MaterialDialog(context)
				.apply(creator)
				.also {
					it.cancelable(false)
					it.dynamicStyle(DIALOG_LAYER)
					it.show()
				}
	}

	/**
	 * Notifies the [com.adsamcik.tracker.shared.utils.style.StyleController] that view has changed.
	 */
	protected fun notifyContentChanged(view: View) {
		styleController.updateOnce(
				StyleView(
						view,
						DIALOG_LAYER
				), allowRecycler = false
		)
	}

	protected fun onDestroy() {
		StyleManager.recycleController(styleController)
	}
}
