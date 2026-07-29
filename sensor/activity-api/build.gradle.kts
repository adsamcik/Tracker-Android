import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    id("tracker.android.library")
}

abstract class VerifyActivityApiDependencies : DefaultTask() {
    @get:Input
    abstract val componentIds: ListProperty<String>

    @TaskAction
    fun verify() {
        val forbidden = componentIds.get().filter { component ->
            component in setOf("project::core:base", "project::stats:engine") ||
                component.startsWith("module:com.google.android.gms:")
        }

        check(forbidden.isEmpty()) {
            "Forbidden activity-api dependencies:\n${forbidden.distinct().joinToString("\n")}"
        }
    }
}

android {
    namespace = "com.adsamcik.tracker.activity"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":stats:api"))

    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
}

val verifyActivityApiDependencies = tasks.register<VerifyActivityApiDependencies>(
    "verifyActivityApiDependencies",
) {
    group = "verification"
    description = "Rejects implementation and Google Play Services leakage into activity-api."
    componentIds.set(
        configurations.getByName("debugCompileClasspath")
            .incoming
            .resolutionResult
            .rootComponent
            .map { root ->
                val pending = ArrayDeque<ResolvedComponentResult>()
                val visited = mutableSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()
                val result = mutableListOf<String>()
                pending.add(root)
                while (pending.isNotEmpty()) {
                    val component = pending.removeFirst()
                    if (!visited.add(component.id)) continue
                    when (val id = component.id) {
                        is ProjectComponentIdentifier -> result += "project:${id.projectPath}"
                        is ModuleComponentIdentifier ->
                            result += "module:${id.group}:${id.module}:${id.version}"
                    }
                    component.dependencies
                        .filterIsInstance<ResolvedDependencyResult>()
                        .forEach { dependency -> pending.add(dependency.selected) }
                }
                result
            },
    )
}

tasks.named("check") {
    dependsOn(verifyActivityApiDependencies)
}

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(verifyActivityApiDependencies)
}
