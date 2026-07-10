plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.test")
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
    api(project(":core:model"))
    api(project(":stats:api"))

    implementation(libs.kotlin.stdlib.jdk8)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
}
