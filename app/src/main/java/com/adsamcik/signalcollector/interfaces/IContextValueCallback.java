package com.adsamcik.signalcollector.interfaces;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

public interface IContextValueCallback<C extends Context, T> {
	/**
	 * Context callback to be called
	 *
	 * @param context context
	 * @param value result value
	 */
	void callback(@NonNull C context, @Nullable T value);
}
