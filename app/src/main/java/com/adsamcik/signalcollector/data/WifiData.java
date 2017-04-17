package com.adsamcik.signalcollector.data;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.io.Serializable;

import static android.net.wifi.ScanResult.CHANNEL_WIDTH_160MHZ;
import static android.net.wifi.ScanResult.CHANNEL_WIDTH_20MHZ;
import static android.net.wifi.ScanResult.CHANNEL_WIDTH_40MHZ;
import static android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ;
import static android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ;

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
			switch (sr.channelWidth) {
				case CHANNEL_WIDTH_20MHZ:
					sr.channelWidth = 20;
					break;
				case CHANNEL_WIDTH_40MHZ:
					sr.channelWidth = 40;
					break;
				case CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
					this.centerFreq1 = sr.centerFreq1;
				case CHANNEL_WIDTH_80MHZ:
					sr.channelWidth = 80;
					break;
				case CHANNEL_WIDTH_160MHZ:
					sr.channelWidth = 160;
					break;
			}
			
			this.isPasspoint = sr.isPasspointNetwork();
		}
		this.frequency = sr.frequency;
		this.level = sr.level;
		this.bar = WifiManager.calculateSignalLevel(sr.level, 10);
	}
}
