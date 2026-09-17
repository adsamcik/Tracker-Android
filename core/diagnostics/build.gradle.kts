plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.room")
    id("tracker.android.test")
}

android {
    namespace = "com.adsamcik.tracker.diagnostics"
}

dependencies {
    // Tracebox remains the crash/general diagnostics backend and an implementation detail. The
    // bounded tracking-operational Room store is also owned entirely by this module.
    implementation(libs.tracebox)
}
