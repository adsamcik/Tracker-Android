package com.adsamcik.signalcollector.extensions

import okhttp3.Request


fun Request.Builder.addBearer(token: String?): Request.Builder {
    if (token != null)
        addHeader("Authorization", "Bearer $token")
    return this
}