import org.gradle.api.GradleException
import java.io.File
import java.util.Locale

plugins {
	// gradlew dependencyUpdates -Drevision=release
	alias(libs.plugins.benmanes.versions)
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.android.library) apply false
	alias(libs.plugins.android.kotlin.multiplatform.library) apply false
	alias(libs.plugins.kotlin.android) apply false
	alias(libs.plugins.kotlin.multiplatform) apply false
	alias(libs.plugins.kotlin.parcelize) apply false
	alias(libs.plugins.kotlin.serialization) apply false
	alias(libs.plugins.kotlin.compose) apply false
	alias(libs.plugins.ksp) apply false
	alias(libs.plugins.hilt) apply false
}

// OpenGL map renderer toggle (emulator builds only). See the dependency-substitution block in
// `subprojects` below for the rationale. Resolved once here where the `libs` version-catalog
// accessor is in scope; `maplibreOpenGlModule` reuses the catalog's android-sdk-opengl coordinates
// so the OpenGL build version stays in lockstep with the bundled native SDK.
val useOpenGlMapRenderer: Boolean = providers.gradleProperty("useOpenGlMapRenderer")
	.map(String::toBoolean)
	.getOrElse(false)
val maplibreOpenGlModule: String = libs.maplibre.android.opengl.get().toString()

subprojects {
	gradle.projectsEvaluated {
		tasks.withType(JavaCompile::class.java) {
			options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
		}
	}

	// Optional OpenGL map renderer for emulator builds. MapLibre Android 13 defaults to the Vulkan
	// renderer, which segfaults inside libmaplibre.so (mbgl::android::MapRenderer::render) on the
	// software-emulated GPUs that Android emulators expose. The renderer backend is fixed per
	// native-SDK build (not runtime-toggleable), so we swap the whole native SDK for its drop-in
	// OpenGL build. Pass -PuseOpenGlMapRenderer=true to enable; device and release builds keep
	// Vulkan by leaving the flag unset.
	if (useOpenGlMapRenderer) {
		configurations.configureEach {
			resolutionStrategy.dependencySubstitution {
				substitute(module("org.maplibre.gl:android-sdk"))
					.using(module(maplibreOpenGlModule))
					.because(
						"Vulkan renderer segfaults on software-emulated GPUs; " +
							"use the OpenGL native build for emulator builds (-PuseOpenGlMapRenderer)"
					)
			}
		}
	}

	// Configure Kotlin compiler options for all projects
	tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
		compilerOptions {
			freeCompilerArgs.add("-Xannotation-default-target=param-property")
			freeCompilerArgs.add("-Xmetadata-version=2.1.0")
		}
	}
}

tasks.register("clean", Delete::class) {
	delete(rootProject.layout.buildDirectory)
}

val roomSchemaProjects = subprojects
	.filter { it.layout.projectDirectory.dir("schemas").asFile.isDirectory }
	.sortedBy { it.path }

val roomSchemaGeneratorTasks = roomSchemaProjects.map { "${it.path}:kspDebugKotlin" }

val roomSchemaPaths = roomSchemaProjects.map { project ->
	"${project.projectDir.relativeTo(rootDir).path.replace(File.separatorChar, '/')}/schemas"
}

tasks.register("checkRoomSchemaDrift") {
	group = "verification"
	description = "Generates Room schemas and fails when schema JSON changes are not committed."
	dependsOn(roomSchemaGeneratorTasks)
	inputs.files(roomSchemaPaths.map { layout.projectDirectory.dir(it) })

	doLast {
		val process = ProcessBuilder(listOf("git", "status", "--porcelain", "--") + roomSchemaPaths)
			.directory(rootDir)
			.redirectErrorStream(true)
			.start()

		val schemaStatus = process.inputStream.bufferedReader().use { it.readText() }.trim()
		val exitCode = process.waitFor()
		if (exitCode != 0) {
			throw GradleException("Unable to check Room schema drift with git status:\n$schemaStatus")
		}
		if (schemaStatus.isNotEmpty()) {
			throw GradleException(
				"Room schema drift detected. Commit generated schema JSON changes or fix the migration.\n" +
					schemaStatus
			)
		}
	}
}

/**
 * Returns true if version is considered stable.
 */
fun isStable(version: String): Boolean {
	val stableKeyword = listOf("RELEASE", "FINAL", "GA").any {
		version.uppercase(Locale.getDefault())
			.contains(it)
	}
	val regex = "^[0-9,.v-]+(-r)?$".toRegex()
	return stableKeyword || regex.matches(version)
}


tasks.named(
	"dependencyUpdates",
	com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask::class.java
).configure {
	resolutionStrategy {
		componentSelection {
			all(Action<com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent> {
				if (!isStable(candidate.version) && isStable(currentVersion)) {
					reject("Release candidate")
				}
			})
		}
	}
}
