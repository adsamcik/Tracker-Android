package com.adsamcik.signalcollector.services

import androidx.core.content.edit
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Preferences
import com.google.firebase.messaging.FirebaseMessagingService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class InstanceIDListenerService : FirebaseMessagingService() {

    /**
     * Called if InstanceID token is updated. This may occur if the security of
     * the previous token had been compromised. Note that this is also called
     * when the InstanceID token is initially generated, so this is where
     * you retrieve the token.
     */
    override fun onNewToken(token: String) {
        val context = this
        Preferences.getPref(context).edit {
            putBoolean(Preferences.PREF_SENT_TOKEN_TO_SERVER, false)
        }
        GlobalScope.launch(Dispatchers.Default, CoroutineStart.DEFAULT) {
            val user = Signin.getUserAsync(context)
            if (user != null)
                Network.register(context, user.token, token)
        }
    }

}
