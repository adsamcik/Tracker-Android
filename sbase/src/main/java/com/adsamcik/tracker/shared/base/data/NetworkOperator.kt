package com.adsamcik.tracker.shared.base.data

import android.os.Build
import android.os.Parcelable
import android.telephony.CellIdentityNr
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import androidx.annotation.RequiresApi
import androidx.room.Entity
import kotlinx.parcelize.Parcelize

/**
 * Information about cell network operator.
 */
// TODO: Migrate to CellSample (which stores mcc/mnc inline). Dual-write active in
//  DatabaseCellComponent (enableDualWrite flag). No production readers found — only written to.
//  Once dual-write is disabled, this table will have no active writers or readers.
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
		 * Operator name
		 */
		val name: String?
) : Parcelable {

	/**
	 * Checks if LTE (4G) cell belongs to network operator.
	 */
	fun sameNetwork(info: CellInfoLte): Boolean {
		val identity = info.cellIdentity
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			identity.mncString == mnc && identity.mccString == mcc
		} else {
			@Suppress("deprecation")
			identity.mnc.toString() == mnc && identity.mcc.toString() == mcc
		}
	}

	/**
	 * Always returns false, because CDMA (2G) uses different identification than everything else.
	 */
	@Suppress("DEPRECATION")
	fun sameNetwork(@Suppress("UNUSED_PARAMETER") info: android.telephony.CellInfoCdma): Boolean {
		return false
	}


	/**
	 * Checks if GSM (2G) cell belongs to network operator.
	 */
	fun sameNetwork(info: CellInfoGsm): Boolean {
		val identity = info.cellIdentity
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			identity.mncString == mnc && identity.mccString == mcc
		} else {
			@Suppress("deprecation")
			identity.mnc.toString() == mnc && identity.mcc.toString() == mcc
		}
	}

	/**
	 * Checks if WCDMA (3G) cell belongs to network operator.
	 */
	fun sameNetwork(info: CellInfoWcdma): Boolean {
		val identity = info.cellIdentity
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			identity.mncString == mnc && identity.mccString == mcc
		} else {
			@Suppress("deprecation")
			identity.mnc.toString() == mnc && identity.mcc.toString() == mcc
		}
	}

	/**
	 * Checks if NR (5G) cell belongs to network operator.
	 */
	@RequiresApi(29)
	fun sameNetwork(info: CellInfoNr): Boolean {
		val identity = info.cellIdentity as CellIdentityNr
		return identity.mncString == mnc && identity.mccString == mcc
	}
}

