plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.room")
    id("tracker.android.test")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.adsamcik.tracker.points"
}

dependencies {
    implementation(project(":core:base"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:logging"))

    // Stats architecture
    implementation(project(":stats:api"))

    // Core
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    androidTestImplementation(libs.androidx.work.testing)

    // Hilt worker bridge
    implementation(libs.hilt.work)
}
