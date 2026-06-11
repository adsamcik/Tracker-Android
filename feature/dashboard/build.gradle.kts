plugins {
    id("tracker.android.library")
    id("tracker.android.compose")
    id("tracker.android.hilt")
    id("tracker.android.test")
}

android {
    namespace = "com.adsamcik.tracker.dashboard"

    buildTypes {
        create("dev") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("debug", "release")
        }
    }
}

dependencies {
    // Internal modules
    implementation(project(":feature:dashboard:api"))
    implementation(project(":feature:game:api"))
    implementation(project(":feature:map:api"))
    implementation(project(":feature:statistics:api"))
    implementation(project(":core:base"))
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
    implementation(project(":core:logging"))
    implementation(project(":tracker:api"))
    implementation(project(":stats:api"))
    implementation(project(":stats:data"))
    implementation(project(":domain:points"))

    // Core
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.navigation.compose)

    // Compose
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    // MapLibre for tracking map hero
    implementation(libs.maplibre.compose)

    // Glass effects
    implementation(libs.haze)

    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
