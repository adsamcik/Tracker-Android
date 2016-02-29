package com.adsamcik.signalcollector;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.adsamcik.signalcollector.Services.TrackerService;
import com.google.android.gms.location.DetectedActivity;

import java.nio.charset.Charset;

public class Extensions {
    private static TelephonyManager telephonyManager;

    public static void Initialize(TelephonyManager tm) {
        telephonyManager = tm;
    }

    public static String humanReadableByteCount(long bytes, @SuppressWarnings("SameParameterValue") boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    public static int getNavBarHeight(Context c) {
        int result = 0;
        boolean hasMenuKey = ViewConfiguration.get(c).hasPermanentMenuKey();
        boolean hasBackKey = KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK);

        if (!hasMenuKey && !hasBackKey) {
            //The device has a navigation bar
            Resources resources = c.getResources();

            int orientation = resources.getConfiguration().orientation;
            int resourceId = resources.getIdentifier(orientation == Configuration.ORIENTATION_PORTRAIT ? "navigation_bar_height" : "navigation_bar_width", "dimen", "android");

            if (resourceId > 0)
                return c.getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    //0-still, 1-foot, 2-vehicle
    public static int EvaluateActivity(int val) {
        switch (val) {
            case DetectedActivity.STILL:
                return 0;
            case DetectedActivity.ON_BICYCLE:
                return 1;
            case DetectedActivity.IN_VEHICLE:
                return 1;
            case DetectedActivity.RUNNING:
                return 2;
            case DetectedActivity.ON_FOOT:
                return 2;
            case DetectedActivity.WALKING:
                return 2;
            case DetectedActivity.TILTING:
                return 3;
            default:
                return 0;
        }
    }

    public static boolean canBackgroundTrack(Context c, int evalActivity) {
        if(evalActivity == 3 || evalActivity == 0 || TrackerService.isActive) return false;
        if(Setting.sharedPreferences == null) Setting.Initialize(PreferenceManager.getDefaultSharedPreferences(c));
        int val = Setting.sharedPreferences.getInt(Setting.BACKGROUND_TRACKING, 1);
        return val == 2 || val == evalActivity;
    }

    public static String EasierToReadNumber(int number) {
        StringBuilder sb = new StringBuilder(number);
        for (int i = sb.length(); i > 0; i-=3)
            sb.insert(i," ");
        return sb.toString();
    }

    public static String getImei() {
        if (telephonyManager == null)
            throw new NullPointerException("Extensions were not initialized, this is a bug.");
        return telephonyManager.getDeviceId();
    }

    /**
     * Returns activity as string
     * @param type  type of activity (ENUM)
     * @return      name of the activity
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

}
