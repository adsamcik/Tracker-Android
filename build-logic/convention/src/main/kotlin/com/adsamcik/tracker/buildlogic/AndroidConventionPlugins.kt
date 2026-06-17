package com.adsamcik.tracker.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal object TrackerAndroidConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 26
    const val BUILD_TOOLS = "37.0.0-rc2"
    const val JAVA_VERSION = 17
}

internal fun Project.libs(): VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.library(alias: String) = libs().findLibrary(alias).get()

internal fun Project.addLibrary(configurationName: String, alias: String) {
    dependencies.add(configurationName, library(alias))
}

internal fun Project.addPlatformLibrary(configurationName: String, alias: String) {
    dependencies.add(configurationName, dependencies.platform(library(alias)))
}

internal fun Project.configureKotlinAndroid() {
    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(TrackerAndroidConfig.JAVA_VERSION)
    }
}

internal fun Project.configureJUnitPlatform() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

internal fun LibraryExtension.configureTrackerLibrary() {
    compileSdk = TrackerAndroidConfig.COMPILE_SDK
    buildToolsVersion = TrackerAndroidConfig.BUILD_TOOLS

    defaultConfig {
        minSdk = TrackerAndroidConfig.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
        targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("debug") {
        }
        maybeCreate("release_nominify").apply {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

internal fun ApplicationExtension.configureTrackerApplication() {
    compileSdk = TrackerAndroidConfig.COMPILE_SDK
    buildToolsVersion = TrackerAndroidConfig.BUILD_TOOLS

    defaultConfig {
        minSdk = TrackerAndroidConfig.MIN_SDK
        targetSdk = TrackerAndroidConfig.TARGET_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
        targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("debug") {
        }
        maybeCreate("release_nominify").apply {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<LibraryExtension> {
            configureTrackerLibrary()
        }
        configureKotlinAndroid()
        configureJUnitPlatform()
    }
}

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<ApplicationExtension> {
            configureTrackerApplication()
        }
        configureKotlinAndroid()
        configureJUnitPlatform()
    }
}
