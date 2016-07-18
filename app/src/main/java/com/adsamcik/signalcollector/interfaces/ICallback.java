package com.adsamcik.signalcollector.interfaces;

public interface ICallback {
	/**
	 * Called when callback action is positive
	 * eg Upload file was created
	 */
	void OnTrue();

	/**
	 * Called when callback action is negative
	 * eg Upload file was deleted
	 */
	void OnFalse();
}
