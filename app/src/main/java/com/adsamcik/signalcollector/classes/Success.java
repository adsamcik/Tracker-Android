package com.adsamcik.signalcollector.classes;

public class Success<T> {
	public final T value;

	/**
	 * Creates successful instance
	 */
	public Success() {
		value = null;
	}

	/**
	 * Create unsuccessful instance
	 * @param message value
	 */
	public Success(T message) {
		this.value = message;
	}

	public boolean getSuccess() {
		return value == null;
	}
}
