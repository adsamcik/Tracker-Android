package com.adsamcik.signalcollector.uitools

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Assist.navbarSize


typealias KeyboardListener = (state: Boolean, keyboardHeight: Int) -> Unit

/**
 * Manages access to the Android soft keyboard.
 */
class KeyboardManager(private val rootView: View) {
    private val listeners = ArrayList<KeyboardListener>()
    private var wasOpen = false
    private var keyboardHeight = 0
    private var defaultDiff = 0

    private val threshold = KEYBOARD_VISIBLE_THRESHOLD_DP.dpAsPx

    private val r = Rect()

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val heightDiff = calculateHeightDiff()
        val isOpen = heightDiff > threshold

        if (isOpen != wasOpen) {
            keyboardHeight = if (isOpen)
                heightDiff - defaultDiff
            else
                0

            wasOpen = isOpen
            listeners.forEach { it.invoke(isOpen, keyboardHeight) }
        }
    }

    init {
        calculateDefaultDiff()
        rootView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    private fun calculateHeightDiff(): Int {
        rootView.getWindowVisibleDisplayFrame(r)
        return rootView.rootView.height - r.height()
    }

    private fun calculateDefaultDiff() {
        defaultDiff = calculateHeightDiff() - navbarSize(rootView.context).second.y
    }

    fun onDisplaySizeChanged() {
        calculateDefaultDiff()
        layoutListener.onGlobalLayout()
        listeners.forEach { it.invoke(wasOpen, keyboardHeight) }
    }

    fun addKeyboardListener(listener: KeyboardListener) {
        listeners.add(listener)
        listener.invoke(wasOpen, keyboardHeight)
    }

    fun removeKeyboardListener(listener: KeyboardListener) = listeners.remove(listener)

    fun removeAllListeners() = listeners.clear()

    fun keyboardOpen() = wasOpen

    fun closeKeyboard() {
        if (wasOpen) {
            val imm = rootView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(rootView.windowToken, 0)
            listeners.forEach { it.invoke(false, 0) }
            wasOpen = false
        }
    }

    companion object {
        private const val KEYBOARD_VISIBLE_THRESHOLD_DP = 100
    }
}