package com.adsamcik.signalcollector.utility;

import com.adsamcik.signalcollector.data.Challenge;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;
import java.util.Random;

public class ChallengeDeserializer implements JsonDeserializer<Challenge> {
	@Override
	public Challenge deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
		JsonObject jobject = json.getAsJsonObject();
		JsonArray jDescVars = jobject.get("descVars").getAsJsonArray();
		String[] descVars = new String[jDescVars.size()];

		for (int i = 0; i < descVars.length; i++)
			descVars[i] = jDescVars.get(i).getAsString();

		JsonElement progressElement  = jobject.get("progress");
		float progress;
		if(progressElement == null)
			progress = 0;
		else
			progress = progressElement.getAsFloat();

		return new Challenge(
				Challenge.ChallengeType.valueOf(jobject.get("type").getAsString()),
				jobject.get("title").getAsString(),
				descVars,
				progress,
				jobject.get("difficulty").getAsInt());
	}
}
