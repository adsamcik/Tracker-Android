package com.adsamcik.tracker.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryExtension> {
                buildFeatures.compose = true
            }
        }
        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationExtension> {
                buildFeatures.compose = true
            }
        }

        dependencies {
            add("implementation", platform(library("compose-bom")))
            add("implementation", library("compose-runtime"))
            add("implementation", library("compose-material3"))
            add("implementation", library("compose-foundation"))
            add("implementation", library("compose-ui"))
            add("implementation", library("compose-material-icons-extended"))
        }
    }
}

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        addLibrary("implementation", "hilt-android")
        addLibrary("ksp", "hilt-compiler")
        addLibrary("ksp", "androidx-hilt-compiler")
    }
}

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")

        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryExtension> {
                sourceSets.maybeCreate("androidTest").assets.directories.add(
                    layout.projectDirectory.dir("schemas").asFile.absolutePath,
                )
            }
        }
        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationExtension> {
                sourceSets.maybeCreate("androidTest").assets.directories.add(
                    layout.projectDirectory.dir("schemas").asFile.absolutePath,
                )
            }
        }

        extensions.configure<KspExtension> {
            arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.absolutePath)
            arg("room.incremental", "true")
            arg("room.generateKotlin", "true")
        }

        addLibrary("implementation", "androidx-room-runtime")
        addLibrary("implementation", "androidx-room-ktx")
        addLibrary("ksp", "androidx-room-compiler")
    }
}

class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        addPlatformLibrary("testImplementation", "junit5-bom")
        addLibrary("testImplementation", "junit5-jupiter")
        addLibrary("testImplementation", "junit5-jupiter-params")
        addLibrary("testRuntimeOnly", "junit5-jupiter-engine")
        addLibrary("testRuntimeOnly", "junit5-vintage-engine")
        addLibrary("testRuntimeOnly", "junit-platform-launcher")
        addLibrary("testImplementation", "junit4")
        addLibrary("testImplementation", "kotlin-test")
        addLibrary("testImplementation", "mockk")
        addLibrary("testImplementation", "kotlinx-coroutines-test")
        addLibrary("testImplementation", "robolectric")
        addLibrary("testImplementation", "androidx-test-core")
        addLibrary("testImplementation", "arch-core-testing")
        addLibrary("testImplementation", "kotest-assertions-core")
    }
}

class AndroidInstrumentedTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        addLibrary("androidTestImplementation", "junit4")
        addLibrary("androidTestImplementation", "androidx-test-runner")
        addLibrary("androidTestImplementation", "uiautomator")
        addLibrary("androidTestImplementation", "androidx-test-ext-junit")
        addLibrary("androidTestImplementation", "androidx-test-rules")
        addLibrary("androidTestImplementation", "arch-core-testing")
        addLibrary("androidTestImplementation", "espresso")
        addLibrary("androidTestImplementation", "mockk-android")
        addLibrary("androidTestImplementation", "kotlinx-coroutines-test")
    }
}
