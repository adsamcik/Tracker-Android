plugins {
    id("tracker.android.library")
    id("tracker.android.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.adsamcik.tracker.feature.map.api"
}

dependencies {
    implementation(libs.navigation.compose)
    api(libs.compose.runtime)
    api(libs.compose.ui)
}
