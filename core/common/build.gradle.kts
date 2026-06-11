plugins {
    id("tracker.android.library")
    id("tracker.android.compose")
    id("tracker.android.test")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.adsamcik.tracker.shared.common"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Logging contracts (the only intra-project dependency core:common needs)
    implementation(project(":core:logging-api"))

    // Core
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.androidx.documentfile)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)
    implementation(libs.google.play.services.location)

    // Compose runtime for CompositionLocal DI support (graph/di packages)
    implementation(libs.activity.compose)

    // Paging
    implementation(libs.androidx.paging.runtime)

    // DI annotations (javax.inject for @Qualifier, @Singleton, etc.)
    api(libs.javax.inject)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
}
