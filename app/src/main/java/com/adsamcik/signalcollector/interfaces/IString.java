package com.adsamcik.signalcollector.interfaces;

import android.support.annotation.NonNull;

public interface IString<T> {
	/**
	 * Method that converts objects to string
	 * This allows for custom tostring implementations that can be passed as parameters
	 *
	 * @param item item that will be converted to string
	 * @return supplied item as string
	 */
	@NonNull
	String stringify(@NonNull T item);
}
