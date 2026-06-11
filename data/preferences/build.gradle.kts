plugins {
    id("tracker.android.library")
    id("tracker.android.room")
    id("tracker.android.test")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.adsamcik.tracker.shared.preferences"

    lint {
        baseline = file("lint-baseline.xml")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:base"))

    // Core
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)

    // DataStore proto settings (full protobuf runtime for GeneratedMessageV3)
    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.protobuf.java)

    // UI components used by preference sliders
    implementation(libs.component.slider)
}

configureProtobuf()
