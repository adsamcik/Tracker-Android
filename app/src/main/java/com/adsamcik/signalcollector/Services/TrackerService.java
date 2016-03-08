package com.adsamcik.signalcollector.Services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.adsamcik.signalcollector.Data.CellData;
import com.adsamcik.signalcollector.Data.Data;
import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.Extensions;
import com.adsamcik.signalcollector.Fragments.FragmentMain;
import com.adsamcik.signalcollector.MainActivity;
import com.adsamcik.signalcollector.Play.PlayController;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class TrackerService extends Service implements SensorEventListener {
	public static final String TAG = "SignalsTracker";

	public static boolean isActive = false;
	public static Intent service;

	public static long approxSize = 0;
	public final int UPDATE_TIME = 2000;
	public final float MIN_DISTANCE_M = 5;

	final ArrayList<Data> data = new ArrayList<>();
	LocationListener locationListener;
	ScanResult[] wifiScanData;
	CellInfo[] cellScanData;
	LocationManager locationManager;
	TelephonyManager telephonyManager;
	WifiManager wifiManager;
	WifiReceiver wifiReceiver;
	SensorManager mSensorManager;
	Sensor mPressure;
	BroadcastReceiver activityReceiver;

	float pressureValue;
	int currentActivity;
	boolean backgroundActivated = false;
	boolean wifiEnabled = false;

	int saveAttemptsFailed = 0;

	NotificationManager notificationManager;
	PowerManager powerManager;
	PowerManager.WakeLock wakeLock;

	private static boolean isAirplaneModeOn(Context context) {
		return Settings.Global.getInt(context.getContentResolver(),
				Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
	}

	public void makeUseOfNewLocation(Location location) {
		wakeLock.acquire();
		wifiManager.startScan();

		if(!isAirplaneModeOn(this)) {
			List<CellInfo> cells = telephonyManager.getAllCellInfo();
			if(cells != null)
				cellScanData = cells.toArray(new CellInfo[cells.size()]);
		}

		Data d = new Data(location.getTime(), location.getLongitude(), location.getLatitude(), location.getAltitude(), location.getAccuracy(), cellScanData, wifiScanData, pressureValue, telephonyManager.getNetworkOperatorName(), currentActivity);
		data.add(d);
		if(data.size() > 10)
			saveData();

		int cellCount = -1;
		int cellDbm = 0, cellAsu = 0;
		String cellType = "";
		if(cellScanData != null) {
			for(CellData cd : d.cell) {
				if(cd.isRegistered) {
					cellDbm = cd.dbm;
					cellAsu = cd.asu;
					cellType = cd.getType();
				}
			}
			cellCount = d.cell.length;
		}

		int wifiCount = d.wifi == null ? -1 : d.wifi.length;
		approxSize += DataStore.objectToJSON(d).getBytes(Charset.defaultCharset()).length;
		sendUpdateInfoBroadcast(d.time, wifiCount, cellCount, cellDbm, cellAsu, cellType, d.longitude, d.latitude, d.altitude, d.accuracy, pressureValue, Extensions.getActivityName(currentActivity));

		UpdateNotification(true, wifiCount, cellCount);
		wifiScanData = null;
		cellScanData = null;

		if(backgroundActivated && powerManager.isPowerSaveMode())
			stopSelf();

		wakeLock.release();
	}

	void UpdateNotification(boolean gpsAvailable, int wifiCount, int cellCount) {
		//Intent pause = new Intent(this, TrackerService.class);
		//Notification.Action.Builder playPause = new Notification.Action.Builder(R.drawable.ic_stop_black_36dp, "Stop", PendingIntent.getActivity(this, 1, pause, 0));
		Intent intent = new Intent(this, MainActivity.class);
		Notification.Builder builder = new Notification.Builder(this)
				.setSmallIcon(R.drawable.ic_notification_icon)  // the status icon
				.setTicker("Collection started")  // the status text
				.setWhen(System.currentTimeMillis())  // the time stamp
				.setContentTitle(getResources().getString(R.string.app_name))// the label of the entry
				//.addAction(playPause.build())
				.setContentIntent(PendingIntent.getActivity(this, 0, intent, 0)); // The intent to send when the entry is clicked

		builder.setColor(ContextCompat.getColor(getApplicationContext(), R.color.colorPrimary));

		if(!gpsAvailable)
			builder.setContentText("Looking for GPS");
		else {
			if(wifiCount >= 0)
				if(cellCount >= 0)
					builder.setContentText("Found " + wifiCount + " wifi and " + cellCount + " cell");
				else
					builder.setContentText("Found " + wifiCount + " wifi");
			else if(cellCount >= 0)
				builder.setContentText("Found " + cellCount + " cell");
		}

		notificationManager.notify(1, builder.build());
	}

	void saveData() {
		if(data.size() == 0) return;
		String input = DataStore.arrayToJSON(data.toArray(new Data[data.size()]));
		input = input.substring(1, input.length() - 1);

		int result = DataStore.saveData(input);
		if(result == 1) {
			saveAttemptsFailed++;
			if(saveAttemptsFailed >= 5)
				stopSelf();
		} else {
			data.clear();
			if(result == 2)
				DataStore.requestUpload(getApplicationContext());
		}
	}


	@Override
	public void onCreate() {
		approxSize = 0;
		isActive = true;
		sendStatusBroadcast(-1, 1);

		Context appContext = getApplicationContext();
		DataStore.setContext(appContext);

		if(PlayController.c == null)
			PlayController.setContext(appContext);
		if(!PlayController.apiActivity)
			PlayController.initializeActivityClient();

		activityReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if(intent.getIntExtra("confidence", -1) > 85) {
					currentActivity = intent.getIntExtra("activity", -1);
					int evalActivity = Extensions.EvaluateActivity(currentActivity);
					int backTrackVal = Setting.getPreferences(getApplicationContext()).getInt(Setting.BACKGROUND_TRACKING, 1);
					if(backgroundActivated && ((backTrackVal == 1 && evalActivity == 2) || backTrackVal == 0))
						stopSelf();
				}
			}
		};

		PlayController.registerActivityReceiver(activityReceiver);

		locationListener = new LocationListener() {
			public void onLocationChanged(Location location) {
				makeUseOfNewLocation(location);
			}

			public void onStatusChanged(String provider, int status, Bundle extras) {
				if(status == LocationProvider.TEMPORARILY_UNAVAILABLE || status == LocationProvider.OUT_OF_SERVICE)
					UpdateNotification(false, 0, 0);
			}

			public void onProviderEnabled(String provider) {
			}

			public void onProviderDisabled(String provider) {
			}
		};

		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
		notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		//Setup notification
		Intent intent = new Intent(this, MainActivity.class);
		Notification n = new Notification.Builder(this)
				.setSmallIcon(R.drawable.ic_notification_icon)
				.setTicker("Collection started")
				.setWhen(System.currentTimeMillis())
				.setContentTitle(getResources().getString(R.string.app_name))
				.setContentText("Initializing")
				.setContentIntent(PendingIntent.getActivity(this, 0, intent, 0))
				.build();

		startForeground(1, n);

		UpdateNotification(false, 0, 0);

		//Enable location update
		if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_TIME, MIN_DISTANCE_M, locationListener);

		wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

		wifiEnabled = wifiManager.isWifiEnabled();
		if(!wifiEnabled)
			wifiManager.setWifiEnabled(true);

		wifiManager.startScan();
		registerReceiver(wifiReceiver = new WifiReceiver(), new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		if(mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null) {
			mPressure = mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
			mSensorManager.registerListener(this, mPressure, SensorManager.SENSOR_DELAY_UI);
		}

		powerManager = (PowerManager) getSystemService(POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackerWakeLock");
	}

	public static void onUploadComplete(int maxUploadId) {
		SharedPreferences sp = Setting.getPreferences();
		int maxId = sp.getInt(DataStore.KEY_FILE_ID, 0);
		int currentId = 0;

		//Split in two to save a bit of power
		for(int i = 0; i <= maxUploadId; i++)
			if(DataStore.exists(DataStore.DATA_FILE + i))
				DataStore.moveFile(DataStore.DATA_FILE + i, DataStore.DATA_FILE + currentId++);

		for(int i = maxUploadId; i <= maxId; i++)
			DataStore.moveFile(DataStore.DATA_FILE + i, DataStore.DATA_FILE + currentId++);

		sp.edit().putInt(DataStore.KEY_FILE_ID, currentId).putLong(DataStore.KEY_SIZE, DataStore.recountDataSize()).apply();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if(intent == null) {
			approxSize = DataStore.recountDataSize();
			backgroundActivated = true;
			Log.d(TAG, "null intent");
		} else {
			approxSize = intent.getLongExtra("approxSize", 0);
			backgroundActivated = intent.getBooleanExtra("backTrack", false);
		}
		return super.onStartCommand(intent, flags, startId);
	}


	@Override
	public void onDestroy() {
		if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			locationManager.removeUpdates(locationListener);

		unregisterReceiver(wifiReceiver);
		PlayController.unregisterActivityReceiver(activityReceiver);

		mSensorManager.unregisterListener(this);
		//LocalBroadcastManager.getInstance(MainActivity.instance).sendBroadcast();
		saveData();
		if(!wifiEnabled)
			wifiManager.setWifiEnabled(false);
		stopForeground(true);
		sendStatusBroadcast(-1, 0);
		isActive = false;
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private void sendStatusBroadcast(int cloudStatus, int trackerStatus) {
		Intent intent = new Intent(MainActivity.StatusReceiver.BROADCAST_TAG);
		intent.putExtra("cloudStatus", cloudStatus);
		intent.putExtra("trackerStatus", trackerStatus);
		LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
	}

	private void sendUpdateInfoBroadcast(long time, int wifiCount, int cellCount, int cellDbm,
	                                     int cellAsu, String cellType, double longitude, double latitude,
	                                     double altitude, double accuracy, float pressure, String activity) {
		Intent intent = new Intent(FragmentMain.UpdateInfoReceiver.BROADCAST_TAG);
		intent.putExtra("time", time);
		intent.putExtra("wifiCount", wifiCount);
		intent.putExtra("cellCount", cellCount);
		intent.putExtra("cellDbm", cellDbm);
		intent.putExtra("cellAsu", cellAsu);
		intent.putExtra("cellType", cellType);
		intent.putExtra("longitude", longitude);
		intent.putExtra("latitude", latitude);
		intent.putExtra("altitude", altitude);
		intent.putExtra("accuracy", (int) accuracy);
		intent.putExtra("approxSize", approxSize);
		intent.putExtra("pressure", pressure);
		intent.putExtra("activity", activity);
		LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		pressureValue = event.values[0];
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}

	private class WifiReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			List<ScanResult> result = wifiManager.getScanResults();
			wifiScanData = result.toArray(new ScanResult[result.size()]);
		}
	}
}
