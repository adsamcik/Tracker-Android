package com.adsamcik.signalcollector.common.data

import android.os.Parcel
import android.os.Parcelable
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import androidx.annotation.RequiresApi
import androidx.room.ColumnInfo
import androidx.room.Ignore
import com.adsamcik.signalcollector.common.extension.requireParcelable
import com.squareup.moshi.JsonClass

/**
 * Data class that contains all the information about Cell.
 * It works universally with every supported cell technology
 * Supported technologies are GSM, CDMA, WCDMA, LTE and NR
 */
@Suppress("DEPRECATION")
@JsonClass(generateAdapter = true)
data class CellInfo
/**
 * CellInfo constructor
 *
 * @param type         [type]
 * @param cellId           [cellId]
 * @param networkOperator          [networkOperator]
 * @param dbm          [dbm]
 * @param asu          [asu]
 * @param level        [CellInfo.level]
 */(
		/**
		 * Network operator of the cell
		 */
		var networkOperator: NetworkOperator,
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
		 * Network type.
		 */
		var type: CellType,
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
		var level: Int = 0
) : Parcelable {

	constructor(parcel: Parcel) : this(
			parcel.requireParcelable(NetworkOperator::class.java.classLoader),
			parcel.readLong(),
			CellType.values()[parcel.readInt()],
			parcel.readInt(),
			parcel.readInt(),
			parcel.readInt())

	constructor(networkOperator: NetworkOperator, type: CellType, cellId: Long, asu: Int)
			: this(networkOperator, cellId, type, asu, 0, 0)


	/**
	 * Creates new instance of CellInfo from GSM cell info
	 *
	 * @param identity         Identity of the cell
	 * @param signalStrength Signal strength of the cell
	 * @return new CellInfo if successful, null otherwise
	 */
	constructor(identity: CellIdentityGsm,
	            signalStrength: CellSignalStrengthGsm,
	            networkOperator: NetworkOperator)
			: this(networkOperator,
			identity.cid.toLong(),
			CellType.GSM,
			signalStrength.asuLevel,
			signalStrength.dbm,
			signalStrength.level)


	/**
	 * Creates new instance of CellInfo from CDMA cell info
	 *
	 * @param identity         Identity of the cell
	 * @param signalStrength Signal strength of the cell
	 * @return new CellInfo if successful, null otherwise
	 */
	constructor(identity: CellIdentityCdma,
	            signalStrength: CellSignalStrengthCdma,
	            networkOperator: NetworkOperator)
			: this(networkOperator,
			identity.basestationId.toLong(),
			CellType.CDMA,
			signalStrength.asuLevel,
			signalStrength.dbm,
			signalStrength.level)

	/**
	 * Creates new instance of CellInfo from WCDMA cell info
	 *
	 * @param identity         Identity of the cell
	 * @param signalStrength Signal strength of the cell
	 * @return new CellInfo if successful, null otherwise
	 */
	constructor(identity: CellIdentityWcdma,
	            signalStrength: CellSignalStrengthWcdma,
	            networkOperator: NetworkOperator)
			: this(networkOperator,
			identity.cid.toLong(),
			CellType.CDMA,
			signalStrength.asuLevel,
			signalStrength.dbm,
			signalStrength.level)


	/**
	 * Creates new instance of CellInfo from LTE cell info
	 *
	 * @param identity         Identity of the cell
	 * @param signalStrength Signal strength of the cell
	 * @return new CellInfo if successful, null otherwise
	 */
	constructor(identity: CellIdentityLte,
	            signalStrength: CellSignalStrengthLte,
	            networkOperator: NetworkOperator)
			: this(networkOperator,
			identity.ci.toLong(),
			CellType.CDMA,
			signalStrength.asuLevel,
			signalStrength.dbm,
			signalStrength.level)


	/**
	 * Creates new instance of CellInfo from NR (5G) cell info
	 *
	 * @param identity         Identity of the cell
	 * @param signalStrength Signal strength of the cell
	 * @return new CellInfo if successful, null otherwise
	 */

	@RequiresApi(29)
	constructor(identity: CellIdentityNr,
	            signalStrength: CellSignalStrengthNr,
	            networkOperator: NetworkOperator)
			: this(networkOperator,
			identity.nci,
			CellType.CDMA,
			signalStrength.asuLevel,
			signalStrength.dbm,
			signalStrength.level)

	override fun writeToParcel(parcel: Parcel, flags: Int) {
		parcel.writeParcelable(networkOperator, flags)
		parcel.writeLong(cellId)
		parcel.writeInt(type.ordinal)
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

