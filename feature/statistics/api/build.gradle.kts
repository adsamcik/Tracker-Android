plugins {
    id("tracker.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.adsamcik.tracker.feature.statistics.api"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    api(project(":stats:api"))
    implementation(libs.navigation.compose)
}
