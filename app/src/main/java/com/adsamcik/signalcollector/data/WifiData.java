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
	private static final int MAX_SIGNAL_BAR = 10;

	/**
	 * Unique wifi identification
	 */
	public final String BSSID;

	/**
	 * Wifi name
	 */
	public final String SSID;

	/**
	 * Capabilities of the network
	 */
	public final String capabilities;

	/**
	 * Primary frequency used to communicate with AP. Channel width is 20MHz.
	 */
	public final int frequency;

	/**
	 * The detected signal level in dBm.
	 */
	public final int level;

	/**
	 * Calculated signal level
	 * Has value from 0 to {@value #MAX_SIGNAL_BAR}
	 */
	public final int bar;

	/**
	 * Center frequency, not used for 20MHz AP bandwidth
	 * Only available on Android 5.1 and newer
	 */
	public int centerFreq0;

	/**
	 * Center frequency, used only for 80+80 AP bandwidth mode
	 * Only available on Android 5.1 and newer
	 */
	public int centerFreq1;

	/**
	 * Channel width
	 * Only available on Android 5.1 and newer
	 */
	public int channelWidth;

	/**
	 * Is wifi passpoint certified
	 */
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
		this.bar = WifiManager.calculateSignalLevel(sr.level, MAX_SIGNAL_BAR);
	}
}
