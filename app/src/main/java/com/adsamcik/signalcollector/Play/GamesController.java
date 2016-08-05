package com.adsamcik.signalcollector.play;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.adsamcik.signalcollector.Extensions;
import com.adsamcik.signalcollector.classes.Network;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.games.Games;
import com.google.firebase.crash.FirebaseCrash;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import java.lang.ref.WeakReference;

import cz.msebera.android.httpclient.Header;

public class GamesController implements GoogleApiClient.ConnectionCallbacks {
	private static final int REQUEST_LEADERBOARD = 5989;
	private static final int REQUEST_ACHIEVEMENTS = 8955;
	public static final int GOOGLE_API_ID = 57641;

	private GoogleApiClient client;
	private WeakReference<Button> buttonWeakReference;

	public GamesController setClient(GoogleApiClient client) {
		this.client = client;
		return this;
	}

	public GamesController setUI(View v) {
		buttonWeakReference = new WeakReference<>((Button) v.findViewById(R.id.play_loginButton));
		updateUI(client.isConnected());
		return this;
	}

	public void logout() {
		updateUI(false);
		/*String userID = getUserID();
		if (userID != null) {
			RequestParams rp = new RequestParams();
			rp.add("register", "false");
			rp.add("userID", userID);

			new AsyncHttpClient().post(Network.URL_TOKEN_REGISTRATION, rp, new AsyncHttpResponseHandler() {
				@Override
				public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
					Setting.getPreferences().edit().putBoolean(Setting.REGISTERED_USER, true).apply();
				}

				@Override
				public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {

				}
			});
		}*/
	}

	private void updateUI(boolean connected) {
		Button button = this.buttonWeakReference.get();
		if (button != null) {
			if (connected) {
				button.setText(R.string.settings_playGamesLogout);
				button.setTextColor(Color.rgb(255, 110, 110));
			} else {
				button.setText(R.string.settings_playGamesLogin);
				button.setTextColor(Color.rgb(110, 255, 110));
			}
		}
	}

	public void showAchievements(Activity activity) {
		if (client != null)
			activity.startActivityForResult(Games.Achievements.getAchievementsIntent(client), REQUEST_ACHIEVEMENTS);
	}

	public void showLeaderboard(Activity activity, String id) {
		if (client != null)
			activity.startActivityForResult(Games.Leaderboards.getLeaderboardIntent(client, id), REQUEST_LEADERBOARD);
	}

	public String getUserID() {
		return client.isConnected() ? Games.Players.getCurrentPlayerId(client) : null;
	}

	public String getUserName() {
		return Games.Players.getCurrentPlayer(client).getDisplayName();
	}

	@Override
	public void onConnected(Bundle bundle) {
		updateUI(true);
		if (!Setting.getPreferences().getBoolean(Setting.REGISTERED_USER, false)) {
			Log.d("TAG", "Registering");
			AsyncHttpClient hac = new AsyncHttpClient();
			RequestParams rp = new RequestParams();
			rp.add("userID", getUserID());
			rp.add("imei", Extensions.getImei());
			rp.add("userName", getUserName());
			rp.add("register", "true");
			hac.post(Network.URL_USER_REGISTRATION, rp, new AsyncHttpResponseHandler() {
				@Override
				public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
					Setting.getPreferences().edit().putBoolean(Setting.REGISTERED_USER, true).apply();
				}

				@Override
				public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {

				}
			});
		}
	}

	@Override
	public void onConnectionSuspended(int i) {

	}

	public void earnAchievement(String id) {
		Games.Achievements.unlock(client, id);
	}

	public void progressAchievement(String id, int value) {
		Games.Achievements.increment(client, id, value);
	}
}
