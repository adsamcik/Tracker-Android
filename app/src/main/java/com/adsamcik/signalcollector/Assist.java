package com.adsamcik.signalcollector;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.adsamcik.signalcollector.classes.Network;
import com.adsamcik.signalcollector.interfaces.IValueCallback;
import com.google.android.gms.location.DetectedActivity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Assist {
	public static final int SECOND_IN_MILLISECONDS = 1000;
	public static final int MINUTE_IN_MILLISECONDS = 60 * SECOND_IN_MILLISECONDS;
	public static final int HOUR_IN_MILLISECONDS = 60 * MINUTE_IN_MILLISECONDS;
	public static final int DAY_IN_MILLISECONDS = 24 * HOUR_IN_MILLISECONDS;


	private static TelephonyManager telephonyManager;
	private static ConnectivityManager connectivityManager;

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public static boolean isInitialized() {
		return telephonyManager != null;
	}

	public static void initialize(Context c) {
		telephonyManager = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
		connectivityManager = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
	}

	@SuppressWarnings("unused")
	public static String humanReadableByteCount(long bytes, @SuppressWarnings("SameParameterValue") boolean si) {
		int unit = si ? 1000 : 1024;
		if (bytes < unit) return bytes + " B";
		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
		return String.format(Locale.ENGLISH, "%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

	public static String humanReadableByteCount(long bytes) {
		final int unit = 1024;
		if (bytes < unit) return bytes + " B";
		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = "KMGTPE".charAt(exp - 1) + "i";
		return String.format(Locale.ENGLISH, "%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

	/**
	 * Gets SW navbar height
	 *
	 * @param c context
	 * @return height, 0 if HW navbar is present
	 */
	public static int getNavBarHeight(@NonNull Context c) {
		Resources r = c.getResources();
		int resourceId = r.getIdentifier("navigation_bar_height", "dimen", "android");
		if (resourceId > 0)
			return r.getDimensionPixelSize(resourceId);
		return 0;
	}

	public static DisplayMetrics getDisplayMetrics(@NonNull WindowManager windowManager) {
		Display d = windowManager.getDefaultDisplay();
		DisplayMetrics realDisplayMetrics = new DisplayMetrics();
		d.getRealMetrics(realDisplayMetrics);
		return  realDisplayMetrics;
	}

	/**
	 * Checks if device has SW or HW navbar
	 *
	 * @param windowManager Window Manager
	 * @return true if SW navbar is present
	 */
	public static boolean hasNavBar(@NonNull WindowManager windowManager) {
		Display d = windowManager.getDefaultDisplay();

		DisplayMetrics realDisplayMetrics = new DisplayMetrics();
		d.getRealMetrics(realDisplayMetrics);

		int realHeight = realDisplayMetrics.heightPixels;
		int realWidth = realDisplayMetrics.widthPixels;

		DisplayMetrics displayMetrics = new DisplayMetrics();
		d.getMetrics(displayMetrics);

		int displayHeight = displayMetrics.heightPixels;
		int displayWidth = displayMetrics.widthPixels;

		return (realWidth - displayWidth) > 0 || (realHeight - displayHeight) > 0;
	}

	public static int dpToPx(@NonNull Context c, int dp) {
		return Math.round(dp * c.getResources().getDisplayMetrics().density);
	}

	public static int pxToDp(@NonNull Context c, int px) {
		return Math.round(px / c.getResources().getDisplayMetrics().density);
	}

	public static int ptToPx(@NonNull Context c, int pt) {
		return (int) (pt * c.getResources().getDisplayMetrics().density + 0.5f);
	}

	/**
	 * Generates position between two passed positions based on time
	 *
	 * @param locationOne first location
	 * @param locationTwo second location
	 * @param time        Value between 0 and 1. 0 is locationOne, 1 is locationTwo
	 * @return interpolated location
	 */
	public static Location interpolateLocation(@NonNull Location locationOne, @NonNull Location locationTwo, double time) {
		if (time < 0 || time > 1)
			throw new IllegalArgumentException("Time must be between 0 and 1. is " + time);
		Location l = new Location("interpolation");
		l.setLatitude(locationOne.getLatitude() + (locationTwo.getLatitude() - locationOne.getLatitude()) * time);
		l.setLongitude(locationOne.getLongitude() + (locationTwo.getLongitude() - locationOne.getLongitude()) * time);
		l.setAltitude(locationOne.getAltitude() + (locationTwo.getAltitude() - locationOne.getAltitude()) * time);
		return l;
	}

	/**
	 * Checks if required permission are available
	 * ACCESS_FINE_LOCATION - GPS
	 * READ_PHONE_STATE - IMEI
	 *
	 * @param context context
	 * @return permissions that app does not have, null if api is lower than 23 or all permission are acquired
	 */
	public static String[] checkTrackingPermissions(@NonNull Context context) {
		if (Build.VERSION.SDK_INT > 22) {
			List<String> permissions = new ArrayList<>();
			if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
				permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION);

			if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
				permissions.add(android.Manifest.permission.READ_PHONE_STATE);

			if (Preferences.get(context).getBoolean(Preferences.TRACKING_NOISE_ENABLED, false) && ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
				permissions.add(android.Manifest.permission.RECORD_AUDIO);

			if (permissions.size() == 0)
				return null;

			return permissions.toArray(new String[permissions.size()]);
		}
		return null;
	}

	/**
	 * Checks if upload can be initiated
	 *
	 * @param c            context
	 * @param isBackground true if NOT activated by user
	 * @return true if upload can be initiated
	 */
	public static boolean canUpload(final @NonNull Context c, final boolean isBackground) {
		if (!isInitialized() || connectivityManager == null)
			initialize(c);
		NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
		if (isBackground) {
			int aVal = Preferences.get(c).getInt(Preferences.AUTO_UPLOAD, 1);
			return activeNetwork != null && activeNetwork.isConnectedOrConnecting() &&
					(activeNetwork.getType() == ConnectivityManager.TYPE_WIFI ||
							(aVal == 2 && activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE && !activeNetwork.isRoaming()));
		} else
			return !activeNetwork.isRoaming();
	}

	/**
	 * Converts numbers to easier to read format (spaces after every 3 digits)
	 *
	 * @param number number
	 * @return stringified number
	 */
	public static String easierToReadNumber(final int number) {
		StringBuilder sb = new StringBuilder(number);
		for (int i = sb.length(); i > 0; i -= 3)
			sb.insert(i, " ");
		return sb.toString();
	}

	/**
	 * @return Device imei
	 */
	@SuppressLint("HardwareIds")
	public static String getImei() {
		if (telephonyManager == null)
			throw new NullPointerException("Assist were not initialized, this is a bug.");
		return telephonyManager.getDeviceId();
	}

	/**
	 * Converts amplitude to dbm
	 *
	 * @param amplitude amplitude
	 * @return dbm
	 */
	public static double amplitudeToDbm(final double amplitude) {
		return 20 * Math.log10(Math.abs(amplitude));
	}

	/**
	 * 0 still/default
	 * 1 foot
	 * 2 vehicle
	 * 3 tilting
	 */
	public static int evaluateActivity(final int val) {
		switch (val) {
			case DetectedActivity.STILL:
				return 0;
			case DetectedActivity.RUNNING:
				return 1;
			case DetectedActivity.ON_FOOT:
				return 1;
			case DetectedActivity.WALKING:
				return 1;
			case DetectedActivity.ON_BICYCLE:
				return 2;
			case DetectedActivity.IN_VEHICLE:
				return 2;
			case DetectedActivity.TILTING:
				return 3;
			default:
				return 3;
		}
	}

	/**
	 * Returns activity as string
	 *
	 * @param type type of activity (ENUM)
	 * @return name of the activity
	 */
	public static String getActivityName(int type) {
		switch (type) {
			case DetectedActivity.IN_VEHICLE:
				return "In Vehicle";
			case DetectedActivity.ON_BICYCLE:
				return "On Bicycle";
			case DetectedActivity.ON_FOOT:
				return "On Foot";
			case DetectedActivity.WALKING:
				return "Walking";
			case DetectedActivity.STILL:
				return "Still";
			case DetectedActivity.TILTING:
				return "Tilting";
			case DetectedActivity.RUNNING:
				return "Running";
			case DetectedActivity.UNKNOWN:
				return "Unknown";
		}
		return "N/A";
	}

	/**
	 * Converts coordinate to string
	 *
	 * @param coordinate coordinate
	 * @return stringified coordinate
	 */
	public static String coordsToString(double coordinate) {
		int degree = (int) coordinate;
		coordinate = (coordinate - degree) * 60;
		int minute = (int) coordinate;
		coordinate = (coordinate - minute) * 60;
		int second = (int) coordinate;
		return String.format(Locale.ENGLISH, "%02d", degree) + "\u00B0 " + String.format(Locale.ENGLISH, "%02d", minute) + "' " + String.format(Locale.ENGLISH, "%02d", second) + "\"";
	}

	public static long getDayInUTC() {
		Calendar c = Calendar.getInstance();
		c.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		return c.getTimeInMillis();
	}

	public static void getMapOverlays(final SharedPreferences sharedPreferences, final IValueCallback<String> callback) {
		long lastUpdate = sharedPreferences.getLong(Preferences.AVAILABLE_MAPS_LAST_UPDATE, -1);
		if (lastUpdate == -1 || System.currentTimeMillis() - lastUpdate > Assist.DAY_IN_MILLISECONDS) {
			if(!isConnected() && lastUpdate != -1) {
				callback.callback(sharedPreferences.getString(Preferences.AVAILABLE_MAPS, null));
				return;
			}

			OkHttpClient client = new OkHttpClient();
			Request request = new Request.Builder().url(Network.URL_MAPS_AVAILABLE).build();

			client.newCall(request).enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException e) {

				}

				@Override
				public void onResponse(Call call, Response response) throws IOException {
					String json = response.body().string();
					sharedPreferences.edit()
							.putLong(Preferences.AVAILABLE_MAPS_LAST_UPDATE, System.currentTimeMillis())
							.putString(Preferences.AVAILABLE_MAPS, json)
							.apply();
					callback.callback(json);
				}
			});
		} else {
			callback.callback(sharedPreferences.getString(Preferences.AVAILABLE_MAPS, null));
		}
	}

	public static boolean isConnected() {
		return connectivityManager.getActiveNetworkInfo().isConnected();
	}

	public static int getAgeInDays(long time) {
		return (int)((System.currentTimeMillis() - time) / DAY_IN_MILLISECONDS);
	}
}
