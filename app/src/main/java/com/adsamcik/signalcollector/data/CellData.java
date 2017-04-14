package com.adsamcik.signalcollector.data;

import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
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
import android.telephony.SubscriptionInfo;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;


public class CellData implements Serializable {
	public static final int GSM = 0;
	public static final int CDMA = 1;
	public static final int WCDMA = 2;
	public static final int LTE = 3;

	public final String operatorName;
	public final int type;
	public final int id;
	public final Integer mcc;
	public final Integer mnc;
	public final int dbm;
	public final int asu;
	public final int level;

	public CellData(String operatorName, int type, int id, Integer mcc, Integer mnc, int dbm, int asu, int level) {
		this.operatorName = operatorName;
		this.type = type;
		this.id = id;
		this.mcc = mcc;
		this.mnc = mnc;
		this.dbm = dbm;
		this.asu = asu;
		this.level = level;
	}

	/**
	 * Finds carrier name in subscriptions
	 *
	 * @param mnc                  Mobile network code
	 * @param mcc                  Mobile country code
	 * @param subscriptionInfoList Subscribed sim cards
	 * @return carrier name or null if not found
	 */
	@RequiresApi(22)
	private static String getCarrierName(int mnc, int mcc, @NonNull List<SubscriptionInfo> subscriptionInfoList) {
		if (mcc == Integer.MAX_VALUE)
			return null;

		for (SubscriptionInfo si : subscriptionInfoList) {
			if (si.getMcc() == mcc && si.getMnc() == mnc) {
				return si.getCarrierName().toString();
			}
		}

		return null;
	}

	@RequiresApi(22)
	private static String getCarrierNameAndRemove(int mnc, int mcc, @NonNull List<SubscriptionInfo> siList) {
		if (mcc == Integer.MAX_VALUE)
			return null;

		for (Iterator<SubscriptionInfo> iter = siList.iterator(); iter.hasNext(); ) {
			SubscriptionInfo si = iter.next();
			if (si.getMcc() == mcc && si.getMnc() == mnc) {
				String carrierName = si.getCarrierName().toString();
				iter.remove();
				return carrierName;
			}
		}

		return null;
	}

	/**
	 * Creates new instance of CellData from GSM cell info
	 *
	 * @param cing         GSM cell info
	 * @param operatorName network operator name
	 * @return new CellData if successfull, null otherwise
	 */
	public static CellData newInstance(CellInfoGsm cing, String operatorName) {
		if (operatorName == null)
			return null;
		CellIdentityGsm cig = cing.getCellIdentity();
		CellSignalStrengthGsm cssg = cing.getCellSignalStrength();
		return new CellData(operatorName, GSM, cig.getCid(), cig.getMcc(), cig.getMnc(), cssg.getDbm(), cssg.getAsuLevel(), cssg.getLevel());
	}


	@RequiresApi(22)
	public static CellData newInstance(@NonNull CellInfoGsm cing, @NonNull List<SubscriptionInfo> subscriptionInfoList) {
		CellIdentityGsm cig = cing.getCellIdentity();
		return newInstance(cing, getCarrierNameAndRemove(cig.getMnc(), cig.getMcc(), subscriptionInfoList));
	}

	/**
	 * Creates new instance of CellData from CDMA cell info
	 *
	 * @param cinc         CDMA cell info
	 * @param operatorName network operator name
	 * @return new CellData if successfull, null otherwise
	 */
	public static CellData newInstance(CellInfoCdma cinc, String operatorName) {
		if (operatorName == null)
			return null;
		CellIdentityCdma cic = cinc.getCellIdentity();
		CellSignalStrengthCdma cssg = cinc.getCellSignalStrength();
		return new CellData(operatorName, CDMA, cic.getBasestationId(), cic.getNetworkId(), cic.getSystemId(), cssg.getDbm(), cssg.getAsuLevel(), cssg.getLevel());
	}

	@RequiresApi(22)
	public static CellData newInstance(@NonNull CellInfoCdma cinc, @NonNull List<SubscriptionInfo> subscriptionInfoList) {
		if (subscriptionInfoList.size() == 1)
			return newInstance(cinc, subscriptionInfoList.get(0).getCarrierName().toString());
		else
			return null;
	}

	/**
	 * Creates new instance of CellData from WCDMA cell info
	 *
	 * @param cinl         WCDMA cell info
	 * @param operatorName network operator name
	 * @return new CellData if successfull, null otherwise
	 */
	public static CellData newInstance(CellInfoWcdma cinl, String operatorName) {
		if (operatorName == null)
			return null;
		CellIdentityWcdma cil = cinl.getCellIdentity();
		CellSignalStrengthWcdma cssg = cinl.getCellSignalStrength();
		return new CellData(operatorName, WCDMA, cil.getCid(), cil.getMcc(), cil.getMnc(), cssg.getDbm(), cssg.getAsuLevel(), cssg.getLevel());
	}


	@RequiresApi(22)
	public static CellData newInstance(@NonNull CellInfoWcdma cinl, @NonNull List<SubscriptionInfo> subscriptionInfoList) {
		if (subscriptionInfoList.size() == 1)
			return newInstance(cinl, subscriptionInfoList.get(0).getCarrierName().toString());
		else {
			CellIdentityWcdma cil = cinl.getCellIdentity();
			return newInstance(cinl, getCarrierNameAndRemove(cil.getMnc(), cil.getMcc(), subscriptionInfoList));
		}
	}


	/**
	 * Creates new instance of CellData from LTE cell info
	 *
	 * @param cinl         LTE Cell Info
	 * @param operatorName network operator name
	 * @return new CellData if successfull, null otherwise
	 */
	public static CellData newInstance(CellInfoLte cinl, String operatorName) {
		if (operatorName == null)
			return null;
		CellIdentityLte cil = cinl.getCellIdentity();
		CellSignalStrengthLte cssg = cinl.getCellSignalStrength();
		return new CellData(operatorName, LTE, cil.getCi(), cil.getMcc(), cil.getMnc(), cssg.getDbm(), cssg.getAsuLevel(), cssg.getLevel());
	}


	@RequiresApi(22)
	public static CellData newInstance(@NonNull CellInfoLte cinl, @NonNull List<SubscriptionInfo> subscriptionInfoList) {
		if (subscriptionInfoList.size() == 1)
			return newInstance(cinl, subscriptionInfoList.get(0).getCarrierName().toString());
		else {
			CellIdentityLte cil = cinl.getCellIdentity();
			return newInstance(cinl, getCarrierNameAndRemove(cil.getMnc(), cil.getMcc(), subscriptionInfoList));
		}
	}

	/**
	 * Converts int type to string
	 *
	 * @return type of network as string
	 */
	public String getType() {
		switch (type) {
			case GSM:
				return "GSM";
			case CDMA:
				return "CDMA";
			case WCDMA:
				return "WCDMA";
			case LTE:
				return "LTE";
			default:
				return "UNKNOWN";
		}
	}
}
