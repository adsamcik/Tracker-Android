package com.adsamcik.signalcollector.interfaces;

import android.support.annotation.NonNull;

public interface INonNullValueCallback<T> {
	/**
	 * Callback to be called
	 *
	 * @param value return value
	 */
	void callback(@NonNull T value);
}
