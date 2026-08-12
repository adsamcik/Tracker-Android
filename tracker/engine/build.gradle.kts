plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.test")
    id("tracker.android.protobuf")
    alias(libs.plugins.kotlin.parcelize)
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
    implementation(project(":core:common"))
    implementation(project(":core:base"))
    implementation(project(":core:model"))
    implementation(project(":core:diagnostics"))
    implementation(project(":sensor:activity-api"))
    implementation(project(":data:preferences"))
    implementation(project(":stats:api"))
    implementation(project(":stats:engine"))

    // Core
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.androidx.work.testing)
    testImplementation(project(":core:testing"))
    testImplementation(libs.turbine)
}
