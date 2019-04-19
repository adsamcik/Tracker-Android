package com.adsamcik.signalcollector.tracker.data.collection

import android.annotation.SuppressLint
import android.net.wifi.ScanResult
import android.os.Build
import android.telephony.*
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.crashlytics.android.Crashlytics
import com.squareup.moshi.JsonClass
import java.util.*

/**
 * Object containing raw collection data.
 * Data in here might have been reordered to different objects
 * but they have not been modified in any way.
 */
@JsonClass(generateAdapter = true)
data class MutableCollectionData(
		override val time: Long = System.currentTimeMillis(),
		override var location: Location? = null,
		override var activity: ActivityInfo? = null,
		override var cell: CellData? = null,
		override var wifi: WifiData? = null) : CollectionData {


	/**
	 * Sets collection location
	 *
	 * @param location location
	 * @return this
	 */
	fun setLocation(location: android.location.Location): MutableCollectionData {
		this.location = Location(location)
		return this
	}

	/**
	 * Sets wifi and time of wifi collection
	 *
	 * @param data data
	 * @param time time of collection
	 * @return this
	 */
	fun setWifi(location: android.location.Location, time: Long, data: Array<ScanResult>?): MutableCollectionData {
		if (data != null && time > 0) {
			val scannedWifi = data.map { scanResult -> WifiInfo(scanResult) }
			this.wifi = WifiData(Location(location), time, scannedWifi)
		}
		return this
	}

	/**
	 * Sets activity
	 *
	 * @param activity activity
	 * @return this
	 */
	fun setActivity(activity: ActivityInfo): MutableCollectionData {
		this.activity = activity
		return this
	}

	@Suppress("DEPRECATION")
	fun addCell(telephonyManager: TelephonyManager) {
//Annoying lint bug CoarseLocation permission is not required when android.permission.ACCESS_FINE_LOCATION is present
		@SuppressLint("MissingPermission") val cellInfo = telephonyManager.allCellInfo
		val nOp = telephonyManager.networkOperator
		if (nOp.isNotEmpty()) {
			val mcc = java.lang.Short.parseShort(nOp.substring(0, 3))
			val mnc = java.lang.Short.parseShort(nOp.substring(3))

			if (cellInfo != null) {
				val registeredCells = ArrayList<CellInfo>(if (Build.VERSION.SDK_INT >= 23) telephonyManager.phoneCount else 1)
				for (ci in cellInfo) {
					if (ci.isRegistered) {
						var cd: CellInfo? = null
						when (ci) {
							is CellInfoLte -> cd =
									if (ci.cellIdentity.mnc == mnc.toInt() && ci.cellIdentity.mcc == mcc.toInt())
										CellInfo.newInstance(ci, telephonyManager.networkOperatorName)
									else
										CellInfo.newInstance(ci, null as String?)
							is CellInfoGsm -> cd =
									if (ci.cellIdentity.mnc == mnc.toInt() && ci.cellIdentity.mcc == mcc.toInt())
										CellInfo.newInstance(ci, telephonyManager.networkOperatorName)
									else
										CellInfo.newInstance(ci, null as String?)
							is CellInfoWcdma -> cd =
									if (ci.cellIdentity.mnc == mnc.toInt() && ci.cellIdentity.mcc == mcc.toInt())
										CellInfo.newInstance(ci, telephonyManager.networkOperatorName)
									else
										CellInfo.newInstance(ci, null as String?)
							is CellInfoCdma -> /*if (cic.getCellIdentity().getMnc() == mnc && cic.getCellIdentity().getMcc() == mcc)
                addCell(CellInfo.newInstance(cic, telephonyManager.getNetworkOperatorName()));
                else*/
								cd = CellInfo.newInstance(ci, null as String?)
							else -> Crashlytics.logException(Throwable("UNKNOWN CELL TYPE"))
						}

						if (cd != null)
							registeredCells.add(cd)
					}
				}

				this.cell = CellData(registeredCells.toTypedArray(), cellInfo.size)
			}
		}
	}
}
