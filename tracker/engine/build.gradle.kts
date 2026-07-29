plugins {
    id("tracker.android.library")
    id("tracker.android.room")
    id("tracker.android.hilt")
    id("tracker.android.test")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.adsamcik.tracker.tracker.engine"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        // Enable BuildConfig generation for debug flag checking
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            // Library-level R8 is intentionally DISABLED. The app module runs R8 over
            // the entire classpath (app + every library) with full visibility, which
            // shrinks more aggressively than per-library R8 ever could and avoids
            // the consumer-rules.pro coverage trap (where a library's R8 strips an
            // "internal" class that the app actually references via Hilt/DI/reflection).
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
        baseline = file("lint-baseline.xml")
    }
}

tasks.withType<Test>().configureEach {
    // The default 512m worker heap is too small for this module's large Robolectric-backed
    // pipeline/persistence suite and causes intermittent "Java heap space" failures. Forking a
    // fresh worker periodically also reclaims Robolectric's per-test SDK/shadow state instead of
    // letting it accumulate across the whole suite in one JVM. forkEvery counts test *classes*.
    maxHeapSize = "6g"
    setForkEvery(5)
}

dependencies {
    api(project(":tracker:api"))
    implementation(project(":tracker:control"))
    implementation(project(":core:base"))
    implementation(project(":core:model"))
    implementation(project(":core:logging-api"))
    implementation(project(":sensor:activity-api"))
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
    implementation(project(":core:logging"))
    implementation(project(":stats:api"))
    implementation(project(":stats:engine"))

    // Core
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.location.altitude)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)
    implementation(libs.google.play.services.location)

    implementation(libs.hilt.work)
    // DataStore (proto for typed settings, preferences for migration compatibility)
    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.protobuf.java)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    androidTestImplementation(libs.androidx.work.testing)

    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(project(":core:testing"))
}

configureProtobuf()
