package com.adsamcik.signalcollector.services

import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.signin.Signin
import com.google.firebase.iid.FirebaseInstanceId
import com.google.firebase.iid.FirebaseInstanceIdService
import kotlinx.coroutines.experimental.launch

class InstanceIDListenerService : FirebaseInstanceIdService() {

    /**
     * Called if InstanceID token is updated. This may occur if the security of
     * the previous token had been compromised. Note that this is also called
     * when the InstanceID token is initially generated, so this is where
     * you retrieve the token.
     */
    override fun onTokenRefresh() {
        val refreshedToken = FirebaseInstanceId.getInstance().token!!
        val context = this
        launch {
            val user = Signin.getUserAsync(context)
            if (user != null)
                Network.register(context, user.token, refreshedToken)
        }
    }

}
