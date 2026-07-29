plugins {
    id("tracker.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.adsamcik.tracker.feature.statistics.api"
}

dependencies {
    implementation(libs.navigation.compose)
}
