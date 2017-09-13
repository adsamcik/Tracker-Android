package com.adsamcik.signalcollector.interfaces;

import android.support.annotation.NonNull;

public interface IFilterRule<T> {
	boolean filter(@NonNull T value, @NonNull String stringValue, @NonNull CharSequence constraint);
}
