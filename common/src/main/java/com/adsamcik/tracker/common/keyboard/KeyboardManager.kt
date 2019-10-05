package com.adsamcik.tracker.common.keyboard

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import com.adsamcik.tracker.common.assist.DisplayAssist.getNavigationBarSize
import com.adsamcik.tracker.common.debug.Reporter
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.common.extension.inputMethodManager


typealias KeyboardListener = (state: Boolean, keyboardHeight: Int) -> Unit

/**
 * Manages access to the Android soft keyboard.
 */
class KeyboardManager(private val rootView: View) {
	private val listeners = mutableListOf<KeyboardListener>()
	private var wasOpen = false
	private var keyboardHeight = 0
	private var defaultDiff = 0
	private var isEnabled = false

	private val threshold = KEYBOARD_VISIBLE_THRESHOLD_DP.dp

	private val r = Rect()

	private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
		val heightDiff = calculateHeightDiff()
		val isOpen = heightDiff > threshold

		if (isOpen != wasOpen) {
			keyboardHeight = if (isOpen) {
				heightDiff - defaultDiff
			} else {
				0
			}

			wasOpen = isOpen
			listeners.forEach { it.invoke(isOpen, keyboardHeight) }
		}
	}

	init {
		calculateDefaultDiff()
	}

	fun onEnable() {
		isEnabled = true
		if (listeners.isNotEmpty()) {
			addLayoutListener()
		}
	}

	fun onDisable() {
		if (listeners.isNotEmpty()) {
			removeLayoutListener()
		}
		isEnabled = false
	}

	private fun addLayoutListener() {
		if (isEnabled) {
			onDisplaySizeChanged()
			rootView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
		} else {
			Reporter.report("Called when not enabled")
		}
	}

	private fun removeLayoutListener() {
		if (isEnabled) {
			rootView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
		} else {
			Reporter.report("Called when not enabled")
		}
	}

	private fun calculateHeightDiff(): Int {
		rootView.getWindowVisibleDisplayFrame(r)
		return rootView.rootView.height - r.height()
	}

	private fun calculateDefaultDiff() {
		defaultDiff = calculateHeightDiff() - getNavigationBarSize(rootView.context).second.y
	}

	/**
	 * Needs to be called when either rotation occurs or display size is changed for some other reason.
	 */
	fun onDisplaySizeChanged() {
		calculateDefaultDiff()
		layoutListener.onGlobalLayout()
		listeners.forEach { it.invoke(wasOpen, keyboardHeight) }
	}

	/**
	 * Adds keyboard listener to active listeners
	 */
	fun addKeyboardListener(listener: KeyboardListener) {
		if (isEnabled && listeners.isEmpty()) {
			addLayoutListener()
		}

		listeners.add(listener)
		listener.invoke(wasOpen, keyboardHeight)
	}

	/**
	 * Removes keyboard listener from active listeners
	 */
	fun removeKeyboardListener(listener: KeyboardListener): Boolean {
		val removed = listeners.remove(listener)

		if (removed && listeners.isEmpty()) {
			removeLayoutListener()
		}

		return removed
	}

	/**
	 * Removes all active listeners
	 */
	fun removeAllListeners() {
		if (listeners.isNotEmpty()) {
			removeLayoutListener()
			listeners.clear()
		}
	}

	/**
	 * Hides software keyboard
	 */
	fun hideKeyboard() {
		//if (wasOpen) {
		rootView.context.inputMethodManager.hideSoftInputFromWindow(rootView.windowToken, 0)
		listeners.forEach { it.invoke(false, 0) }
		wasOpen = false
		//}
	}

	companion object {
		private const val KEYBOARD_VISIBLE_THRESHOLD_DP = 100
	}
}

