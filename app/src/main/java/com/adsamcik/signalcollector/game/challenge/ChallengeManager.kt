package com.adsamcik.signalcollector.game.challenge

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.Challenge
import com.squareup.moshi.Moshi

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
    fun getChallenges(ctx: Context, force: Boolean): Array<Challenge> {
        throw NotImplementedError()
        /*val context = ctx.applicationContext
        GlobalScope.launch(Dispatchers.Default, CoroutineStart.DEFAULT) {
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
        }*/
    }
}
