package com.adsamcik.tracker.common.data

import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.telephony.CellIdentityNr
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import androidx.annotation.RequiresApi
import androidx.room.Entity
import com.adsamcik.tracker.common.extension.requireString
import kotlinx.android.parcel.Parcelize

@Entity(tableName = "network_operator", primaryKeys = ["mcc", "mnc"])
@Parcelize
data class NetworkOperator(
		val mcc: String,
		/**
		 * Mobile network code
		 * Replaced with Network ID on CDMA
		 */
		val mnc: String,
		/**
		 * Mobile country code
		 * Replaced with System ID on CDMA
		 */
		val name: String?
) : Parcelable {

	fun sameNetwork(info: CellInfoLte): Boolean {
		val identity = info.cellIdentity
		return if (Build.VERSION.SDK_INT >= 28) {
			identity.mncString == mnc && identity.mccString == mcc
		} else {
			@Suppress("deprecation")
			identity.mnc.toString() == mnc && identity.mcc.toString() == mcc
		}
	}

	fun sameNetwork(info: CellInfoCdma): Boolean {
		//todo add cdma network matching (can be done if only 1 cell is registered)
		return false
	}

	fun sameNetwork(info: CellInfoGsm): Boolean {
		val identity = info.cellIdentity
		return if (Build.VERSION.SDK_INT >= 28) {
			identity.mncString == mnc && identity.mccString == mcc
		} else {
			@Suppress("deprecation")
			identity.mnc.toString() == mnc && identity.mcc.toString() == mcc
		}
	}

	fun sameNetwork(info: CellInfoWcdma): Boolean {
		val identity = info.cellIdentity
		return if (Build.VERSION.SDK_INT >= 28) {
			identity.mncString == mnc && identity.mccString == mcc
		} else {
			@Suppress("deprecation")
			identity.mnc.toString() == mnc && identity.mcc.toString() == mcc
		}
	}

	@RequiresApi(29)
	fun sameNetwork(info: CellInfoNr): Boolean {
		val identity = info.cellIdentity as CellIdentityNr
		return identity.mncString == mnc && identity.mccString == mcc
	}
}

