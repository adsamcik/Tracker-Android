package com.adsamcik.tracker.shared.utils.extension

import android.content.res.Configuration
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
 * 
 * @deprecated Use Compose dialogs with Material 3 theming instead of legacy StyleController
 */
@Deprecated("Use Compose dialogs with Material 3 theming instead of legacy StyleController")
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
 * 
 * @deprecated Use Compose dialogs with Material 3 theming instead of legacy StyleController
 */
@Deprecated("Use Compose dialogs with Material 3 theming instead of legacy StyleController")
inline fun MaterialDialog.dynamicBaseStyle(
    layer: Int = 1,
    applyStyle: (StyleController) -> Unit
): MaterialDialog {
    @Suppress("DEPRECATION")
    val styleController = StyleManager.createController()
    try {
        val recycler = getRecyclerView()
        @Suppress("DEPRECATION")
        styleController.watchRecyclerView(RecyclerStyleView(recycler, layer))
    } catch (e: IllegalStateException) {
        // it's fine, just don't add it to recycler
    }

    applyStyle(styleController)

    onDismiss {
        @Suppress("DEPRECATION")
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
 * 
 * @deprecated Use Compose dialogs with Material 3 theming instead of legacy StyleController
 */
@Deprecated("Use Compose dialogs with Material 3 theming instead of legacy StyleController")
inline fun AlertDialog.dynamicStyle(
    layer: Int = 1,
    applyStyle: (StyleController) -> Unit = {}
): AlertDialog {
    return dynamicBaseStyle(layer) { styleController ->
        val decorView = window?.decorView
        if (decorView != null) {
            @Suppress("DEPRECATION")
            styleController.watchView(StyleView(decorView, layer))
        }
        @Suppress("DEPRECATION")
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
 * 
 * @deprecated Use Compose dialogs with Material 3 theming instead of legacy StyleController
 */
@Deprecated("Use Compose dialogs with Material 3 theming instead of legacy StyleController")
inline fun AlertDialog.dynamicBaseStyle(
    layer: Int = 1,
    applyStyle: (StyleController) -> Unit
): AlertDialog {
    @Suppress("DEPRECATION")
    val styleController = StyleManager.createController()

    val contentView = window?.decorView as? ViewGroup
    val recyclerView = contentView?.findRecyclerView()
    if (recyclerView != null) {
        @Suppress("DEPRECATION")
        styleController.watchRecyclerView(RecyclerStyleView(recyclerView, layer))
    }

    applyStyle(styleController)

    setOnDismissListener {
        @Suppress("DEPRECATION")
        StyleManager.recycleController(styleController)
    }

    return this
}

/**
 * Simple theming utility for dialogs that automatically adapts to dark/light mode.
 * Use this instead of deprecated StyleController-based methods.
 */
fun AlertDialog.applyMaterial3Theme(): AlertDialog {
    val isDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    // Material 3 automatically handles theming, so this is mostly for consistency
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

