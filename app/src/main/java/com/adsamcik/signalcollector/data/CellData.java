package com.adsamcik.signalcollector.data;

import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthWcdma;

import java.io.Serializable;


public class CellData implements Serializable {
	public final int type;
	public final int id;
	public int mcc;
	public int mnc;
	public final int dbm;
	public final int asu;
	public final int level;
	public final boolean isRegistered;


	/**
	 * GSM constructor
	 * @param cing GSM cell info
	 */
	public CellData(CellInfoGsm cing) {
		this.type = 0;
		this.isRegistered = cing.isRegistered();
		CellIdentityGsm cig = cing.getCellIdentity();
		this.id = cig.getCid();
		this.mcc = cig.getMcc();
		this.mnc = cig.getMnc();

		CellSignalStrengthGsm cssg = cing.getCellSignalStrength();
		this.dbm = cssg.getDbm();
		this.asu = cssg.getAsuLevel();
		this.level = cssg.getLevel();
	}

	/**
	 * LTE constructor
	 * @param cinl LTE cell info
	 */
	public CellData(CellInfoLte cinl) {
		this.type = 3;
		this.isRegistered = cinl.isRegistered();
		CellIdentityLte cil = cinl.getCellIdentity();
		this.id = cil.getCi();
		this.mcc = cil.getMcc();
		this.mnc = cil.getMnc();

		CellSignalStrengthLte cssl = cinl.getCellSignalStrength();
		this.dbm = cssl.getDbm();
		this.asu = cssl.getAsuLevel();
		this.level = cssl.getLevel();
	}

	/**
	 * CDMA constructor
	 * @param cinc cdma cell info
	 */
	public CellData(CellInfoCdma cinc) {
		this.type = 1;
		this.isRegistered = cinc.isRegistered();
		CellIdentityCdma cil = cinc.getCellIdentity();
		this.id = cil.getBasestationId();

		CellSignalStrengthCdma cssc = cinc.getCellSignalStrength();
		this.dbm = cssc.getDbm();
		this.asu = cssc.getAsuLevel();
		this.level = cssc.getLevel();
	}

	/**
	 * WCDMA constructor
	 * @param cinw WCDMA cell info
	 */
	public CellData(CellInfoWcdma cinw) {
		this.type = 2;
		this.isRegistered = cinw.isRegistered();
		CellIdentityWcdma ciw = cinw.getCellIdentity();
		this.id = ciw.getCid();
		this.mcc = ciw.getMcc();
		this.mnc = ciw.getMnc();

		CellSignalStrengthWcdma cssw = cinw.getCellSignalStrength();
		this.dbm = cssw.getDbm();
		this.asu = cssw.getAsuLevel();
		this.level = cssw.getLevel();
	}

	/**
	 * Converts int type to string
	 * @return type of network as string
	 */
	public String getType() {
		switch(type) {
			case 0:
				return "GSM";
			case 1:
				return "CDMA";
			case 2:
				return "WCDMA";
			case 3:
				return "LTE";
			default:
				return "UNKNOWN";
		}
	}
}
