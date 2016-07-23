package com.adsamcik.signalcollector.play;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.util.Log;
import android.view.View;

import com.adsamcik.signalcollector.Setting;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.games.Games;
import com.google.android.gms.location.ActivityRecognition;

public class PlayController {
	public static final String TAG = "PLAY";

	public static GoogleApiClient gapiActivityClient, gapiGamesClient;
	public static Activity activity;
	public static boolean apiActivity = false;
	public static boolean apiGames = false;

	public static ActivityController activityController;
	public static GamesController gamesController;

	public static void setActivity(Activity activity) {
		PlayController.activity = activity;
	}

	public static boolean initializeActivityClient(Context context) {
		if(isPlayServiceAvailable(context)) {
			activityController = new ActivityController(context);
			gapiActivityClient = new GoogleApiClient.Builder(context)
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
		Log.w(TAG, "Play services not available");
		return false;
	}

	public static boolean initializeGamesClient(View v, Context context) {
		if(isPlayServiceAvailable(context)) {
			gamesController = new GamesController(activity);
			gapiGamesClient = new GoogleApiClient.Builder(context)
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
		Log.w(TAG, "Play services not available");
		return false;
	}

	public static void destroyGamesClient() {
		if(!isLogged()) return;
		gamesController.logout();
		Games.signOut(gapiGamesClient);
		gapiGamesClient.disconnect();
		gapiGamesClient = null;
		apiGames = false;
		Setting.getPreferences().edit().putBoolean(Setting.REGISTERED_USER, false).apply();
	}

	public static void registerActivityReceiver(BroadcastReceiver receiver, Context context) {
		if(apiActivity) {
			//Filter the Intent and register broadcast receiver
			IntentFilter filter = new IntentFilter();
			filter.addAction("SCActivity");
			context.registerReceiver(receiver, filter);
		}
		else
			Log.w(TAG, "Registration failed - play api not initialized");
	}

	public static void unregisterActivityReceiver(BroadcastReceiver receiver, Context context) {
		if(apiActivity)
			context.unregisterReceiver(receiver);
	}

	public static boolean isLogged() {
		return gapiGamesClient != null && gapiGamesClient.isConnected();
	}

	//Check for Google play services available on device
	public static boolean isPlayServiceAvailable(Context context) {
		GoogleApiAvailability gaa = GoogleApiAvailability.getInstance();
		return gaa != null && gaa.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS;
	}
}
