package com.adsamcik.signalcollector.data;

import android.location.Location;
import android.net.wifi.ScanResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;

import java.io.Serializable;
import java.util.List;

@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class Data implements Serializable {
	public final long time;

	public CellData activeCell = null;
	public String networkOperator = null;
	public int cellCount = -1;

	public WifiData[] wifi = null;
	public long wifiTime;

	public double longitude;
	public double latitude;
	public double altitude;
	public float accuracy;

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
	 *
	 * @param operator current network operator
	 * @param data     nearby cell
	 * @return this
	 */
	public Data setCell(String operator, List<CellInfo> data) {
		if (data != null) {
			cellCount = data.size();
			boolean found = false;
			for (int i = 0; i < data.size(); i++) {
				CellInfo c = data.get(i);
				if (c.isRegistered()) {
					if (c instanceof CellInfoGsm)
						setCell(operator, new CellData((CellInfoGsm) c));
					else if (c instanceof CellInfoLte)
						setCell(operator, new CellData((CellInfoLte) c));
					else if (c instanceof CellInfoCdma)
						setCell(operator, new CellData((CellInfoCdma) c));
					else if (c instanceof CellInfoWcdma)
						setCell(operator, new CellData((CellInfoWcdma) c));
					found = true;
					break;
				}
			}

			if (!found)
				setCell("", (CellData) null);
		}
		return this;
	}

	/**
	 * Sets active cell and network operator
	 *
	 * @param operator   cell network operator
	 * @param activeCell active cell
	 * @return this
	 */
	private Data setCell(@NonNull String operator, @Nullable CellData activeCell) {
		this.activeCell = activeCell;
		this.networkOperator = operator;
		return this;
	}

	/**
	 * Returns active cell
	 *
	 * @return active cell
	 */
	public CellData getActiveCell() {
		return activeCell;
	}
}
