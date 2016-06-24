package com.adsamcik.signalcollector;

import android.content.Context;
import android.content.res.Resources;
import android.support.annotation.NonNull;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.adsamcik.signalcollector.services.TrackerService;
import com.google.android.gms.location.DetectedActivity;

import java.util.Locale;

public class Extensions {
	private static TelephonyManager telephonyManager;

	public static boolean isInitialized() {
		return telephonyManager != null;
	}

	public static void initialize(TelephonyManager tm) {
		telephonyManager = tm;
	}

	@SuppressWarnings("unused")
	public static String humanReadableByteCount(long bytes, @SuppressWarnings("SameParameterValue") boolean si) {
		int unit = si ? 1000 : 1024;
		if(bytes < unit) return bytes + " B";
		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
		return String.format(Locale.ENGLISH, "%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

	public static String humanReadableByteCount(long bytes) {
		final int unit = 1024;
		if(bytes < unit) return bytes + " B";
		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = "KMGTPE".charAt(exp - 1) + "i";
		return String.format(Locale.ENGLISH, "%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

	public static int getNavBarHeight(@NonNull Context c) {
		Resources r = c.getResources();
		int resourceId = r.getIdentifier("navigation_bar_height", "dimen", "android");
		if(resourceId > 0)
			return r.getDimensionPixelSize(resourceId);
		return 0;
	}

	public static boolean hasNavBar(WindowManager windowManager) {
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
		DisplayMetrics displayMetrics = c.getResources().getDisplayMetrics();
		return Math.round(dp * (displayMetrics.xdpi / DisplayMetrics.DENSITY_DEFAULT));
	}

	public static int pxToDp(@NonNull Context c, int px) {
		DisplayMetrics displayMetrics = c.getResources().getDisplayMetrics();
		return Math.round(px / (displayMetrics.xdpi / DisplayMetrics.DENSITY_DEFAULT));
	}

	public static int ptToPx(@NonNull Context c, int pt) {
		final float scale = c.getResources().getDisplayMetrics().density;
		return (int) (pt * scale + 0.5f);
	}


	/**
	 * 0 still/default
	 * 1 foot
	 * 2 vehicle
	 * 3 tilting
	 */

	public static int evaluateActivity(int val) {
		switch(val) {
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
				return 0;
		}
	}

	public static boolean canBackgroundTrack(Context c, int evalActivity) {
		if(evalActivity == 3 || evalActivity == 0 || TrackerService.isActive || Setting.getPreferences(c).getBoolean(Setting.STOP_TILL_RECHARGE, false))
			return false;
		int val = Setting.getPreferences(c).getInt(Setting.BACKGROUND_TRACKING, 1);
		return val != 0 && (val == evalActivity || val > evalActivity);
	}


	public static String easierToReadNumber(int number) {
		StringBuilder sb = new StringBuilder(number);
		for(int i = sb.length(); i > 0; i -= 3)
			sb.insert(i, " ");
		return sb.toString();
	}

	public static String getImei() {
		if(telephonyManager == null)
			throw new NullPointerException("Extensions were not initialized, this is a bug.");
		return telephonyManager.getDeviceId();
	}

	/**
	 * Returns activity as string
	 *
	 * @param type type of activity (ENUM)
	 * @return name of the activity
	 */
	public static String getActivityName(int type) {
		switch(type) {
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
	 * @param coordinate
	 * @return stringified coordinate
	 */
	public static String coordsToString(double coordinate) {
		int degree = (int) coordinate;
		coordinate = (coordinate - degree) * 60;
		int minute = (int) coordinate;
		coordinate = (coordinate - minute) * 60;
		int second = (int) coordinate;
		return String.format(Locale.ENGLISH, "%02d", degree) + "° " + String.format(Locale.ENGLISH, "%02d", minute) + "' " + String.format(Locale.ENGLISH, "%02d", second) + "\"";
	}
}
