plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    compileSdk = Android.COMPILE_VERSION
    buildToolsVersion = Android.BUILD_TOOLS_VERSION

    defaultConfig {
        minSdk = Android.MIN_VERSION
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = Android.javaTarget
        targetCompatibility = Android.javaTarget
    }

    kotlin {
        jvmToolchain(Android.JAVA_VERSION)
    }

    buildTypes {
        getByName("debug") {}
        create("release_nominify") { isMinifyEnabled = false }
        getByName("release") { isMinifyEnabled = false }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    namespace = "com.adsamcik.tracker.activity"
}

dependencies {
    api(project(":core:base"))
    api(project(":stats:engine"))

    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.androidx.core.ktx)
    implementation(libs.google.play.services.location)
}
