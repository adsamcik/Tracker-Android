package com.adsamcik.signalcollector.common.data

import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.telephony.*
import androidx.annotation.RequiresApi
import androidx.room.ColumnInfo
import androidx.room.Ignore
import com.adsamcik.signalcollector.common.extension.requireString
import com.squareup.moshi.JsonClass

/**
 * Data class that contains all the information about Cell.
 * It works universally with every supported cell technology
 * Supported technologies are GSM, CDMA, WCDMA and LTE
 */
@Suppress("DEPRECATION")
@JsonClass(generateAdapter = true)
data class CellInfo
/**
 * CellInfo constructor
 *
 * @param operatorName [CellInfo.operatorName]
 * @param type         [CellInfo.type]
 * @param cellId           [CellInfo.cellId]
 * @param mcc          [CellInfo.mcc]
 * @param mnc          [CellInfo.mnc]
 * @param dbm          [dbm]
 * @param asu          [asu]
 * @param level        [CellInfo.level]
 */(@ColumnInfo(name = "operator_name")
    var operatorName: String,
    /**
     * Network type.
     */
    var type: CellType,
    /**
     * Cell id
     * GSM - cid
     * CDMA - baseStationId
     * WCDMA - cid
     * LTE - ci
     */
    @ColumnInfo(name = "cell_id")
    var cellId: Long,
    /**
     * Mobile country code
     * Replaced with System ID on CDMA
     */
    var mcc: String,
    /**
     * Mobile network code
     * Replaced with Network ID on CDMA
     */
    var mnc: String,
    /**
     * Strength of signal in asu
     */
    var asu: Int,
    /**
     * Strength of signal in decibels
     */
    @Ignore
    var dbm: Int = 0,
    /**
     * Signal strength as int 0...4 calculated by device
     */
    @Ignore
    var level: Int = 0) : Parcelable {

	constructor(parcel: Parcel) : this(
			parcel.requireString(),
			CellType.values()[parcel.readInt()],
			parcel.readLong(),
			parcel.requireString(),
			parcel.requireString(),
			parcel.readInt(),
			parcel.readInt(),
			parcel.readInt()) {
	}

	constructor(operatorName: String, type: CellType, cellId: Long, mcc: String, mnc: String, asu: Int) : this(operatorName, type, cellId, mcc, mnc, asu, 0, 0)

	override fun writeToParcel(parcel: Parcel, flags: Int) {
		parcel.writeString(operatorName)
		parcel.writeLong(cellId)
		parcel.writeString(mcc)
		parcel.writeString(mnc)
		parcel.writeInt(asu)
		parcel.writeInt(dbm)
		parcel.writeInt(level)
	}

	override fun describeContents(): Int {
		return 0
	}


	companion object CREATOR : Parcelable.Creator<CellInfo> {
		override fun createFromParcel(parcel: Parcel): CellInfo {
			return CellInfo(parcel)
		}

		override fun newArray(size: Int): Array<CellInfo?> {
			return arrayOfNulls(size)
		}

		/**
		 * Creates new instance of CellInfo from GSM cell info
		 *
		 * @param cing         GSM cell info
		 * @param operatorName network operator name
		 * @return new CellInfo if successfull, null otherwise
		 */
		fun newInstance(cing: CellInfoGsm, operatorName: String?): CellInfo? {
			if (operatorName == null) return null

			val cig = cing.cellIdentity
			val cssg = cing.cellSignalStrength

			val mcc: String
			val mnc: String
			if (Build.VERSION.SDK_INT == 28) {
				mcc = cig.mccString ?: return null
				mnc = cig.mncString ?: return null
			} else {
				mcc = cig.mcc.toString()
				mnc = cig.mnc.toString()
			}

			return CellInfo(operatorName, CellType.GSM, cig.cid.toLong(), mcc, mnc, cssg.asuLevel, cssg.dbm, cssg.level)
		}


		/**
		 * Creates new instance of CellInfo from CDMA cell info
		 *
		 * @param cinc         CDMA cell info
		 * @param operatorName network operator name
		 * @return new CellInfo if successfull, null otherwise
		 */
		fun newInstance(cinc: CellInfoCdma, operatorName: String?): CellInfo? {
			if (operatorName == null) return null

			val cic = cinc.cellIdentity
			val cssg = cinc.cellSignalStrength

			return CellInfo(operatorName, CellType.CDMA, cic.basestationId.toLong(), cic.systemId.toString(), cic.networkId.toString(), cssg.asuLevel, cssg.dbm, cssg.level)
		}

		/**
		 * Creates new instance of CellInfo from WCDMA cell info
		 *
		 * @param cinl         WCDMA cell info
		 * @param operatorName network operator name
		 * @return new CellInfo if successfull, null otherwise
		 */
		fun newInstance(cinl: CellInfoWcdma, operatorName: String?): CellInfo? {
			if (operatorName == null) return null

			val cil = cinl.cellIdentity
			val cssg = cinl.cellSignalStrength

			val mcc: String
			val mnc: String
			if (Build.VERSION.SDK_INT == 28) {
				mcc = cil.mccString ?: return null
				mnc = cil.mncString ?: return null
			} else {
				mcc = cil.mcc.toString()
				mnc = cil.mnc.toString()
			}

			return CellInfo(operatorName, CellType.WCDMA, cil.cid.toLong(), mcc, mnc, cssg.asuLevel, cssg.dbm, cssg.level)
		}


		/**
		 * Creates new instance of CellInfo from LTE cell info
		 *
		 * @param cinl         LTE Cell Info
		 * @param operatorName network operator name
		 * @return new CellInfo if successfull, null otherwise
		 */
		fun newInstance(cinl: CellInfoLte, operatorName: String?): CellInfo? {
			if (operatorName == null) return null

			val cil = cinl.cellIdentity
			val cssg = cinl.cellSignalStrength

			val mcc: String
			val mnc: String
			if (Build.VERSION.SDK_INT == 28) {
				mcc = cil.mccString ?: return null
				mnc = cil.mncString ?: return null
			} else {
				mcc = cil.mcc.toString()
				mnc = cil.mnc.toString()
			}

			return CellInfo(operatorName, CellType.LTE, cil.ci.toLong(), mcc, mnc, cssg.asuLevel, cssg.dbm, cssg.level)
		}


		/**
		 * Creates new instance of CellInfo from NR (5G) cell info
		 *
		 * @param cinr         NR Cell Info
		 * @param operatorName network operator name
		 * @return new CellInfo if successfull, null otherwise
		 */

		@RequiresApi(29)
		fun newInstance(cinr: CellInfoNr, operatorName: String?): CellInfo? {
			if (operatorName == null) return null

			val cil = cinr.cellIdentity as CellIdentityNr
			val cssg = cinr.cellSignalStrength as CellSignalStrengthNr

			val mcc = cil.mccString
			val mnc = cil.mncString

			return if (mcc == null || mnc == null) {
				null
			} else {
				CellInfo(operatorName, CellType.NR, cil.nci, mcc, mnc, cssg.asuLevel, cssg.dbm, cssg.level)
			}
		}
	}
}

enum class CellType {
	Unknown,
	GSM,
	CDMA,
	WCDMA,
	LTE,
	NR
}
