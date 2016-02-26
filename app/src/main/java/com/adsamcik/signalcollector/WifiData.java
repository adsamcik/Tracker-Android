package com.adsamcik.signalcollector;

import android.net.wifi.ScanResult;
import android.os.Build;

import java.io.Serializable;

public class WifiData implements Serializable {
    public final String BSSID;
    public final String SSID;
    public final String capabilities;
    public final int frequency;
    public final int level;
    public int centerFreq0;
    public int centerFreq1;
    public int channelWidth;

    public WifiData(ScanResult sr) {
        this.BSSID = sr.BSSID;
        this.SSID = sr.SSID;
        this.capabilities = sr.capabilities;
        if (Build.VERSION.SDK_INT > 22) {
            this.centerFreq0 = sr.centerFreq0;
            this.centerFreq1 = sr.centerFreq1;
            this.channelWidth = sr.channelWidth;
        }
        this.frequency = sr.frequency;
        this.level = sr.level;
    }
}
