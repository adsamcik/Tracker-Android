package com.adsamcik.signalcollector.interfaces;

public interface IStateValueCallback<S extends Enum, T> {
	/**
	 * State callback to be called
	 *
	 * @param state result state
	 * @param value result value
	 */
	void callback(S state, T value);
}
