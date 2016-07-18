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

	public CellData[] cell = null;
	public String networkOperator = null;

	public WifiData[] wifi = null;
	public long wifiTime;

	public double longitude;
	public double latitude;
	public double altitude;
	public float accuracy;

	public float pressure;
	public int activity;

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

	public Data setWifi(@NonNull ScanResult[] data, long time) {
		wifi = new WifiData[data.length];
		for(int i = 0; i < data.length; i++)
			wifi[i] = new WifiData(data[i]);
		this.wifiTime = time;
		return this;
	}

	public Data setActivity(int activity) {
		this.activity = activity;
		return this;
	}

	public Data setCell(String operator, @NonNull List<CellInfo> data) {
		CellData[] cellData = new CellData[data.size()];
		for (int i = 0; i < data.size(); i++) {
			CellInfo c = data.get(i);
			if (c instanceof CellInfoGsm)
				cellData[i] = new CellData((CellInfoGsm) c);
			else if (c instanceof CellInfoLte)
				cellData[i] = new CellData((CellInfoLte) c);
			else if (c instanceof CellInfoCdma)
				cellData[i] = new CellData((CellInfoCdma) c);
			else if (c instanceof CellInfoWcdma)
				cellData[i] = new CellData((CellInfoWcdma) c);
		}
		setCell(operator, cellData);
		return this;
	}

	public Data setCell(String operator, @NonNull CellData[] data) {
		this.cell = data;
		this.networkOperator = operator;
		return this;
	}

	public CellData GetActiveCell() {
		for (CellData cd : cell) {
			if (cd.isRegistered)
				return cd;
		}
		return null;
	}
}
