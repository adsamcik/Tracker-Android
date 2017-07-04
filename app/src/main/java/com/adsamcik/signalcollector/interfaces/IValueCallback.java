package com.adsamcik.signalcollector.interfaces;

import android.support.annotation.Nullable;

public interface IValueCallback<T> {
	/**
	 * Callback to be called
	 *
	 * @param value return value
	 */
	void callback(@Nullable T value);
}
