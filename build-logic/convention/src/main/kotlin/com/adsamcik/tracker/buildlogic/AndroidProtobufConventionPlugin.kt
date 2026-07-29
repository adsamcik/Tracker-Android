package com.adsamcik.tracker.buildlogic

import com.google.protobuf.gradle.ProtobufExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

private fun String.kspVariantName(): String? {
    if (!startsWith("ksp") || !endsWith("Kotlin")) return null

    return removePrefix("ksp")
        .removeSuffix("Kotlin")
        .takeIf(String::isNotBlank)
}

/**
 * Configures protobuf code generation for Android modules and keeps KSP
 * processors ordered behind the generated Java sources they may inspect.
 */
class AndroidProtobufConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.protobuf")

        val protobufVersion = libs().findVersion("protobuf").get().requiredVersion
        extensions.configure<ProtobufExtension> {
            protoc {
                artifact = "com.google.protobuf:protoc:$protobufVersion"
            }
            generateProtoTasks {
                all().configureEach {
                    builtins.maybeCreate("java")
                }
            }
        }

        // Protobuf registers its generated Java directory with the Android
        // variants. This lazy dependency is only needed for KSP processors that
        // inspect those generated types; no afterEvaluate hook is necessary.
        tasks.configureEach {
            val variantName = name.kspVariantName() ?: return@configureEach
            dependsOn(tasks.matching { it.name == "generate${variantName}Proto" })

            if (variantName.endsWith("UnitTest")) {
                val mainVariantName = variantName.removeSuffix("UnitTest")
                dependsOn(tasks.matching { it.name == "generate${mainVariantName}Proto" })
            }
        }
    }
}
