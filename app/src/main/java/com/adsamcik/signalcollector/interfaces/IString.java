package com.adsamcik.signalcollector.interfaces;

import android.support.annotation.NonNull;

public interface IString<T> {
	@NonNull String stringify(@NonNull T item);
}
