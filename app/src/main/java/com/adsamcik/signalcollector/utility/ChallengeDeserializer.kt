package com.adsamcik.signalcollector.utility

import com.adsamcik.signalcollector.data.Challenge
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson
import java.io.IOException

/**
 * JsonDeserializer class that is needed to properly deserialize Challenge objects
 */
class ChallengeDeserializer {
    @FromJson fun fromJson(reader: JsonReader): Challenge? {
        var challengeType: Challenge.ChallengeType? = null
        var title: String? = null
        var descVars: Array<String>? = null
        var progress: Float? = null
        var difficulty: Int? = null

        reader.isLenient = true

        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (name) {
                "descVars" -> descVars = readStringArray(reader).toTypedArray()
                "progress" -> progress = reader.nextDouble().toFloat()
                "type" -> challengeType = Challenge.ChallengeType.valueOf(reader.nextString())
                "difficulty" -> difficulty = reader.nextInt()
                "title" -> title = reader.nextString()
            }
        }
        reader.endObject()

        if (challengeType == null || title == null || descVars == null || progress == null || difficulty == null)
            return null

        return Challenge(
                challengeType,
                title,
                descVars,
                progress,
                difficulty)
    }

    @Throws(IOException::class)
    private fun readStringArray(reader: JsonReader): List<String> {
        val strings = ArrayList<String>()

        reader.beginArray()
        while (reader.hasNext())
            strings.add(reader.nextString())

        reader.endArray()
        return strings
    }

    @ToJson fun toJson(writer: JsonWriter?, value: Challenge?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}
