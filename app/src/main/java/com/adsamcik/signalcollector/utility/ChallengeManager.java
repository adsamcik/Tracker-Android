package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.data.Challenge;
import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.interfaces.IStateValueCallback;
import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.network.NetworkLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class ChallengeManager {
	public static void getChallenges(@NonNull Context ctx, boolean force, @NonNull final IStateValueCallback<NetworkLoader.Source, Challenge[]> callback) {
		Context context = ctx.getApplicationContext();
		NetworkLoader.requestStringSigned(Network.URL_CHALLENGES_LIST, force ? 0 : Assist.DAY_IN_MINUTES, context, Preferences.PREF_ACTIVE_CHALLENGE_LIST, (source, jsonChallenges) -> {
			if (!source.isSuccess())
				callback.callback(source, null);
			else {
				GsonBuilder gsonBuilder = new GsonBuilder();
				gsonBuilder.registerTypeAdapter(Challenge.class, new ChallengeDeserializer());
				Gson gson = gsonBuilder.create();
				Challenge[] challengeArray = gson.fromJson(jsonChallenges, Challenge[].class);
				for (Challenge challenge : challengeArray)
					challenge.generateTexts(context);
				callback.callback(source, challengeArray);
			}
		});
	}

	public static void saveChallenges(@NonNull Context context, @NonNull Challenge[] challenges) {
		DataStore.saveString(context, Preferences.PREF_ACTIVE_CHALLENGE_LIST, new Gson().toJson(challenges), false);
	}
}
