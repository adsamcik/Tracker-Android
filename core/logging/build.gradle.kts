plugins {
    id("tracker.android.library")
    id("tracker.android.room")
    id("tracker.android.test")
    id("tracker.android.instrumented-test")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.adsamcik.tracker.logger"

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":core:logging-api"))
    implementation(project(":data:preferences"))
    implementation(project(":core:sqlite-runtime"))
    implementation(libs.androidx.documentfile)
    androidTestImplementation(libs.androidx.room.testing)

    // Core
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)
}
