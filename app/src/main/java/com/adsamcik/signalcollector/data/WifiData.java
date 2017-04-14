package com.adsamcik.signalcollector.data;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.io.Serializable;

@SuppressWarnings("unused")
public class WifiData implements Serializable {
	public final String BSSID;
	public final String SSID;
	public final String capabilities;
	public final int frequency;
	public final int level;
	public final int bar;
	public int centerFreq0;
	public int centerFreq1;
	public int channelWidth;
	public boolean isPasspoint;

	public WifiData(ScanResult sr) {
		this.BSSID = sr.BSSID;
		this.SSID = sr.SSID;
		this.capabilities = sr.capabilities;
		if(Build.VERSION.SDK_INT > 22) {
			this.centerFreq0 = sr.centerFreq0;
			this.centerFreq1 = sr.centerFreq1;
			this.channelWidth = sr.channelWidth;
			this.isPasspoint = sr.isPasspointNetwork();
		}
		this.frequency = sr.frequency;
		this.level = sr.level;
		this.bar = WifiManager.calculateSignalLevel(sr.level, 10);
	}
}
