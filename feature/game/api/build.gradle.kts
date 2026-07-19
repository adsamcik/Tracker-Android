plugins {
    id("tracker.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.adsamcik.tracker.feature.game.api"

    buildTypes {
        create("dev") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("debug", "release")
        }
    }
}

dependencies {
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.navigation.compose)
}
