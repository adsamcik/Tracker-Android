package com.adsamcik.signalcollector.utility;

import android.support.annotation.NonNull;

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
	public Failure(@NonNull T message) {
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
