package com.adsamcik.signalcollector.utility

import android.content.Context
import com.adsamcik.signalcollector.data.Challenge
import com.adsamcik.signals.network.Network
import com.adsamcik.signals.network.NetworkLoader
import com.adsamcik.signals.signin.Signin
import com.adsamcik.signals.tracking.storage.DataStore
import com.adsamcik.signals.base.Constants.DAY_IN_MINUTES
import com.adsamcik.signals.base.Preferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.experimental.launch
import kotlin.coroutines.experimental.suspendCoroutine

object ChallengeManager {
    suspend fun getChallenges(ctx: Context, force: Boolean): Pair<NetworkLoader.Source, Array<Challenge>?> = suspendCoroutine { cont ->
        val context = ctx.applicationContext
        launch {
            val user = Signin.getUserAsync(context)
            if(user != null) {
                val str = NetworkLoader.requestStringSignedAsync(Network.URL_CHALLENGES_LIST, user.token, if (force) 0 else DAY_IN_MINUTES, context, Preferences.PREF_ACTIVE_CHALLENGE_LIST)
                if (str.first.success) {
                    val gsonBuilder = GsonBuilder()
                    gsonBuilder.registerTypeAdapter(Challenge::class.java, ChallengeDeserializer())
                    val gson = gsonBuilder.create()
                    val challengeArray = gson.fromJson(str.second!!, Array<Challenge>::class.java)
                    for (challenge in challengeArray)
                        challenge.generateTexts(context)
                    cont.resume(Pair(str.first, challengeArray))
                } else {
                    cont.resume(Pair(str.first, null))
                }
            } else
                cont.resume(Pair(NetworkLoader.Source.NO_DATA_FAILED_SIGNIN, null))
        }
    }

    fun getChallenges(ctx: Context, force: Boolean, callback: (NetworkLoader.Source, Array<Challenge>?) -> Unit) {
        val context = ctx.applicationContext
        launch {
            val user = Signin.Companion.getUserAsync(context)
            if (user != null) {
                NetworkLoader.requestStringSigned(Network.URL_CHALLENGES_LIST, user.token, if (force) 0 else DAY_IN_MINUTES, context, Preferences.PREF_ACTIVE_CHALLENGE_LIST, { source, jsonChallenges ->
                    if (!source.success)
                        callback.invoke(source, null)
                    else {
                        val gsonBuilder = GsonBuilder()
                        gsonBuilder.registerTypeAdapter(Challenge::class.java, ChallengeDeserializer())
                        val gson = gsonBuilder.create()
                        val challengeArray = gson.fromJson(jsonChallenges, Array<Challenge>::class.java)
                        challengeArray.forEach { it.generateTexts(context) }
                        callback.invoke(source, challengeArray)
                    }
                })
            }
        }
    }

    fun saveChallenges(context: Context, challenges: Array<Challenge>) {
        DataStore.saveString(context, Preferences.PREF_ACTIVE_CHALLENGE_LIST, Gson().toJson(challenges), false)
    }
}
