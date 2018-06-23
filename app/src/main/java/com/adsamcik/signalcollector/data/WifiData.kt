package com.adsamcik.signalcollector.data

import android.net.wifi.ScanResult
import android.net.wifi.ScanResult.*
import android.net.wifi.WifiManager
import android.os.Build
import com.squareup.moshi.JsonClass

/**
 * Class containing Wi-Fi information.
 * It is used in RawData
 */
@JsonClass(generateAdapter = true)
data class WifiData(/**
                     * Unique wifi identification
                     */
                    var BSSID: String,

                    /**
                     * Wifi name
                     */
                    var SSID: String,

                    /**
                     * Capabilities of the network
                     */
                    var capabilities: String,

                    /**
                     * Primary frequency used to communicate with AP. Channel width is 20MHz.
                     */
                    var frequency: Int = 0,

                    /**
                     * The detected signal level in dBm.
                     */
                    var level: Int = 0,

                    /**
                     * Calculated signal level
                     * Has value from 0 to {@value #MAX_SIGNAL_BAR}
                     */
                    var bar: Int = 0,

                    /**
                     * Center frequency, not used for 20MHz AP bandwidth
                     * Only available on Android 5.1 and newer
                     */
                    var centerFreq0: Int = 0,

                    /**
                     * Center frequency, used only for 80+80 AP bandwidth mode
                     * Only available on Android 5.1 (API 21) and newer
                     */
                    var centerFreq1: Int = 0,

                    /**
                     * Channel width
                     * Only available on Android 5.1 (API 21) and newer
                     */
                    var channelWidth: Int = 0,

                    /**
                     * Is wifi passpoint certified
                     */
                    var isPasspoint: Boolean = false) {



    constructor() : this("", "", "")

    constructor(sr: ScanResult) : this() {
        this.BSSID = sr.BSSID
        this.SSID = sr.SSID
        this.capabilities = sr.capabilities
        if (Build.VERSION.SDK_INT > 22) {
            this.centerFreq0 = sr.centerFreq0
            when (sr.channelWidth) {
                CHANNEL_WIDTH_20MHZ -> channelWidth = 20
                CHANNEL_WIDTH_40MHZ -> channelWidth = 40
                CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> {
                    this.centerFreq1 = sr.centerFreq1
                    channelWidth = 80
                }
                CHANNEL_WIDTH_80MHZ -> channelWidth = 80
                CHANNEL_WIDTH_160MHZ -> channelWidth = 160
            }

            this.isPasspoint = sr.isPasspointNetwork
        }
        this.frequency = sr.frequency
        this.level = sr.level
        this.bar = WifiManager.calculateSignalLevel(sr.level, MAX_SIGNAL_BAR)
    }

    companion object {
        private const val MAX_SIGNAL_BAR = 10
    }
}
