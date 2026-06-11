plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    compileSdk = Android.COMPILE_VERSION
    buildToolsVersion = Android.BUILD_TOOLS_VERSION

    defaultConfig {
        minSdk = Android.MIN_VERSION
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
        create("dev") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("debug", "release")
        }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = false
    }

    namespace = "com.adsamcik.tracker.feature.statistics.api"
}

dependencies {
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.navigation.compose)
}
