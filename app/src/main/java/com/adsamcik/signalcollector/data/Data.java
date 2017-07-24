package com.adsamcik.signalcollector.data;

import android.location.Location;
import android.net.wifi.ScanResult;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import com.google.firebase.crash.FirebaseCrash;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class Data implements Serializable {
	/**
	 * Time of collection in milliseconds since midnight, January 1, 1970 UTC
	 */
	public final long time;

	/**
	 * Longitude
	 */
	public double longitude;

	/**
	 * Latitude
	 */
	public double latitude;

	/**
	 * Altitude
	 */
	public double altitude;

	/**
	 * Accuracy in meters
	 */
	public float accuracy;

	/**
	 * List of registered cells
	 * Null if not collected
	 */
	public CellData[] regCells = null;

	/**
	 * Total cell count
	 * default (0) if not collected.
	 */
	public int cellCount;

	/**
	 * Array of collected wifi networks
	 */
	public WifiData[] wifi = null;

	/**
	 * Time of collection of wifi data
	 */
	public long wifiTime;

	/**
	 * Current resolved activity
	 */
	public int activity;

	public short noise;

	/**
	 * Data constructor
	 *
	 * @param time collection time
	 */
	public Data(long time) {
		this.time = time;
	}

	/**
	 * Sets collection location
	 *
	 * @param location location
	 * @return this
	 */
	public Data setLocation(@NonNull Location location) {
		this.longitude = location.getLongitude();
		this.latitude = location.getLatitude();
		this.altitude = location.getAltitude();
		this.accuracy = location.getAccuracy();
		return this;
	}

	/**
	 * Sets wifi and time of wifi collection
	 *
	 * @param data data
	 * @param time time of collection
	 * @return this
	 */
	public Data setWifi(ScanResult[] data, long time) {
		if (data != null && time > 0) {
			wifi = new WifiData[data.length];
			for (int i = 0; i < data.length; i++)
				wifi[i] = new WifiData(data[i]);
			this.wifiTime = time;
		}
		return this;
	}

	/**
	 * Sets activity
	 *
	 * @param activity activity
	 * @return this
	 */
	public Data setActivity(int activity) {
		this.activity = activity;
		return this;
	}

	/**
	 * Sets noise value.
	 *
	 * @param noise Noise value. Must be absolute amplitude.
	 * @return this
	 */
	public Data setNoise(short noise) {
		if (noise > 0)
			this.noise = noise;
		return this;
	}

	/**
	 * Sets current active cell from nearby cells
	 * <p>
	 * //* @param operator current network operator
	 * //* @param data     nearby cell
	 *
	 */
	public void addCell(@NonNull TelephonyManager telephonyManager) {
		List<CellInfo> cellInfos = telephonyManager.getAllCellInfo();
		String nOp = telephonyManager.getNetworkOperator();
		if (!nOp.isEmpty()) {
			short mcc = Short.parseShort(nOp.substring(0, 3));
			short mnc = Short.parseShort(nOp.substring(3));

			if (cellInfos != null) {
				cellCount = cellInfos.size();
				ArrayList<CellData> registeredCells = new ArrayList<>(Build.VERSION.SDK_INT >= 23 ? telephonyManager.getPhoneCount() : 1);
				for (CellInfo ci : cellInfos) {
					if (ci.isRegistered()) {
						CellData cd = null;
						if (ci instanceof CellInfoLte) {
							CellInfoLte cig = (CellInfoLte) ci;
							if (cig.getCellIdentity().getMnc() == mnc && cig.getCellIdentity().getMcc() == mcc)
								cd = CellData.newInstance(cig, telephonyManager.getNetworkOperatorName());
							else
								cd = CellData.newInstance(cig, (String) null);
						} else if (ci instanceof CellInfoGsm) {
							CellInfoGsm cig = (CellInfoGsm) ci;
							if (cig.getCellIdentity().getMnc() == mnc && cig.getCellIdentity().getMcc() == mcc)
								cd = CellData.newInstance(cig, telephonyManager.getNetworkOperatorName());
							else
								cd = CellData.newInstance(cig, (String) null);
						} else if (ci instanceof CellInfoWcdma) {
							CellInfoWcdma cig = (CellInfoWcdma) ci;
							if (cig.getCellIdentity().getMnc() == mnc && cig.getCellIdentity().getMcc() == mcc)
								cd = CellData.newInstance(cig, telephonyManager.getNetworkOperatorName());
							else
								cd = CellData.newInstance(cig, (String) null);
						} else if (ci instanceof CellInfoCdma) {
							CellInfoCdma cic = (CellInfoCdma) ci;
						/*if (cic.getCellIdentity().getMnc() == mnc && cic.getCellIdentity().getMcc() == mcc)
							addCell(CellData.newInstance(cic, telephonyManager.getNetworkOperatorName()));
						else*/
							cd = CellData.newInstance(cic, (String) null);
						} else {
							FirebaseCrash.report(new Throwable("UNKNOWN CELL TYPE"));
						}

						if (cd != null)
							registeredCells.add(cd);
					}
				}

				regCells = new CellData[registeredCells.size()];
				registeredCells.toArray(regCells);
			}
		}
	}

	/**
	 * Returns cells that user is registered to
	 *
	 * @return list of cells
	 */
	public CellData[] getRegisteredCells() {
		return regCells;
	}
}
