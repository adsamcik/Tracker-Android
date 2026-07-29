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
    implementation(project(":core:common"))
    api(project(":core:model"))
    api(project(":stats:api"))

    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
}
