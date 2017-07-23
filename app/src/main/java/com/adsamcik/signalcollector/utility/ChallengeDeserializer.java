package com.adsamcik.signalcollector.utility;

import com.adsamcik.signalcollector.data.Challenge;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

public class ChallengeDeserializer implements JsonDeserializer<Challenge> {
	@Override
	public Challenge deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
		JsonObject jobject = json.getAsJsonObject();
		JsonArray jDescVars = jobject.get("descVars").getAsJsonArray();
		String[] descVars = new String[jDescVars.size()];

		for (int i = 0; i < descVars.length; i++)
			descVars[i] = jDescVars.get(i).getAsString();

		return new Challenge(
				Challenge.ChallengeType.values()[jobject.get("id").getAsInt()],
				jobject.get("title").getAsString(),
				descVars,
				jobject.get("isDone").getAsBoolean());
	}
}
