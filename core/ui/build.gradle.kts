plugins {
    id("tracker.android.library")
    id("tracker.android.compose")
    id("tracker.android.test")
}

android {
    namespace = "com.adsamcik.tracker.shared.utils"

    sourceSets {
        this.maybeCreate("androidTest").assets.srcDirs(files("$projectDir/schemas"))
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation(project(":core:base"))
    implementation(project(":core:logging-api"))
    implementation(project(":data:preferences"))

    // Core
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)
    implementation(libs.google.play.services.location)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    androidTestImplementation(libs.androidx.work.testing)

    // Compose
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.compose.animation)
    implementation(libs.material.kolor)
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
