plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.test")
}

android {
    namespace = "com.adsamcik.tracker.diagnostics"
}

dependencies {
    // Tracebox remains the sole local backend, but it is an implementation detail. Public tracking
    // diagnostic signatures are the payload-free types owned by this module.
    implementation(libs.tracebox)
}
