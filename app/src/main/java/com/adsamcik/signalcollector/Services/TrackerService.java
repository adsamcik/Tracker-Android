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
import android.telephony.TelephonyManager;
import android.util.Log;

import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.Extensions;
import com.adsamcik.signalcollector.MainActivity;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;
import com.adsamcik.signalcollector.data.Data;
import com.adsamcik.signalcollector.interfaces.ICallback;
import com.adsamcik.signalcollector.play.PlayController;
import com.adsamcik.signalcollector.receivers.NotificationReceiver;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class TrackerService extends Service implements SensorEventListener {
	//Constants
	private static final String TAG = "SignalsTracker";
	private final static int LOCK_TIME_IN_MINUTES = 30;
	private final static int LOCK_TIME_IN_MILLISECONDS = LOCK_TIME_IN_MINUTES * Extensions.MINUTE_IN_MILLISECONDS;
	private final int UPDATE_TIME_MILLISEC = 2 * Extensions.SECOND_IN_MILLISECONDS;
	private final int UPDATE_MAX_DISTANCE_TO_WIFI = 40;
	private final float MIN_DISTANCE_M = 5;

	public static boolean isActive = false;
	public static Intent service;

	public static long approxSize = 0;

	public static ICallback onNewDataFound;
	public static Data dataEcho;
	public static int distanceToWifi;

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

	static boolean isAutoLocked() {
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

	private void updateData(Location location) {
		wakeLock.acquire();
		Data d = new Data(location.getTime());

		if (wifiScanData != null && wifiScanPos != null) {
			long currentTime = Calendar.getInstance().getTimeInMillis();
			long timeDiff = (wifiScanTime - wifiScanPos.getTime()) / (currentTime - wifiScanPos.getTime());
			float distTo = wifiScanPos.distanceTo(Extensions.interpolateLocation(wifiScanPos, location, timeDiff));
			distanceToWifi = (int) distTo;
			if (distTo > UPDATE_MAX_DISTANCE_TO_WIFI)
				wifiScanData = null;
			else
				d.setWifi(wifiScanData, wifiScanTime);
		}

		SharedPreferences sp = Setting.getPreferences(getApplicationContext());

		if (sp.getBoolean(Setting.TRACKING_WIFI_ENABLED, true)) {
			wifiManager.startScan();
			wifiScanPos = location;
		}

		if (sp.getBoolean(Setting.TRACKING_CELL_ENABLED, true) && !isAirplaneModeOn(this))
			d.setCell(telephonyManager.getNetworkOperator(), telephonyManager.getAllCellInfo());

		if (sp.getBoolean(Setting.TRACKING_PRESSURE_ENABLED, true))
			d.setPressure(pressureValue);
		d.setLocation(location).setActivity(currentActivity);

		data.add(d);
		dataEcho = d;

		approxSize += DataStore.objectToJSON(d).getBytes(Charset.defaultCharset()).length;

		notificationManager.notify(1, generateNotification(true, d));
		if (onNewDataFound != null)
			onNewDataFound.onCallback();

		if (data.size() > 10)
			saveData();


		wifiScanData = null;

		if (backgroundActivated && powerManager.isPowerSaveMode())
			stopSelf();

		wakeLock.release();
	}

	private Notification generateNotification(boolean gpsAvailable, Data d) {
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
			if (d.wifi != null)
				if (d.cell != null)
					builder.setContentText("Found " + d.wifi.length + " wifi and " + d.cell.length + " cell");
				else
					builder.setContentText("Found " + d.wifi.length + " wifi");
			else if (d.cell != null)
				builder.setContentText("Found " + d.cell.length + " cell");
			else
				builder.setContentText("Nothing found");
		}

		return builder.build();
	}

	private void saveData() {
		if (data.size() == 0) return;

		SharedPreferences sp = Setting.getPreferences(getApplicationContext());
		Setting.checkStatsDay(getApplicationContext());

		int wifiCount, cellCount, locations;

		wifiCount = sp.getInt(Setting.STATS_WIFI_FOUND, 0);
		cellCount = sp.getInt(Setting.STATS_CELL_FOUND, 0);
		locations = sp.getInt(Setting.STATS_LOCATIONS_FOUND, 0);
		for (Data d : data) {
			if (d.wifi != null)
				wifiCount += d.wifi.length;
			if (d.cell != null)
				cellCount += d.cell.length;
		}

		sp.edit().putInt(Setting.STATS_WIFI_FOUND, wifiCount).putInt(Setting.STATS_CELL_FOUND, cellCount).putInt(Setting.STATS_LOCATIONS_FOUND, locations + data.size()).apply();

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

		Context appContext = getApplicationContext();
		DataStore.setContext(appContext);

		if (!PlayController.apiActivity)
			PlayController.initializeActivityClient(appContext);

		activityReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (intent.getIntExtra("confidence", -1) > 85 && backgroundActivated) {
					currentActivity = intent.getIntExtra("activity", -1);
					int evalActivity = Extensions.evaluateActivity(currentActivity);
					int backTrackVal = Setting.getPreferences(getApplicationContext()).getInt(Setting.BACKGROUND_TRACKING, 1);
					if (evalActivity == 0 || (backTrackVal == 1 && evalActivity == 2) || backTrackVal == 0)
						stopSelf();
				}
			}
		};

		PlayController.registerActivityReceiver(activityReceiver, getApplicationContext());

		locationListener = new LocationListener() {
			public void onLocationChanged(Location location) {
				updateData(location);
			}

			public void onStatusChanged(String provider, int status, Bundle extras) {
				if (status == LocationProvider.TEMPORARILY_UNAVAILABLE || status == LocationProvider.OUT_OF_SERVICE)
					notificationManager.notify(1, generateNotification(false, null));
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
		startForeground(1, generateNotification(false, null));
		return super.onStartCommand(intent, flags, startId);
	}


	@Override
	public void onDestroy() {
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			locationManager.removeUpdates(locationListener);

		unregisterReceiver(wifiReceiver);
		PlayController.unregisterActivityReceiver(activityReceiver, getApplicationContext());
		mSensorManager.unregisterListener(this);

		isActive = false;
		saveData();
		if (!wifiEnabled)
			wifiManager.setWifiEnabled(false);
		DataStore.cleanup();

		stopForeground(true);
		instance = null;
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
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
			wifiScanTime = System.currentTimeMillis();
		}
	}
}