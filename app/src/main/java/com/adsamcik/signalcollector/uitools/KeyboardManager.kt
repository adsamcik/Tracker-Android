package com.adsamcik.signalcollector.uitools

import android.graphics.Rect
import android.view.View


typealias KeyboardListener = (state: Boolean) -> Unit

/**
 * Manages access to the Android soft keyboard.
 */
class KeyboardManager(private val rootView: View) {
    private val listeners = ArrayList<KeyboardListener>()
    private var state = KeyboardStatus.CLOSED

    fun addKeyboardListener(listener: KeyboardListener) = listeners.add(listener)
    fun removeKeyboardListener(listener: KeyboardListener) = listeners.remove(listener)

    init {
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            rootView.getWindowVisibleDisplayFrame(r)

            val heightDiff = rootView.rootView.height - (r.bottom - r.top)
            if (heightDiff > rootView.rootView.height / 4) {
                if (state == KeyboardStatus.CLOSED)
                    onShow()
            } else if (state == KeyboardStatus.OPEN)
                onHide()
        }
    }

    private fun onShow() {
        state = KeyboardStatus.OPEN
        listeners.forEach { it.invoke(true) }
    }

    private fun onHide() {
        state = KeyboardStatus.CLOSED
        listeners.forEach { it.invoke(false) }
    }
}

enum class KeyboardStatus {
    OPEN, CLOSED
}