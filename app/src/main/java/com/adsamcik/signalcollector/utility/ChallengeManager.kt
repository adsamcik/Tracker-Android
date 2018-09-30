package com.adsamcik.signalcollector.utility

import android.content.Context
import com.adsamcik.signalcollector.data.Challenge
import com.adsamcik.signalcollector.file.CacheStore
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.network.NetworkLoader
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Constants.DAY_IN_MINUTES
import com.squareup.moshi.Moshi
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.launch
import kotlin.coroutines.experimental.suspendCoroutine

/**
 * Singleton class that manages saving and loading of challenges from cache storage or network
 */
object ChallengeManager {
    private val moshi by lazy { Moshi.Builder().add(ChallengeDeserializer()).build() }

    fun getAdapter() = moshi.adapter(Array<Challenge>::class.java)!!

    /**
     * Loads challenges from cache storage if found and new enough
     * or from server otherwise. Uses Kotlin Coroutines.
     *
     * @param ctx Context
     * @param force If true, always downloads data from
     * @return Source of data and List of challenges. List is null if error occurred
     */
    suspend fun getChallenges(ctx: Context, force: Boolean): Pair<NetworkLoader.Source, Array<Challenge>?> = suspendCoroutine { cont ->
        val context = ctx.applicationContext
        GlobalScope.launch(Dispatchers.Default, CoroutineStart.DEFAULT, null, {
            val user = Signin.getUserAsync(context)
            if (user != null) {
                val str = NetworkLoader.requestStringSignedAsync(Network.URL_CHALLENGES_LIST, user.token, if (force) 0 else DAY_IN_MINUTES, context, Preferences.PREF_ACTIVE_CHALLENGE_LIST)
                if (str.first.success) {
                    val challengeArray = getAdapter().fromJson(str.second!!)

                    if(challengeArray != null) {
                        for (challenge in challengeArray)
                            challenge.generateTexts(context)
                        cont.resume(Pair(str.first, challengeArray))
                        return@launch
                    }
                } else {
                    cont.resume(Pair(str.first, null))
                }
            }
        })
    }

    /**
     * Loads challenges from cache storage if found and new enough
     * or from server otherwise. Uses higher order function as callback.
     *
     * @param ctx Context
     * @param force If true, always downloads data from
     * @return Source of data and List of challenges. List is null if error occurred
     */
    fun getChallenges(ctx: Context, force: Boolean, callback: (NetworkLoader.Source, Array<Challenge>?) -> Unit) {
        val context = ctx.applicationContext
        GlobalScope.launch(Dispatchers.Default, CoroutineStart.DEFAULT, null, {
            val user = Signin.getUserAsync(context)
            if (user != null) {
                NetworkLoader.requestStringSigned(Network.URL_CHALLENGES_LIST, user.token, if (force) 0 else DAY_IN_MINUTES, context, Preferences.PREF_ACTIVE_CHALLENGE_LIST) { source, jsonChallenges ->
                    if (!source.success || jsonChallenges == null)
                        callback.invoke(source, null)
                    else {
                        val challengeArray = getAdapter().fromJson(jsonChallenges)!!
                        challengeArray.forEach { it.generateTexts(context) }
                        callback.invoke(source, challengeArray)
                    }
                }
            }
        })
    }

    /***
     * Stores challenges at CacheStore
     */
    @Deprecated("Will be removed in future once proper localization on load is added")
    fun saveChallenges(context: Context, challenges: Array<Challenge>) {
        CacheStore.saveString(context, Preferences.PREF_ACTIVE_CHALLENGE_LIST, getAdapter().toJson(challenges), false)
    }
}
