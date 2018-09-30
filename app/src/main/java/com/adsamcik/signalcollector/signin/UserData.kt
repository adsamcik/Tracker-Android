package com.adsamcik.signalcollector.signin

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UserData(
        /**
         * Number of Wireless Points the user owns.
         * It might not reflect the actual amount, because server does not instantly update this amount on the clientAuth.
         */
        var wirelessPoints: Long,
        /**
         * Server information about availability of services etc.
         */
        var networkInfo: NetworkInfo,
        /**
         * Server preferences.
         */
        var networkPreferences: NetworkPreferences)