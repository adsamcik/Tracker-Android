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
    implementation(project(":core:common"))
    implementation(project(":data:preferences"))
    implementation(project(":tracker:api"))
    implementation(project(":sensor:activity-api"))
    implementation(project(":stats:api"))

    // Kotlin & Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.test)

    // Compose testing
    implementation(libs.compose.ui.test.junit4)

    // The main artifact contains a JVM-only JUnit 5 extension, but its Android test helpers
    // are also consumed by instrumented tests. Keep Jupiter off the published runtime variant
    // so its runner jars are not packaged into Android test APKs.
    compileOnly(platform(libs.junit5.bom))
    compileOnly(libs.junit5.jupiter.api)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.junit5.jupiter.params)
    testRuntimeOnly(libs.junit5.jupiter.engine)
    // Vintage engine for JUnit 4 compatibility during migration
    testRuntimeOnly(libs.junit5.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)

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
