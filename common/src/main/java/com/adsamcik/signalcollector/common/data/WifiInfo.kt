package com.adsamcik.signalcollector.common.data

import android.net.wifi.ScanResult
import android.net.wifi.ScanResult.CHANNEL_WIDTH_160MHZ
import android.net.wifi.ScanResult.CHANNEL_WIDTH_20MHZ
import android.net.wifi.ScanResult.CHANNEL_WIDTH_40MHZ
import android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ
import android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import androidx.room.Ignore
import com.adsamcik.signalcollector.common.extension.requireString
import com.squareup.moshi.JsonClass

/**
 * Class containing Wi-Fi information.
 * It is used in MutableCollectionData
 */
@JsonClass(generateAdapter = true)
data class WifiInfo(
		/**
		 * Unique wifi identification
		 */
		var bssid: String,

		/**
		 * Wifi name
		 */
		var ssid: String,

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
		@Ignore
		var bar: Int = 0,

		/**
		 * Center frequency, not used for 20MHz AP bandwidth
		 * Only available on Android 5.1 and newer
		 */
		@Ignore
		var centerFreq0: Int = 0,

		/**
		 * Center frequency, used only for 80+80 AP bandwidth mode
		 * Only available on Android 5.1 (API 21) and newer
		 */
		@Ignore
		var centerFreq1: Int = 0,

		/**
		 * Channel width
		 * Only available on Android 5.1 (API 21) and newer
		 */
		@Ignore
		var channelWidth: Int = 0,

		/**
		 * Is wifi passpoint certified
		 */
		var isPasspoint: Boolean = false
) : Parcelable {


	constructor(parcel: Parcel) : this(
			parcel.requireString(),
			parcel.requireString(),
			parcel.requireString(),
			parcel.readInt(),
			parcel.readInt(),
			parcel.readInt(),
			parcel.readInt(),
			parcel.readInt(),
			parcel.readInt(),
			parcel.readByte() != 0.toByte())

	constructor() : this("", "", "")

	constructor(sr: ScanResult) : this() {
		this.bssid = sr.BSSID
		this.ssid = sr.SSID
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

	override fun writeToParcel(parcel: Parcel, flags: Int) {
		parcel.writeString(bssid)
		parcel.writeString(ssid)
		parcel.writeString(capabilities)
		parcel.writeInt(frequency)
		parcel.writeInt(level)
		parcel.writeInt(bar)
		parcel.writeInt(centerFreq0)
		parcel.writeInt(centerFreq1)
		parcel.writeInt(channelWidth)
		parcel.writeByte(if (isPasspoint) 1 else 0)
	}

	override fun describeContents(): Int {
		return 0
	}

	companion object CREATOR : Parcelable.Creator<WifiInfo> {
		private const val MAX_SIGNAL_BAR = 10

		override fun createFromParcel(parcel: Parcel): WifiInfo {
			return WifiInfo(parcel)
		}

		override fun newArray(size: Int): Array<WifiInfo?> {
			return arrayOfNulls(size)
		}
	}
}

