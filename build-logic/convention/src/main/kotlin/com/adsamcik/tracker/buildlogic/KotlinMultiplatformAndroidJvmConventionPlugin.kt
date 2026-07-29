package com.adsamcik.tracker.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Shared Android/JVM target setup for the project's multiplatform libraries.
 *
 * Modules remain responsible for their Android namespace, optional test
 * compilations, and source-set dependencies.
 */
class KotlinMultiplatformAndroidJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")

        extensions.configure<KotlinMultiplatformExtension> {
            targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
                compileSdk = TrackerAndroidConfig.COMPILE_SDK
                minSdk = TrackerAndroidConfig.MIN_SDK
            }
            jvm()
            jvmToolchain(TrackerAndroidConfig.JAVA_VERSION)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
