package com.adsamcik.signalcollector.Play;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.view.View;

import com.adsamcik.signalcollector.Setting;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.games.Games;
import com.google.android.gms.location.ActivityRecognition;

public class PlayController {
    public static GoogleApiClient gapiActivityClient, gapiGamesClient;
    public static int registeredCount = 0;
    public static Context c;
    public static Activity a;
    public static boolean apiActivity = false;
    public static boolean apiGames = false;

    public static ActivityController activityController;
    public static GamesController gamesController;

    public static void setContext(Context context) {
        c = context;
    }

    public static void setActivity(Activity activity) {
        a = activity;
    }

    public static boolean InitializeActivityClient() {
        if (isPlayServiceAvailable()) {
            activityController = new ActivityController(c);
            gapiActivityClient = new GoogleApiClient.Builder(c)
                    .addApi(ActivityRecognition.API)
                    .addConnectionCallbacks(activityController)
                    .addOnConnectionFailedListener(activityController)
                    .build();

            activityController.setClient(gapiActivityClient);
            //Connect to Google API
            gapiActivityClient.connect();
            apiActivity = true;
            return true;
        }
        return false;
    }

    public static boolean InitializeGamesClient(View v) {
        if (isPlayServiceAvailable()) {
            gamesController = new GamesController(c, a);
            gapiGamesClient = new GoogleApiClient.Builder(c)
                    .addApi(Games.API)
                    .addScope(Games.SCOPE_GAMES)
                    .addConnectionCallbacks(gamesController)
                    .addOnConnectionFailedListener(gamesController)
                    .setViewForPopups(v)
                    .build();

            gamesController.setClient(gapiGamesClient).setUI(v);
            //Connect to Google API
            gapiGamesClient.connect();
            apiGames = true;
            return true;
        }
        return false;
    }

    public static void DestroyGamesClient() {
        gamesController.logout();
        Games.signOut(gapiGamesClient);
        gapiGamesClient.disconnect();
        gapiGamesClient = null;
        apiGames = false;
        Setting.sharedPreferences.edit().putBoolean(Setting.REGISTERED_USER, false);
    }

    public static void RegisterActivityReceiver(BroadcastReceiver receiver) {
        if (apiActivity) {
            //Filter the Intent and register broadcast receiver
            IntentFilter filter = new IntentFilter();
            filter.addAction("SCActivity");

            c.registerReceiver(receiver, filter);
            registeredCount++;
        }
    }

    public static void UnregisterActivityReceiver(BroadcastReceiver receiver) {
        if (apiActivity) {
            registeredCount--;
            c.unregisterReceiver(receiver);
        }
    }

    public static boolean isLogged() {
        return gapiGamesClient != null && gapiGamesClient.isConnected();
    }

    //Check for Google play services available on device
    public static boolean isPlayServiceAvailable() {
        GoogleApiAvailability gaa = GoogleApiAvailability.getInstance();
        return gaa != null && gaa.isGooglePlayServicesAvailable(c) == ConnectionResult.SUCCESS;
    }
}
