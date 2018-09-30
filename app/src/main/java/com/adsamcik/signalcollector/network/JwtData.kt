package com.adsamcik.signalcollector.network

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class JwtData(val token: String)