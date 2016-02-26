package com.adsamcik.signalcollector.Play;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.adsamcik.signalcollector.BaseGameUtils;
import com.adsamcik.signalcollector.Extensions;
import com.adsamcik.signalcollector.Network;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.games.Games;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import cz.msebera.android.httpclient.Header;

public class GamesController implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    static final int REQUEST_LEADERBOARD = 5989;
    static final int REQUEST_ACHIEVEMENTS = 8955;
    private static final int RC_SIGN_IN = 9001;
    GoogleApiClient client;
    final Context context;
    final Activity activity;
    Button button;
    private boolean mResolvingConnectionFailure = false;

    public GamesController(Context context, Activity a) {
        this.context = context;
        this.activity = a;
    }

    public GamesController setClient(GoogleApiClient client) {
        this.client = client;
        return this;
    }

    public GamesController setUI(View v) {
        button = (Button) v.findViewById(R.id.textView_playLoginText);
        updateUI(client.isConnected());
        return this;
    }

    public void logout() {
        updateUI(false);
        AsyncHttpClient hac = new AsyncHttpClient();
        RequestParams rp = new RequestParams();
        rp.add("register", "false");
        rp.add("userID", getUserID());
        hac.post(Network.URL_TOKEN_REGISTRATION, rp, new AsyncHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
                Setting.sharedPreferences.edit().putBoolean(Setting.REGISTERED_USER, true).apply();
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {

            }
        });
    }

    void updateUI(boolean connected) {
        if (connected) {
            if (button != null) {
                button.setText(R.string.settings_playGamesLogout);
                button.setBackgroundTintList(new ColorStateList(
                        new int[][]{
                                new int[]{android.R.attr.state_pressed},
                                new int[]{android.R.attr.state_enabled}
                        },
                        new int[]{
                                Color.rgb(165, 50, 50),
                                Color.rgb(165, 11, 11)
                        }
                ));

            }
        } else {
            if (button != null) {
                button.setText(R.string.settings_playGamesLogin);
                button.setBackgroundTintList(new ColorStateList(
                        new int[][]{
                                new int[]{android.R.attr.state_pressed},
                                new int[]{android.R.attr.state_enabled}
                        },
                        new int[]{
                                Color.rgb(100, 153, 100),
                                Color.rgb(68, 153, 68)
                        }
                ));
            }
        }
    }

    public void showAchievements() {
        if (client != null)
            activity.startActivityForResult(Games.Achievements.getAchievementsIntent(client), REQUEST_ACHIEVEMENTS);
    }

    public void showLeaderboard(String id) {
        if (client != null)
            activity.startActivityForResult(Games.Leaderboards.getLeaderboardIntent(client, id), REQUEST_LEADERBOARD);
    }

    public String getUserID() {
        return Games.Players.getCurrentPlayerId(client);
    }

    public String getUserName() {
        return Games.Players.getCurrentPlayer(client).getDisplayName();
    }

    @Override
    public void onConnected(Bundle bundle) {
        PlayController.apiGames = true;
        updateUI(true);
        if (!Setting.sharedPreferences.getBoolean(Setting.REGISTERED_USER, false)) {
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
                    Setting.sharedPreferences.edit().putBoolean(Setting.REGISTERED_USER,true).apply();
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

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        if (mResolvingConnectionFailure) {
            // Already resolving
            return;
        }

        mResolvingConnectionFailure = BaseGameUtils.resolveConnectionFailure(activity, client, connectionResult, RC_SIGN_IN, "error");
    }
}
