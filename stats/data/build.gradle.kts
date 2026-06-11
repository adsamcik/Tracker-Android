plugins {
    id("tracker.android.library")
    id("tracker.android.room")
    id("tracker.android.hilt")
    id("tracker.android.test")
    alias(libs.plugins.protobuf)
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
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
    implementation(project(":domain:osm"))

    // Core
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.paging.runtime)

    // Arrow
    implementation(libs.arrow.core)

    // DataStore proto (live stats)
    implementation(libs.androidx.datastore.core)
    implementation(libs.protobuf.java)

    // Hilt worker bridge
    implementation(libs.hilt.work)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // DI annotations
    implementation(libs.javax.inject)

    testImplementation(libs.androidx.work.testing)
    testImplementation(project(":core:testing"))
    // Contract tests assert AchievementWorker (this module) and AchievementProcessor
    // (in :stats-engine) produce identical unlock decisions for the same input: the
    // R1 round-6 regression seam. Test-only edge; no production coupling.
    testImplementation(project(":stats:engine"))
}

configureProtobuf()
