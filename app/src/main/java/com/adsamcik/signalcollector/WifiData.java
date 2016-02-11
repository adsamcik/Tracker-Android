package com.adsamcik.signalcollector;

import android.net.wifi.ScanResult;
import android.os.Build;

import java.io.Serializable;

public class WifiData implements Serializable {
    public String BSSID,SSID, capabilities;
    public int centerFreq0, centerFreq1, channelWidth, frequency, level;

    public WifiData(ScanResult sr) {
        this.BSSID = sr.BSSID;
        this.SSID = sr.SSID;
        this.capabilities = sr.capabilities;
        if(Build.VERSION.SDK_INT > 22) {
            this.centerFreq0 = sr.centerFreq0;
            this.centerFreq1 = sr.centerFreq1;
            this.channelWidth = sr.channelWidth;
        }
        this.frequency = sr.frequency;
        this.level = sr.level;
    }
}
