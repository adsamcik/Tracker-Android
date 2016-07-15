package com.adsamcik.signalcollector.services;

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
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.adsamcik.signalcollector.data.CellData;
import com.adsamcik.signalcollector.data.Data;
import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.Extensions;
import com.adsamcik.signalcollector.fragments.FragmentMain;
import com.adsamcik.signalcollector.MainActivity;
import com.adsamcik.signalcollector.play.PlayController;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.receivers.NotificationReceiver;
import com.adsamcik.signalcollector.Setting;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class TrackerService extends Service implements SensorEventListener {
	//Constants
	private static final String TAG = "SignalsTracker";
	private final static int LOCK_TIME_IN_MINUTES = 30;
	private final static int LOCK_TIME_IN_MILLISECONDS = LOCK_TIME_IN_MINUTES * 60000;
	private final int UPDATE_TIME_MILLISEC = 2000;
	private final float MIN_DISTANCE_M = 5;

	public static boolean isActive = false;
	public static Intent service;

	public static long approxSize = 0;
	private static long lockedUntil;
	private static TrackerService instance;

	private Location wifiScanPos;
	private long wifiScanTime;

	private final ArrayList<Data> data = new ArrayList<>();
	private LocationListener locationListener;
	private ScanResult[] wifiScanData;
	private LocationManager locationManager;
	private TelephonyManager telephonyManager;
	private WifiManager wifiManager;
	private WifiReceiver wifiReceiver;
	private SensorManager mSensorManager;
	private Sensor mPressure;
	private BroadcastReceiver activityReceiver;

	private float pressureValue;
	private int currentActivity;
	private boolean backgroundActivated = false;
	private boolean wifiEnabled = false;

	private int saveAttemptsFailed = 0;

	private NotificationManager notificationManager;
	private PowerManager powerManager;
	private PowerManager.WakeLock wakeLock;

	public static boolean isAutoLocked() {
		return System.currentTimeMillis() < lockedUntil;
	}

	public static void setAutoLock() {
		if (instance.backgroundActivated)
			lockedUntil = System.currentTimeMillis() + LOCK_TIME_IN_MILLISECONDS;
	}

	private static boolean isAirplaneModeOn(Context context) {
		return Settings.Global.getInt(context.getContentResolver(),
				Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
	}

	private void makeUseOfNewLocation(Location location) {
		wakeLock.acquire();
		if (wifiScanData != null && wifiScanPos != null) {
			float distTo = wifiScanPos.distanceTo(location);
			if ((distTo > 3 * MIN_DISTANCE_M && wifiScanTime - (1.5f * Calendar.getInstance().getTimeInMillis()) > 0) || distTo > 80)
				wifiScanData = null;
		}

		Data d = new Data(location.getTime());

		if (wifiScanData != null)
			d.setWifi(wifiScanData, wifiScanTime);

		wifiManager.startScan();
		wifiScanPos = location;

		if (!isAirplaneModeOn(this))
			d.setCell(telephonyManager.getNetworkOperator(), telephonyManager.getAllCellInfo());

		d.setLocation(location).setPressure(pressureValue).setActivity(currentActivity);
		if (wifiScanData != null)
			d.setWifi(wifiScanData, wifiScanTime);

		data.add(d);

		if (data.size() > 10)
			saveData();

		int cellCount = -1;
		int cellDbm = 0, cellAsu = 0;
		String cellType = "";
		if (d.cell != null) {
			for (CellData cd : d.cell) {
				if (cd.isRegistered) {
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

		notificationManager.notify(1, generateNotification(true, wifiCount, cellCount));
		wifiScanData = null;

		if (backgroundActivated && powerManager.isPowerSaveMode())
			stopSelf();

		wakeLock.release();
	}

	private Notification generateNotification(boolean gpsAvailable, int wifiCount, int cellCount) {
		Intent intent = new Intent(this, MainActivity.class);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
				.setSmallIcon(R.drawable.ic_notification_icon)  // the status icon
				.setTicker("Collection started")  // the status text
				.setWhen(System.currentTimeMillis())  // the time stamp
				.setContentTitle(getResources().getString(R.string.app_name))// the label of the entry
				.setContentIntent(PendingIntent.getActivity(this, 0, intent, 0)) // The intent to send when the entry is clicked
				.setColor(ContextCompat.getColor(getApplicationContext(), R.color.colorPrimary));

		if (backgroundActivated) {
			Intent stopIntent = new Intent(this, NotificationReceiver.class);
			PendingIntent stop = PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT);
			builder.addAction(R.drawable.ic_battery_alert_black_24dp, "Stop till recharge", stop);
		}

		if (!gpsAvailable)
			builder.setContentText("Looking for GPS");
		else {
			if (wifiCount >= 0)
				if (cellCount >= 0)
					builder.setContentText("Found " + wifiCount + " wifi and " + cellCount + " cell");
				else
					builder.setContentText("Found " + wifiCount + " wifi");
			else if (cellCount >= 0)
				builder.setContentText("Found " + cellCount + " cell");
		}

		return builder.build();
	}

	private void saveData() {
		if (data.size() == 0) return;

		SharedPreferences sp = Setting.getPreferences(getApplicationContext());
		int wifiCount, cellCount;

		//todo check date
		wifiCount = sp.getInt(Setting.TRACKING_WIFI_FOUND, 0);
		cellCount = sp.getInt(Setting.TRACKING_CELL_FOUND, 0);
		for (Data d : data) {
			if (d.wifi != null)
				wifiCount += d.wifi.length;
			if (d.cell != null)
				cellCount += d.cell.length;
		}

		sp.edit().putInt(Setting.TRACKING_WIFI_FOUND, wifiCount).putInt(Setting.TRACKING_CELL_FOUND, cellCount).apply();

		String input = DataStore.arrayToJSON(data.toArray(new Data[data.size()]));
		input = input.substring(1, input.length() - 1);

		int result = DataStore.saveData(input);
		if (result == 1) {
			saveAttemptsFailed++;
			if (saveAttemptsFailed >= 5)
				stopSelf();
		} else {
			data.clear();
			if (result == 2)
				DataStore.requestUpload(getApplicationContext(), true);
		}
	}


	@Override
	public void onCreate() {
		instance = this;
		approxSize = 0;
		isActive = true;
		sendStatusBroadcast(-1, 1);

		Context appContext = getApplicationContext();
		DataStore.setContext(appContext);

		PlayController.setContext(appContext);

		if (!PlayController.apiActivity)
			PlayController.initializeActivityClient();

		activityReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				//Log.d(TAG, "confidence " + intent.getIntExtra("confidence", -1) + " bg activated " + backgroundActivated);
				if (intent.getIntExtra("confidence", -1) > 85 && backgroundActivated) {
					currentActivity = intent.getIntExtra("activity", -1);
					int evalActivity = Extensions.evaluateActivity(currentActivity);
					int backTrackVal = Setting.getPreferences(getApplicationContext()).getInt(Setting.BACKGROUND_TRACKING, 1);
					//Log.d(TAG, "eval activity " + evalActivity + " saved " + backTrackVal);
					if (evalActivity == 0 || (backTrackVal == 1 && evalActivity == 2) || backTrackVal == 0)
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
				if (status == LocationProvider.TEMPORARILY_UNAVAILABLE || status == LocationProvider.OUT_OF_SERVICE)
					notificationManager.notify(1, generateNotification(false, 0, 0));
			}

			public void onProviderEnabled(String provider) {
			}

			public void onProviderDisabled(String provider) {
			}
		};

		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
		notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		//Enable location update
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_TIME_MILLISEC, MIN_DISTANCE_M, locationListener);

		wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

		wifiEnabled = wifiManager.isWifiEnabled();
		if (!wifiEnabled && !wifiManager.isScanAlwaysAvailable())
			wifiManager.setWifiEnabled(true);

		wifiManager.startScan();
		registerReceiver(wifiReceiver = new WifiReceiver(), new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		if (mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null) {
			mPressure = mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
			mSensorManager.registerListener(this, mPressure, SensorManager.SENSOR_DELAY_UI);
		}

		powerManager = (PowerManager) getSystemService(POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackerWakeLock");
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null) {
			approxSize = DataStore.recountDataSize();
			backgroundActivated = true;
			Log.d(TAG, "Tracker services started with null intent");
		} else {
			approxSize = intent.getLongExtra("approxSize", 0);
			backgroundActivated = intent.getBooleanExtra("backTrack", false);
		}
		startForeground(1, generateNotification(false, -1, -1));
		return super.onStartCommand(intent, flags, startId);
	}


	@Override
	public void onDestroy() {
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			locationManager.removeUpdates(locationListener);

		unregisterReceiver(wifiReceiver);
		PlayController.unregisterActivityReceiver(activityReceiver);
		mSensorManager.unregisterListener(this);

		isActive = false;
		saveData();
		if (!wifiEnabled)
			wifiManager.setWifiEnabled(false);
		DataStore.cleanup();

		stopForeground(true);
		sendStatusBroadcast(-1, 0);
		instance = null;
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
			wifiScanTime = Calendar.getInstance().getTimeInMillis();
		}
	}
}
