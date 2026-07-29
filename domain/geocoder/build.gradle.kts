plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.test")
}

android {
    namespace = "com.adsamcik.tracker.geocoder"

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
    // OSM grid index + polyline codec are reused for street-level reverse geocoding.
    implementation(project(":domain:osm"))
    implementation(project(":core:base"))
    implementation(project(":core:common"))
	implementation(project(":core:model"))
    implementation(project(":core:logging-api"))

    // Core
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    // Room (read existing osm_* tables via DAO injection)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // DI annotations
    implementation(libs.javax.inject)

    testImplementation(libs.androidx.room.testing)
}
