package com.adsamcik.tracker.shared.base.data

import android.os.Parcelable
import android.telephony.CellIdentity
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import androidx.annotation.RequiresApi
import androidx.room.ColumnInfo
import androidx.room.Ignore
import com.adsamcik.tracker.shared.base.R
import com.squareup.moshi.JsonClass
import kotlinx.parcelize.Parcelize

/**
 * Data class that contains all the information about Cell.
 * It works universally with every supported cell technology
 * Supported technologies are GSM, CDMA, WCDMA, LTE and NR
 */
@JsonClass(generateAdapter = true)
@Parcelize
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

	constructor(networkOperator: NetworkOperator, type: CellType, cellId: Long, asu: Int)
			: this(networkOperator, cellId, type, asu, 0, 0)


	/**
	 * Creates new instance of CellInfo from GSM cell info
	 *
	 * @param identity         Identity of the cell
	 * @param signalStrength Signal strength of the cell
	 * @return new CellInfo if successful, null otherwise
	 */
	constructor(
			identity: CellIdentityGsm,
			signalStrength: CellSignalStrengthGsm,
			networkOperator: NetworkOperator
	)
			: this(
			networkOperator,
			identity.cid.toLong(),
			CellType.GSM,
			signalStrength.asuLevel,
			signalStrength.dbm,
			signalStrength.level
	)


	/**
	 * Creates new instance of CellInfo from CDMA cell info
	 *
	 * @param identity         Identity of the cell
	 * @param signalStrength Signal strength of the cell
	 * @return new CellInfo if successful, null otherwise
	 */
	constructor(
			identity: CellIdentityCdma,
			signalStrength: CellSignalStrengthCdma,
			networkOperator: NetworkOperator
	)
			: this(
			networkOperator,
			identity.basestationId.toLong(),
			CellType.CDMA,
			signalStrength.asuLevel,
			signalStrength.dbm,
			signalStrength.level
	)

	/**
	 * Creates new instance of CellInfo from WCDMA cell info
	 *
	 * @param identity         Identity of the cell
	 * @param signalStrength Signal strength of the cell
	 * @return new CellInfo if successful, null otherwise
	 */
	constructor(
			identity: CellIdentityWcdma,
			signalStrength: CellSignalStrengthWcdma,
			networkOperator: NetworkOperator
	)
			: this(
			networkOperator,
			identity.cid.toLong(),
			CellType.WCDMA,
			signalStrength.asuLevel,
			signalStrength.dbm,
			signalStrength.level
	)


	/**
	 * Creates new instance of CellInfo from LTE cell info
	 *
	 * @param identity         Identity of the cell
	 * @param signalStrength Signal strength of the cell
	 * @return new CellInfo if successful, null otherwise
	 */
	constructor(
			identity: CellIdentityLte,
			signalStrength: CellSignalStrengthLte,
			networkOperator: NetworkOperator
	)
			: this(
			networkOperator,
			identity.ci.toLong(),
			CellType.LTE,
			signalStrength.asuLevel,
			signalStrength.dbm,
			signalStrength.level
	)


	/**
	 * Creates new instance of CellInfo from NR (5G) cell info
	 *
	 * @param identity         Identity of the cell
	 * @param signalStrength Signal strength of the cell
	 * @return new CellInfo if successful, null otherwise
	 */

	@RequiresApi(29)
	constructor(
			identity: CellIdentityNr,
			signalStrength: CellSignalStrengthNr,
			networkOperator: NetworkOperator
	)
			: this(
			networkOperator,
			identity.nci,
			CellType.NR,
			signalStrength.asuLevel,
			signalStrength.dbm,
			signalStrength.level
	)
}

enum class CellType {
	Unknown {
		override val nameRes: Int = R.string.cell_unknown
	},
	GSM {
		override val nameRes: Int = R.string.cell_gsm
	},
	CDMA {
		override val nameRes: Int = R.string.cell_cdma
	},
	WCDMA {
		override val nameRes: Int = R.string.cell_wcdma
	},
	LTE {
		override val nameRes: Int = R.string.cell_lte
	},
	NR {
		override val nameRes: Int = R.string.cell_nr
	},
	None {
		override val nameRes: Int = R.string.cell_none
	};

	abstract val nameRes: Int
}

