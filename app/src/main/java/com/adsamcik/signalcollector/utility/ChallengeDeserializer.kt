package com.adsamcik.signalcollector.utility

import com.adsamcik.signalcollector.data.Challenge
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type

class ChallengeDeserializer : JsonDeserializer<Challenge> {
    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Challenge {
        val jobject = json.asJsonObject
        val jDescVars = jobject.get("descVars").asJsonArray
        val descVars = Array(jDescVars.size()) { jDescVars[it].asString }

        val progressElement = jobject.get("progress")
        val progress: Float
        progress = progressElement?.asFloat ?: 0f

        return Challenge(
                Challenge.ChallengeType.valueOf(jobject.get("type").asString),
                jobject.get("title").asString,
                descVars,
                progress,
                jobject.get("difficulty").asInt)
    }
}
