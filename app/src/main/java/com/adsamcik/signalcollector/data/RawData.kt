package com.adsamcik.signalcollector.data

import android.annotation.SuppressLint
import android.location.Location
import android.net.wifi.ScanResult
import android.os.Build
import android.telephony.*
import com.adsamcik.signalcollector.enums.ResolvedActivities
import com.crashlytics.android.Crashlytics
import com.squareup.moshi.JsonClass
import java.util.*

/**
 * Object containing raw collection data.
 * Data in here might have been reordered to different objects
 * but they have not been modified in any way.
 */
@JsonClass(generateAdapter = true)
data class RawData(
        /**
         * Time of collection in milliseconds since midnight, January 1, 1970 UTC
         */
        var time: Long = 0,

        /**
         * Longitude
         */
        var longitude: Double? = null,

        /**
         * Latitude
         */
        var latitude: Double? = null,

        /**
         * Altitude
         */
        var altitude: Double? = null,

        /**
         * Accuracy in meters
         */
        var accuracy: Float? = null,

        /**
         * List of registered cells
         * Null if not collected
         */
        var registeredCells: Array<CellData>? = null,

        /**
         * Total cell count
         * default null if not collected.
         */
        var cellCount: Int? = null,

        /**
         * Array of collected wifi networks
         */
        var wifi: Array<WifiData>? = null,

        /**
         * Time of collection of wifi data
         */
        var wifiTime: Long? = null,

        /**
         * Current resolved activity
         */
        @ResolvedActivities.ResolvedActivity
        var activity: Int? = null) {


    /**
     * Sets collection location
     *
     * @param location location
     * @return this
     */
    fun setLocation(location: Location): RawData {
        this.longitude = location.longitude
        this.latitude = location.latitude
        this.altitude = location.altitude
        this.accuracy = location.accuracy
        return this
    }

    /**
     * Sets wifi and time of wifi collection
     *
     * @param data data
     * @param time time of collection
     * @return this
     */
    fun setWifi(data: Array<ScanResult>?, time: Long): RawData {
        if (data != null && time > 0) {
            wifi = data.map { scanResult -> WifiData(scanResult) }.toTypedArray()
            this.wifiTime = time
        }
        return this
    }

    /**
     * Sets activity
     *
     * @param activity activity
     * @return this
     */
    fun setActivity(activity: Int): RawData {
        this.activity = activity
        return this
    }

    fun addCell(telephonyManager: TelephonyManager) {
//Annoying lint bug CoarseLocation permission is not required when android.permission.ACCESS_FINE_LOCATION is present
        @SuppressLint("MissingPermission") val cellInfos = telephonyManager.allCellInfo
        val nOp = telephonyManager.networkOperator
        if (!nOp.isEmpty()) {
            val mcc = java.lang.Short.parseShort(nOp.substring(0, 3))
            val mnc = java.lang.Short.parseShort(nOp.substring(3))

            if (cellInfos != null) {
                cellCount = cellInfos.size
                val registeredCells = ArrayList<CellData>(if (Build.VERSION.SDK_INT >= 23) telephonyManager.phoneCount else 1)
                for (ci in cellInfos) {
                    if (ci.isRegistered) {
                        var cd: CellData? = null
                        when (ci) {
                            is CellInfoLte -> cd =
                                    if (ci.cellIdentity.mnc == mnc.toInt() && ci.cellIdentity.mcc == mcc.toInt())
                                        CellData.newInstance(ci, telephonyManager.networkOperatorName)
                                    else
                                        CellData.newInstance(ci, null as String?)
                            is CellInfoGsm -> cd =
                                    if (ci.cellIdentity.mnc == mnc.toInt() && ci.cellIdentity.mcc == mcc.toInt())
                                        CellData.newInstance(ci, telephonyManager.networkOperatorName)
                                    else
                                        CellData.newInstance(ci, null as String?)
                            is CellInfoWcdma -> cd =
                                    if (ci.cellIdentity.mnc == mnc.toInt() && ci.cellIdentity.mcc == mcc.toInt())
                                        CellData.newInstance(ci, telephonyManager.networkOperatorName)
                                    else
                                        CellData.newInstance(ci, null as String?)
                            is CellInfoCdma -> /*if (cic.getCellIdentity().getMnc() == mnc && cic.getCellIdentity().getMcc() == mcc)
                addCell(CellData.newInstance(cic, telephonyManager.getNetworkOperatorName()));
                else*/
                                cd = CellData.newInstance(ci, null as String?)
                            else -> Crashlytics.logException(Throwable("UNKNOWN CELL TYPE"))
                        }

                        if (cd != null)
                            registeredCells.add(cd)
                    }
                }

                this.registeredCells = registeredCells.toTypedArray()
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RawData

        if (time != other.time) return false
        if (longitude != other.longitude) return false
        if (latitude != other.latitude) return false
        if (altitude != other.altitude) return false
        if (accuracy != other.accuracy) return false
        if (!Arrays.equals(registeredCells, other.registeredCells)) return false
        if (cellCount != other.cellCount) return false
        if (!Arrays.equals(wifi, other.wifi)) return false
        if (wifiTime != other.wifiTime) return false
        if (activity != other.activity) return false

        return true
    }

    override fun hashCode(): Int {
        var result = time.hashCode()
        result = 31 * result + (longitude?.hashCode() ?: 0)
        result = 31 * result + (latitude?.hashCode() ?: 0)
        result = 31 * result + (altitude?.hashCode() ?: 0)
        result = 31 * result + (accuracy?.hashCode() ?: 0)
        result = 31 * result + (registeredCells?.let { Arrays.hashCode(it) } ?: 0)
        result = 31 * result + (cellCount ?: 0)
        result = 31 * result + (wifi?.let { Arrays.hashCode(it) } ?: 0)
        result = 31 * result + (wifiTime?.hashCode() ?: 0)
        result = 31 * result + (activity ?: 0)
        return result
    }
}
