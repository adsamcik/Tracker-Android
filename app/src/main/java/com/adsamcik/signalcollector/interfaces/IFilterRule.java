package com.adsamcik.signalcollector.interfaces;

import android.support.annotation.NonNull;

public interface IFilterRule<T> {
	/**
	 * Filter function that returns true if item should be displayed
	 *
	 * @param value       Value to filter
	 * @param stringValue Value as string to filter
	 * @param constraint  Constraint string
	 * @return true if value should be shown
	 */
	boolean filter(@NonNull T value, @NonNull String stringValue, @NonNull CharSequence constraint);
}
