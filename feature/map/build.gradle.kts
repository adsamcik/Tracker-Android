plugins {
    id("tracker.android.library")
    id("tracker.android.compose")
    id("tracker.android.hilt")
    id("tracker.android.test")
    id("tracker.android.instrumented-test")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.adsamcik.tracker.map"

    lint {
        baseline = file("lint-baseline.xml")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.ExperimentalUnsignedTypes")
    }
}

dependencies {
    // Removed :app dependency as part of converting to a library module
    implementation(project(":feature:map:api"))
    implementation(project(":core:base"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":tracker:api"))
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
    implementation(project(":stats:api"))
    implementation(project(":domain:geocoder"))
    implementation(project(":core:logging"))
    implementation(project(":core:network"))

    // Core
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)
    implementation(libs.google.play.services.location)

    // Compose
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.foundation.layout)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.constraintlayout.compose)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.kotlinx.collections.immutable)
    // MapLibre Compose (replaces Google Maps)
    implementation(libs.maplibre.compose)

    implementation(libs.hilt.navigation.compose)
    implementation(libs.spotlight)

    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.activity.compose)
    testImplementation(kotlin("reflect"))
    testImplementation(libs.turbine)
    testImplementation(project(":core:testing"))
    testImplementation(project(":domain:osm"))

    androidTestImplementation(libs.mockito.android)
    androidTestImplementation(libs.mockito.kotlin)
    androidTestImplementation(project(":core:testing"))
}

// Disable release tests for this UI-heavy module.
tasks.withType<Test>().configureEach {
    if (name.contains("ReleaseUnitTest")) {
        enabled = false
    }
}
