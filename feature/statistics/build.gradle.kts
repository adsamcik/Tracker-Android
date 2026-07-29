plugins {
    id("tracker.android.library")
    id("tracker.android.compose")
    id("tracker.android.hilt")
    id("tracker.android.test")
    id("tracker.android.instrumented-test")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.adsamcik.tracker.statistics"

    lint {
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    // Removed :app dependency as part of converting to a library module
    implementation(project(":feature:statistics:api"))
    implementation(project(":feature:dashboard:api"))
    implementation(project(":feature:map:api"))
    implementation(project(":core:base"))
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
    implementation(project(":core:logging"))
    implementation(project(":feature:import-export"))
    implementation(project(":feature:map"))

    // Stats architecture
    implementation(project(":stats:api"))

    // Arrow
    implementation(libs.arrow.core)
    implementation(libs.suncalc)

    // Core
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)
    implementation(libs.google.play.services.location)
    // Draggable overlay removed
    // JSON
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    // Paging
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    // Compose (UI migration)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.turbine)
    debugImplementation(libs.compose.ui.test.manifest)

    // Instrumented Tests
    androidTestImplementation(libs.espresso.intents)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.simplify)

    // MapLibre Compose for trip route visualization
    implementation(libs.maplibre.compose)

    implementation(libs.hilt.navigation.compose)
}
