package com.adsamcik.signalcollector.utility;

public class Failure<T> {
	public final T value;

	/**
	 * Creates successful instance
	 */
	public Failure() {
		value = null;
	}

	/**
	 * Create unsuccessful instance
	 *
	 * @param message value
	 */
	public Failure(T message) {
		this.value = message;
	}

	/**
	 * Returns failure
	 *
	 * @return true if failed
	 */
	public boolean hasFailed() {
		return value != null;
	}
}
