plugins {
    id("tracker.android.library")
    id("tracker.android.compose")
}

android {
    namespace = "com.adsamcik.tracker.testing"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("debug") {
            // Testing module
        }
        getByName("release") {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    // Project dependencies for domain models
    implementation(project(":core:base"))
    implementation(project(":core:model"))
    implementation(project(":data:preferences"))
    implementation(project(":tracker:api"))
    implementation(project(":sensor:activity-api"))
    implementation(project(":stats:api"))
    implementation(project(":stats:engine"))

    // Kotlin & Coroutines
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.test)

    // Compose testing
    implementation(libs.compose.ui.test.junit4)

    // JUnit 5 (for modern unit tests)
    implementation(platform(libs.junit5.bom))
    implementation(libs.junit5.jupiter)
    implementation(libs.junit5.jupiter.params)
    runtimeOnly(libs.junit5.jupiter.engine)
    // Vintage engine for JUnit 4 compatibility during migration
    runtimeOnly(libs.junit5.vintage.engine)

    // AndroidX Test (JUnit 4 for instrumented tests)
    implementation(libs.junit4)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.uiautomator)
    implementation(libs.espresso)
    implementation(libs.espresso.intents)

    // Mocking & Assertions
    implementation(libs.mockk)
    implementation(libs.turbine)
    implementation(libs.kotest.assertions.core)
    implementation(libs.kotlin.test)

    // Robolectric for tests that use android.location.Location
    testImplementation(libs.robolectric)
}
