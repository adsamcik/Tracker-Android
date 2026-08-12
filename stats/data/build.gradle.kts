plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.test")
}

android {
    namespace = "com.adsamcik.tracker.stats.data"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    api(project(":stats:api"))
    implementation(project(":core:base"))
    implementation(project(":core:diagnostics"))
    implementation(project(":data:preferences"))
    // Core
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.paging.runtime)

    // Arrow
    implementation(libs.arrow.core)

    // Hilt worker bridge
    implementation(libs.hilt.work)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // DI annotations
    implementation(libs.javax.inject)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    testImplementation(libs.androidx.work.testing)
    testImplementation(project(":core:testing"))
    // Contract tests assert AchievementWorker (this module) and AchievementProcessor
    // (in :stats-engine) produce identical unlock decisions for the same input: the
    // R1 round-6 regression seam. Test-only edge; no production coupling.
    testImplementation(project(":stats:engine"))
}
