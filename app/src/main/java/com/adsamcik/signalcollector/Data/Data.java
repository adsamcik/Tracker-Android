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

	public Data setCell(String operator, @NonNull CellInfo[] data) {
		CellData[] cellData = new CellData[data.length];
		for (int i = 0; i < data.length; i++) {
			if (data[i] instanceof CellInfoGsm)
				cellData[i] = new CellData((CellInfoGsm) data[i]);
			else if (data[i] instanceof CellInfoLte)
				cellData[i] = new CellData((CellInfoLte) data[i]);
			else if (data[i] instanceof CellInfoCdma)
				cellData[i] = new CellData((CellInfoCdma) data[i]);
			else if (data[i] instanceof CellInfoWcdma)
				cellData[i] = new CellData((CellInfoWcdma) data[i]);
		}
		setCell(operator, cellData);
		return this;
	}

	public Data setCell(String operator, @NonNull CellData[] data) {
		this.cell = data;
		this.networkOperator = operator;
		return this;
	}
}
