package com.adsamcik.signalcollector.interfaces;

public interface IStateValueCallback<S extends Enum, T> {
	void callback(S state, T value);
}
