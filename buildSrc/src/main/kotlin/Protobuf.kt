import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.withGroovyBuilder

private fun String.kspVariantName(): String? {
    if (!startsWith("ksp") || !endsWith("Kotlin")) return null

    return removePrefix("ksp")
        .removeSuffix("Kotlin")
        .takeIf(String::isNotBlank)
}

/**
 * Configures the Protobuf Gradle plugin for Android library modules.
 *
 * Modules must apply the protobuf plugin themselves in their plugins block:
 * ```
 * plugins {
 *     alias(libs.plugins.protobuf)
 * }
 * configureProtobuf()
 * ```
 *
 * Uses untyped Groovy builder API to avoid requiring protobuf-gradle-plugin
 * or AGP types on the buildSrc classpath (which would conflict with versioned
 * alias() declarations in module build files).
 */
fun Project.configureProtobuf() {
    val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    val protobufVersion = libs.findVersion("protobuf").get().requiredVersion

    // Configure protobuf extension using Groovy builder (no typed ProtobufExtension needed)
    extensions.getByName("protobuf").withGroovyBuilder {
        "protoc" {
            setProperty("artifact", "com.google.protobuf:protoc:$protobufVersion")
        }
        "generateProtoTasks" {
            invokeMethod("all", emptyArray<Any>()).let { tasks ->
                @Suppress("UNCHECKED_CAST")
                (tasks as? Iterable<Any>)?.forEach { task ->
                    task.withGroovyBuilder {
                        "builtins" {
                            invokeMethod("create", arrayOf("java"))
                        }
                    }
                }
            }
        }
    }

    // Add generated proto sources to the Android source sets
    extensions.getByName("android").withGroovyBuilder {
        "sourceSets" {
            invokeMethod("getByName", arrayOf("debug")).withGroovyBuilder {
                "java" {
                    invokeMethod("srcDir", arrayOf("build/generated/source/proto/debug/java"))
                }
            }
            invokeMethod("getByName", arrayOf("release")).withGroovyBuilder {
                "java" {
                    invokeMethod("srcDir", arrayOf("build/generated/source/proto/release/java"))
                }
            }
        }
    }

    // Wire KSP tasks to depend on proto generation so generated proto classes
    // are available when KSP processors (Room, Hilt) run.
    afterEvaluate {
        tasks.forEach { task ->
            val variantName = task.name.kspVariantName() ?: return@forEach

            tasks.findByName("generate${variantName}Proto")?.let { task.dependsOn(it) }

            if (variantName.endsWith("UnitTest")) {
                val mainVariantName = variantName.removeSuffix("UnitTest")
                tasks.findByName("generate${mainVariantName}Proto")?.let { task.dependsOn(it) }
            }
        }
    }
}
