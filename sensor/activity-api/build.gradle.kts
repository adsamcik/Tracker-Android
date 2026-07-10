plugins {
    id("tracker.android.library")
}

android {
    namespace = "com.adsamcik.tracker.activity"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":stats:api"))

    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.androidx.core.ktx)
}
