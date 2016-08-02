package com.adsamcik.signalcollector.classes;

public class Success {
	public final String message;

	/**
	 * Creates successful instance
	 */
	public Success() {
		message = null;
	}

	/**
	 * Create unsuccessful instance
	 * @param message message
	 */
	public Success(String message) {
		this.message = message;
	}

	public boolean getSuccess() {
		return message == null;
	}
}
