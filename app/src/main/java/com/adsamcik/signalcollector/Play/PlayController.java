package com.adsamcik.signalcollector.play;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.View;

import com.adsamcik.signalcollector.Setting;
import com.adsamcik.signalcollector.classes.Success;
import com.adsamcik.signalcollector.services.ActivityService;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.games.Games;
import com.google.android.gms.location.ActivityRecognition;
import com.google.firebase.crash.FirebaseCrash;

public class PlayController {
	private static final String TAG = "SignalsPlay";

	private static GoogleApiClient gapiActivityClient, gapiGamesClient;
	public static boolean apiActivity = false;
	public static boolean apiGames = false;

	public static GamesController gamesController;

	public static Success<String> initializeActivityClient(@NonNull Context context) {
		if (isPlayServiceAvailable(context)) {
			if (gapiActivityClient == null) {
				final Context appContext = context.getApplicationContext();
				if (appContext == null) {
					FirebaseCrash.report(new Throwable("Application context is null"));
					return new Success<>("Failed to initialize automatic tracking");
				}
				gapiActivityClient = new GoogleApiClient.Builder(appContext)
						.addApi(ActivityRecognition.API)
						.addOnConnectionFailedListener(connectionResult -> {
							FirebaseCrash.report(new Throwable("Failed to initialize activity " + connectionResult.getErrorMessage() + " code " + connectionResult.getErrorCode()));
						})
						.addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
							@Override
							public void onConnected(@Nullable Bundle bundle) {
								ActivityService.requestUpdate(gapiActivityClient, context);
							}

							@Override
							public void onConnectionSuspended(int i) {

							}
						})
						.build();
			}
			gapiActivityClient.connect();
			apiActivity = true;
			return new Success<>();
		}
		return new Success<>("Play services are not available");
	}

	public static Success<String> initializeActivityClient(@NonNull FragmentActivity activity) {
		if (isPlayServiceAvailable(activity)) {
			if (gapiActivityClient == null) {
				final Context appContext = activity.getApplicationContext();
				if (appContext == null) {
					FirebaseCrash.report(new Throwable("Application context is null"));
					return new Success<>("Failed to initialize automatic tracking");
				}
				gapiActivityClient = new GoogleApiClient.Builder(appContext)
						.addApi(ActivityRecognition.API)
						.addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
							@Override
							public void onConnected(@Nullable Bundle bundle) {
								ActivityService.requestUpdate(gapiActivityClient, activity);
							}

							@Override
							public void onConnectionSuspended(int i) {

							}
						})
						.enableAutoManage(activity, ActivityService.GOOGLE_API_ID, null)
						.build();
			}
			gapiActivityClient.connect();
			apiActivity = true;
			return new Success<>();
		}
		return new Success<>("Play services are not available");
	}

	public static Success<String> initializeGamesClient(@NonNull View v, @NonNull FragmentActivity activity) {
		if (isPlayServiceAvailable(activity)) {
			if (gapiGamesClient == null) {
				gamesController = new GamesController();
				gapiGamesClient = new GoogleApiClient.Builder(activity)
						.addApi(Games.API)
						.addScope(Games.SCOPE_GAMES)
						.enableAutoManage(activity, GamesController.GOOGLE_API_ID, null)
						.addConnectionCallbacks(gamesController)
						.setViewForPopups(v)
						.build();

				gamesController.setClient(gapiGamesClient).setUI(v);
			}
			gapiGamesClient.connect();
			apiGames = true;
			return new Success<>();
		}
		return new Success<>("Play services are not available");
	}

	public static void reconnect() {
		if (gapiGamesClient != null)
			gapiGamesClient.connect();
	}

	public static void destroyGamesClient() {
		if (!isLogged()) return;
		gamesController.logout();
		Games.signOut(gapiGamesClient);
		gapiGamesClient.disconnect();
		Setting.getPreferences().edit().putBoolean(Setting.REGISTERED_USER, false).apply();
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
