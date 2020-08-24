package com.adsamcik.tracker.shared.utils.extension

import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.list.getRecyclerView

/**
 * Add dialog to [StyleController] watch list.
 * Automatically removes dialog when closed.
 */
fun MaterialDialog.dynamicStyle(styleController: StyleController, layer: Int = 1): MaterialDialog {
	try {
		val recycler = getRecyclerView()
		styleController.watchRecyclerView(RecyclerStyleView(recycler, layer))
	} catch (e: IllegalStateException) {
		//it's fine, just don't add it to recycler
	}

	styleController.watchView(StyleView(view, layer))

	onDismiss {
		styleController.stopWatchingView(view)
		try {
			styleController.stopWatchingRecyclerView(getRecyclerView())
		} catch (e: IllegalStateException) {
			//it's fine, just don't remove recycler
		}
	}

	return this
}

/**
 * Creates new [StyleController] for the dialog and manages it's lifecycle.
 * Automatically removes dialog when closed.
 */
fun MaterialDialog.dynamicStyle(layer: Int = 1): MaterialDialog {
	val styleController = StyleManager.createController()
	try {
		val recycler = getRecyclerView()
		styleController.watchRecyclerView(RecyclerStyleView(recycler, layer))
	} catch (e: IllegalStateException) {
		//it's fine, just don't add it to recycler
	}

	styleController.watchView(StyleView(view, layer))

	onDismiss {
		StyleManager.recycleController(styleController)
	}

	return this
}
