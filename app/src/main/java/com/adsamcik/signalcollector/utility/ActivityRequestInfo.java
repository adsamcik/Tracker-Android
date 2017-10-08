package com.adsamcik.signalcollector.utility;

public class ActivityRequestInfo {
	public final int hash;
	private int updateFrequency;
	private boolean backgroundTracking;

	public ActivityRequestInfo(final int hash, final int updateFrequency, final boolean backgroundTracking) {
		this.hash = hash;
		this.updateFrequency = updateFrequency;
		this.backgroundTracking = backgroundTracking;
	}

	public int getUpdateFrequency() {
		return updateFrequency;
	}

	public void setUpdateFrequency(int updateFrequency) {
		this.updateFrequency = updateFrequency;
	}

	public boolean isBackgroundTracking() {
		return backgroundTracking;
	}

	public void setBackgroundTracking(boolean backgroundTracking) {
		this.backgroundTracking = backgroundTracking;
	}

}