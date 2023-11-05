package com.adsamcik.tracker.shared.utils.extension

import android.view.ViewGroup
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.list.getRecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
        styleController.addListener { styleData ->
            view.buttonsLayout?.actionButtons?.forEach {
                it.post {
                    it.updateTextColor(styleData.foregroundColor())
                }
            }
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
        // it's fine, just don't add it to recycler
    }

    applyStyle(styleController)

    onDismiss {
        StyleManager.recycleController(styleController)
    }

    return this
}

/**
 * Applies dynamic styling to an [AlertDialog].
 *
 * This function applies both base and additional styling to the dialog. It is designed
 * to operate on dialogs created using Material Components' [AlertDialog].
 *
 * @param layer The style layer to apply. This parameter might influence z-order,
 * elevation, etc. depending on the implementation in [StyleController].
 * @param applyStyle A lambda function allowing further customization of the
 * [StyleController]. Default is an empty lambda.
 *
 * @return The styled [AlertDialog] instance for chaining calls.
 */
inline fun AlertDialog.dynamicStyle(
    layer: Int = 1,
    applyStyle: (StyleController) -> Unit = {}
): AlertDialog {
    return dynamicBaseStyle(layer) { styleController ->
        val decorView = window?.decorView
        if (decorView != null) {
            styleController.watchView(StyleView(decorView, layer))
        }
        styleController.addListener { styleData ->
            getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                this.setTextColor(styleData.foregroundColor())
            }
            getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                this.setTextColor(styleData.foregroundColor())
            }
            getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                this.setTextColor(styleData.foregroundColor())
            }
        }
        applyStyle(styleController)
    }
}


/**
 * Applies base dynamic styling to an [AlertDialog].
 *
 * This function applies base styling to a dialog and manages the lifecycle of the
 * associated [StyleController]. It is designed to operate on dialogs created using
 * Material Components' [AlertDialog].
 *
 * @param layer The style layer to apply. This parameter might influence z-order,
 * elevation, etc. depending on the implementation in [StyleController].
 * @param applyStyle A lambda function allowing further customization of the
 * [StyleController]. This parameter is mandatory to facilitate custom styling logic.
 *
 * @return The styled [AlertDialog] instance for chaining calls.
 */
inline fun AlertDialog.dynamicBaseStyle(
    layer: Int = 1,
    applyStyle: (StyleController) -> Unit
): AlertDialog {
    val styleController = StyleManager.createController()

    val contentView = window?.decorView as? ViewGroup
    val recyclerView = contentView?.findRecyclerView()
    if (recyclerView != null) {
        styleController.watchRecyclerView(RecyclerStyleView(recyclerView, layer))
    }

    applyStyle(styleController)

    setOnDismissListener {
        StyleManager.recycleController(styleController)
    }

    return this
}


fun ViewGroup.findRecyclerView(): RecyclerView? {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child is RecyclerView) {
            return child
        } else if (child is ViewGroup) {
            val recyclerView = child.findRecyclerView()
            if (recyclerView != null) {
                return recyclerView
            }
        }
    }
    return null
}

