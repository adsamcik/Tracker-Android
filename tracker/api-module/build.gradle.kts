plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.adsamcik.tracker.tracker"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core:base"))
    api(project(":stats:api"))
    api(project(":stats:engine"))

    implementation(project(":core:logging"))
    implementation(project(":data:preferences"))
    implementation(project(":sensor:activity-api"))

    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
