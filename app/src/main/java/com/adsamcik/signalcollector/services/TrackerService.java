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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.telephony.TelephonyManager;

import com.adsamcik.signalcollector.NoiseTracker;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.activities.MainActivity;
import com.adsamcik.signalcollector.data.Data;
import com.adsamcik.signalcollector.interfaces.ICallback;
import com.adsamcik.signalcollector.receivers.NotificationReceiver;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.Shortcuts;
import com.google.firebase.crash.FirebaseCrash;

import java.lang.ref.WeakReference;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class TrackerService extends Service {
	//Constants
	private static final String TAG = "SignalsTracker";
	private final static int LOCK_TIME_IN_MINUTES = 30;
	private final static int LOCK_TIME_IN_MILLISECONDS = LOCK_TIME_IN_MINUTES * Assist.MINUTE_IN_MILLISECONDS;
	private static final int NOTIFICATION_ID_SERVICE = 7643;
	private final int UPDATE_TIME_MILLISEC = 2 * Assist.SECOND_IN_MILLISECONDS;
	private final float MIN_DISTANCE_M = 5;
	public static ICallback onServiceStateChange;
	public static ICallback onNewDataFound;

	/**
	 * Data from previous collection
	 */
	public static Data dataEcho;
	/**
	 * Extra information about distance for tracker
	 */
	public static int distanceToWifi;

	/**
	 * Weak reference to service for AutoLock and check if service is running
	 */
	private static WeakReference<TrackerService> service;
	private static long lockedUntil;
	private static boolean backgroundActivated = false;
	private final float MAX_NOISE_TRACKING_SPEED_KM = 18;
	private final long TRACKING_ACTIVE_SINCE = System.currentTimeMillis();
	private final ArrayList<Data> data = new ArrayList<>();
	private long wifiScanTime;
	private boolean wasWifiEnabled = false;
	private int saveAttemptsFailed = 0;
	private LocationListener locationListener;
	private ScanResult[] wifiScanData;
	private WifiReceiver wifiReceiver;
	private NotificationManager notificationManager;

	private PowerManager powerManager;
	private PowerManager.WakeLock wakeLock;
	private LocationManager locationManager;
	private TelephonyManager telephonyManager;
	private WifiManager wifiManager;

	private NoiseTracker noiseTracker;
	private boolean noiseActive = false;

	/**
	 * True if previous collection was mocked
	 */
	private boolean prevMocked = false;

	/**
	 * Previous location of collection
	 */
	private Location prevLocation = null;

	/**
	 * Checks if service is running
	 *
	 * @return true if service is running
	 */
	public static boolean isRunning() {
		return service != null && service.get() != null;
	}

	/**
	 * Checks if Tracker is auto locked
	 *
	 * @return true if locked
	 */
	public static boolean isAutoLocked() {
		return System.currentTimeMillis() < lockedUntil;
	}

	/**
	 * Checks if tracker was activated in background
	 *
	 * @return true if activated by the app
	 */
	public static boolean isBackgroundActivated() {
		return backgroundActivated;
	}

	/**
	 * Sets auto lock with predefined time {@link TrackerService#LOCK_TIME_IN_MINUTES}
	 */
	public static int setAutoLock() {
		lockedUntil = System.currentTimeMillis() + LOCK_TIME_IN_MILLISECONDS;

		if (isRunning() && isBackgroundActivated())
			service.get().stopSelf();
		return LOCK_TIME_IN_MINUTES;
	}

	private void updateData(Location location) {
		if (location.isFromMockProvider()) {
			prevMocked = true;
			return;
		} else if (prevMocked && prevLocation != null) {
			prevMocked = false;
			if (location.distanceTo(prevLocation) < MIN_DISTANCE_M)
				return;
		}

		if (location.getAltitude() > 5600) {
			setAutoLock();
			//todo add notification
			if (!isBackgroundActivated())
				stopSelf();
			return;
		}

		wakeLock.acquire();
		Data d = new Data(System.currentTimeMillis());

		if (wifiManager != null) {
			if(prevLocation != null) {
				if (wifiScanData != null) {
					double timeDiff = (double) (wifiScanTime - prevLocation.getTime()) / (double) (d.time - prevLocation.getTime());
					if (timeDiff >= 0) {
						float distTo = location.distanceTo(Assist.interpolateLocation(prevLocation, location, timeDiff));
						distanceToWifi = (int) distTo;
						//Log.d(TAG, "dist to wifi " + distTo);
						int UPDATE_MAX_DISTANCE_TO_WIFI = 40;
						if (distTo <= UPDATE_MAX_DISTANCE_TO_WIFI && distTo > 0)
							d.setWifi(wifiScanData, wifiScanTime);
					}
					wifiScanData = null;
				} else {
					double timeDiff = (double) (wifiScanTime - prevLocation.getTime()) / (double) (d.time - prevLocation.getTime());
					if (timeDiff >= 0) {
						float distTo = location.distanceTo(Assist.interpolateLocation(prevLocation, location, timeDiff));
						distanceToWifi += (int) distTo;
					}
				}
			}

			wifiManager.startScan();
		}

		if (telephonyManager != null && !Assist.isAirplaneMode(this)) {
			d.setCell(telephonyManager.getNetworkOperatorName(), telephonyManager.getAllCellInfo());
		}

		if (noiseTracker != null) {
			int evalActivity = Assist.evaluateActivity(ActivityService.lastActivity);
			float MAX_NOISE_TRACKING_SPEED_M = (float) (MAX_NOISE_TRACKING_SPEED_KM / 3.6);
			if ((evalActivity == 1 || (noiseActive && evalActivity == 3)) && location.getSpeed() < MAX_NOISE_TRACKING_SPEED_M) {
				noiseTracker.start();
				short value = noiseTracker.getSample(10);
				if (value >= 0)
					d.setNoise(value);
				noiseActive = true;
			} else {
				noiseTracker.stop();
				noiseActive = false;
			}
		}

		d.setLocation(location).setActivity(ActivityService.lastActivity);

		data.add(d);
		dataEcho = d;

		DataStore.incSizeOfData(DataStore.objectToJSON(d).getBytes(Charset.defaultCharset()).length);

		prevLocation = location;
		prevLocation.setTime(d.time);

		notificationManager.notify(NOTIFICATION_ID_SERVICE, generateNotification(true, d));

		if (onNewDataFound != null) {
			onNewDataFound.callback();
		}

		if (data.size() > 5)
			saveData();

		if (backgroundActivated && powerManager.isPowerSaveMode())
			stopSelf();

		wakeLock.release();
	}


	private void saveData() {
		if (data.size() == 0) return;

		SharedPreferences sp = Preferences.get(getApplicationContext());
		Preferences.checkStatsDay(getApplicationContext());

		int wifiCount, cellCount, locations;

		wifiCount = sp.getInt(Preferences.STATS_WIFI_FOUND, 0);
		cellCount = sp.getInt(Preferences.STATS_CELL_FOUND, 0);
		locations = sp.getInt(Preferences.STATS_LOCATIONS_FOUND, 0);
		for (Data d : data) {
			if (d.wifi != null)
				wifiCount += d.wifi.length;
			if (d.cellCount != -1)
				cellCount += d.cellCount;
		}

		sp.edit().putInt(Preferences.STATS_WIFI_FOUND, wifiCount).putInt(Preferences.STATS_CELL_FOUND, cellCount).putInt(Preferences.STATS_LOCATIONS_FOUND, locations + data.size()).apply();

		String input = DataStore.arrayToJSON(data.toArray(new Data[data.size()]));
		input = input.substring(1, input.length() - 1);

		int result = DataStore.saveData(input);
		if (result == 1) {
			saveAttemptsFailed++;
			if (saveAttemptsFailed >= 5)
				stopSelf();
		} else {
			data.clear();
			if (result == 2 && DataStore.sizeOfData() > Assist.MB_IN_BYTES * Preferences.get(this).getInt(Preferences.AUTO_UPLOAD_AT_MB, Preferences.DEFAULT_AUTO_UPLOAD_AT_MB)) {
				UploadService.requestUpload(getApplicationContext(), UploadService.UploadScheduleSource.BACKGROUND);
				FirebaseCrash.log("Requested upload from tracking");
			}
		}
	}

	private Notification generateNotification(boolean gpsAvailable, Data d) {
		Intent intent = new Intent(this, MainActivity.class);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setSmallIcon(R.drawable.ic_signals_notification)  // the status icon
				.setTicker(getString(R.string.notification_tracker_active_ticker))  // the status text
				.setWhen(System.currentTimeMillis())  // the time stamp
				.setContentIntent(PendingIntent.getActivity(this, 0, intent, 0)) // The intent to send when the entry is clicked
				.setColor(ContextCompat.getColor(getApplicationContext(), R.color.colorPrimary));

		Intent stopIntent = new Intent(this, NotificationReceiver.class);
		stopIntent.putExtra(NotificationReceiver.ACTION_STRING, backgroundActivated ? 0 : 1);
		PendingIntent stop = PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT);
		if (backgroundActivated)
			builder.addAction(R.drawable.ic_battery_alert_black_24dp, getString(R.string.notification_stop_til_recharge), stop);
		else
			builder.addAction(R.drawable.ic_pause, getString(R.string.notification_stop), stop);

		if (!gpsAvailable)
			builder.setContentTitle(getString(R.string.notification_looking_for_gps));
		else {
			builder.setContentTitle(getString(R.string.notification_tracking_active));
			builder.setContentText(buildNotificationText(d));
		}

		return builder.build();
	}

	private String buildNotificationText(final Data d) {
		StringBuilder sb = new StringBuilder();
		DecimalFormat df = new DecimalFormat("#.#");
		df.setRoundingMode(RoundingMode.HALF_UP);
		if (d.wifi != null)
			sb.append(d.wifi.length).append(" wifi ");
		if (d.cellCount != -1)
			sb.append(d.cellCount).append(" cell ");
		if (d.noise > 0)
			sb.append(df.format(Assist.amplitudeToDbm(d.noise))).append(" dB ");
		if (sb.length() > 0)
			sb.setLength(sb.length() - 1);
		else
			sb.append("Nothing found");
		return sb.toString();
	}


	@Override
	public void onCreate() {
		service = new WeakReference<>(this);
		Context appContext = getApplicationContext();
		DataStore.setContext(appContext);
		Assist.initialize(appContext);
		SharedPreferences sp = Preferences.get(appContext);

		ActivityService.initializeActivityClient(appContext);

		//Get managers
		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		//Enable location update

		locationListener = new LocationListener() {
			public void onLocationChanged(Location location) {
				updateData(location);

			}

			public void onStatusChanged(String provider, int status, Bundle extras) {
				if (status == LocationProvider.TEMPORARILY_UNAVAILABLE || status == LocationProvider.OUT_OF_SERVICE)
					notificationManager.notify(NOTIFICATION_ID_SERVICE, generateNotification(false, null));
			}

			public void onProviderEnabled(String provider) {
			}

			public void onProviderDisabled(String provider) {
				if (provider.equals(LocationManager.GPS_PROVIDER))
					stopSelf();
			}
		};

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_TIME_MILLISEC, MIN_DISTANCE_M, locationListener);

		//Wifi tracking setup
		if (sp.getBoolean(Preferences.TRACKING_WIFI_ENABLED, true)) {
			wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

			wasWifiEnabled = !(wifiManager.isScanAlwaysAvailable() || wifiManager.isWifiEnabled());
			if (wasWifiEnabled)
				wifiManager.setWifiEnabled(true);

			wifiManager.startScan();
			registerReceiver(wifiReceiver = new WifiReceiver(), new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
		}

		//Cell tracking setup
		if (sp.getBoolean(Preferences.TRACKING_CELL_ENABLED, true)) {
			telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
		}

		powerManager = (PowerManager) getSystemService(POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackerWakeLock");

		if (android.os.Build.VERSION.SDK_INT >= 25 && Shortcuts.initializeShortcuts(this))
			Shortcuts.updateShortcut(this, Shortcuts.TRACKING_ID, getString(R.string.shortcut_stop_tracking), getString(R.string.shortcut_stop_tracking_long), R.drawable.ic_pause, Shortcuts.ShortcutType.STOP_COLLECTION);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		lockedUntil = 0;
		backgroundActivated = intent == null || intent.getBooleanExtra("backTrack", false);
		startForeground(NOTIFICATION_ID_SERVICE, generateNotification(false, null));
		if (onServiceStateChange != null)
			onServiceStateChange.callback();
		if (Preferences.get(this).getBoolean(Preferences.TRACKING_NOISE_ENABLED, false))
			noiseTracker = new NoiseTracker(this).start();
		return super.onStartCommand(intent, flags, startId);
	}


	@Override
	public void onDestroy() {
		stopForeground(true);
		service = null;

		if (noiseTracker != null)
			noiseTracker.stop();

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			locationManager.removeUpdates(locationListener);
		unregisterReceiver(wifiReceiver);

		saveData();
		if (onServiceStateChange != null)
			onServiceStateChange.callback();
		DataStore.cleanup();

		if (wasWifiEnabled) {
			if (!powerManager.isInteractive())
				wifiManager.setWifiEnabled(false);
		}

		SharedPreferences sp = Preferences.get(getApplicationContext());
		sp.edit().putInt(Preferences.STATS_MINUTES, sp.getInt(Preferences.STATS_MINUTES, 0) + (int) ((System.currentTimeMillis() - TRACKING_ACTIVE_SINCE) / Assist.MINUTE_IN_MILLISECONDS)).apply();

		if (android.os.Build.VERSION.SDK_INT >= 25 && Shortcuts.initializeShortcuts(this))
			Shortcuts.updateShortcut(this, Shortcuts.TRACKING_ID, getString(R.string.shortcut_start_tracking), getString(R.string.shortcut_start_tracking_long), R.drawable.ic_play, Shortcuts.ShortcutType.START_COLLECTION);
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}


	private class WifiReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			wifiScanTime = System.currentTimeMillis();
			List<ScanResult> result = wifiManager.getScanResults();
			wifiScanData = result.toArray(new ScanResult[result.size()]);
		}
	}
}