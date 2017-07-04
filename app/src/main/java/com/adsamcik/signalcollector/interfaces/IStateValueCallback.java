package com.adsamcik.signalcollector.interfaces;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

public interface IStateValueCallback<S extends Enum, T> {
	/**
	 * State callback to be called
	 *
	 * @param state result state
	 * @param value result value
	 */
	void callback(@NonNull S state, @Nullable T value);
}
