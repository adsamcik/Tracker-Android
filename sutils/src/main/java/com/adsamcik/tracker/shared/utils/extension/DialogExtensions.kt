package com.adsamcik.tracker.shared.utils.extension

import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.list.getRecyclerView

/**
 * Creates new [StyleController] for the dialog and manages it's lifecycle.
 * Automatically removes dialog when closed.
 */
inline fun MaterialDialog.dynamicStyle(
		layer: Int = 1,
		applyStyle: (StyleController) -> Unit = {}
): MaterialDialog {
	return dynamicBaseStyle(layer) { styleController ->
		styleController.watchView(StyleView(view, layer))
		view.buttonsLayout?.actionButtons?.forEach {
			it.background = Assist.getBackgroundDrawable(ColorConstants.WHITE)
		}
		applyStyle(styleController)
	}
}


/**
 * Creates new [StyleController] for the dialog and manages it's lifecycle.
 * Automatically removes dialog when closed.
 */
inline fun MaterialDialog.dynamicBaseStyle(
		layer: Int = 1,
		applyStyle: (StyleController) -> Unit
): MaterialDialog {
	val styleController = StyleManager.createController()
	try {
		val recycler = getRecyclerView()
		styleController.watchRecyclerView(RecyclerStyleView(recycler, layer))
	} catch (e: IllegalStateException) {
		//it's fine, just don't add it to recycler
	}

	applyStyle(styleController)

	onDismiss {
		StyleManager.recycleController(styleController)
	}

	return this
}
