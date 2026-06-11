plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.test")
}

android {
    namespace = "com.adsamcik.tracker.network"
}

dependencies {
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core:logging-api"))
    // :sbase exposes DispatchersProvider so DefaultNetworkGateway can swap
    // dispatchers in tests via TestDispatchersProvider instead of hardcoding
    // Dispatchers.IO. Also satisfies the fitness rule that bans whole-object
    // Dispatchers imports outside :sbase.
    implementation(project(":core:base"))
    // OkHttp is `api` because [OkHttpBackedGateway.okHttpCallFactory] returns
    // `okhttp3.Call.Factory`; that type must be visible to consumers like
    // `:map` (for `HttpRequestUtil.setOkHttpClient`) and any future module that
    // wants to integrate at the OkHttp level. The convention (enforced by code
    // review, future lint rule) is that NO module other than `:network` may
    // construct an `OkHttpClient` directly; get the call factory from the
    // gateway instead so requests share the kill-switch + allowlist + rate-limit
    // interceptor chain.
    api(libs.okhttp)

    // Network-specific test helpers
    testImplementation(libs.okhttp.mockwebserver)
    // okhttp-tls provides HeldCertificate / HandshakeCertificates so MockWebServer
    // can serve HTTPS; required for end-to-end testing through the gateway's
    // HttpsOnlyInterceptor without disabling the HTTPS guard.
    testImplementation(libs.okhttp.tls)
}
