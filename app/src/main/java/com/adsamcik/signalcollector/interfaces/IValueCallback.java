package com.adsamcik.signalcollector.interfaces;

public interface IValueCallback<T> {
	/**
	 * Callback to be called
	 *
	 * @param value return value
	 */
	void callback(T value);
}
