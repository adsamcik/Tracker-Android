package com.adsamcik.tracker.statistics.preference

import com.adsamcik.tracker.shared.base.data.SessionActivity

/**
 * Thread-local context holder for session activity information.
 * Used to pass session activity context to distance formatters and statistics generators.
 */
object SessionActivityContext {
	private val threadLocalSessionActivity = ThreadLocal<SessionActivity?>()
	
	/**
	 * Sets the session activity for the current thread.
	 */
	fun setSessionActivity(sessionActivity: SessionActivity?) {
		threadLocalSessionActivity.set(sessionActivity)
	}
	
	/**
	 * Gets the session activity for the current thread.
	 */
	fun getSessionActivity(): SessionActivity? {
		return threadLocalSessionActivity.get()
	}
	
	/**
	 * Clears the session activity for the current thread.
	 */
	fun clearSessionActivity() {
		threadLocalSessionActivity.remove()
	}
	
	/**
	 * Executes a block with the specified session activity context.
	 */
	inline fun <T> withSessionActivity(sessionActivity: SessionActivity?, block: () -> T): T {
		val previousActivity = getSessionActivity()
		try {
			setSessionActivity(sessionActivity)
			return block()
		} finally {
			setSessionActivity(previousActivity)
		}
	}
}
