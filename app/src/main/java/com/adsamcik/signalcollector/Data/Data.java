package com.adsamcik.signalcollector.data;

import android.location.Location;
import android.net.wifi.ScanResult;
import android.support.annotation.NonNull;
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

	public float pressure;
	public int activity;

	public double noise;

	public Data(long time) {
		this.time = time;
	}

	public Data setPressure(float pressure) {
		this.pressure = pressure;
		return this;
	}

	public Data setLocation(@NonNull Location location) {
		this.longitude = location.getLongitude();
		this.latitude = location.getLatitude();
		this.altitude = location.getAltitude();
		this.accuracy = location.getAccuracy();
		return this;
	}

	public Data setWifi(ScanResult[] data, long time) {
		if (data != null) {
			wifi = new WifiData[data.length];
			for (int i = 0; i < data.length; i++)
				wifi[i] = new WifiData(data[i]);
			this.wifiTime = time;
		}
		return this;
	}

	public Data setActivity(int activity) {
		this.activity = activity;
		return this;
	}

	public Data setNoise(double noise) {
		if (noise > 0)
			this.noise = noise;
		return this;
	}

	public Data setCell(String operator, List<CellInfo> data) {
		if (data != null) {
			for (int i = 0; i < data.size(); i++) {
				CellInfo c = data.get(i);
				if(c.isRegistered()) {
					if (c instanceof CellInfoGsm)
						setCell(operator, new CellData((CellInfoGsm) c));
					else if (c instanceof CellInfoLte)
						setCell(operator, new CellData((CellInfoLte) c));
					else if (c instanceof CellInfoCdma)
						setCell(operator, new CellData((CellInfoCdma) c));
					else if (c instanceof CellInfoWcdma)
						setCell(operator, new CellData((CellInfoWcdma) c));
					break;
				}
			}
			cellCount = data.size();
		}
		return this;
	}

	private Data setCell(String operator, @NonNull CellData activeCell) {
		this.activeCell = activeCell;
		this.networkOperator = operator;
		return this;
	}

	public CellData getActiveCell() {
		return activeCell;
	}
}
