plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.test")
}

android {
    namespace = "com.adsamcik.tracker.osm"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    api(project(":stats:api"))
    implementation(project(":core:base"))
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
    implementation(project(":core:logging-api"))

    // Core
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)

    // Room (read existing osm_* tables via DAO injection)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // WorkManager + Hilt worker bridge
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)

    // DI annotations
    implementation(libs.javax.inject)

    // OSM PBF parsing (offline; never touched by network).
    implementation(libs.osmpbf)

    // Module-specific test helpers
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.room.testing)
}
