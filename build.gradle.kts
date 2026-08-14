import java.util.Locale
import com.adsamcik.tracker.buildlogic.CollectReleaseEvidenceTask
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import dev.detekt.gradle.extensions.FailOnSeverity

plugins {
	id("tracker.root.verification")
	alias(libs.plugins.detekt)
	// gradlew dependencyUpdates -Drevision=release
	alias(libs.plugins.benmanes.versions)
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.android.library) apply false
	alias(libs.plugins.android.kotlin.multiplatform.library) apply false
	alias(libs.plugins.kotlin.multiplatform) apply false
	alias(libs.plugins.kotlin.parcelize) apply false
	alias(libs.plugins.kotlin.serialization) apply false
	alias(libs.plugins.kotlin.compose) apply false
	alias(libs.plugins.ksp) apply false
	alias(libs.plugins.hilt) apply false
}

detekt {
	source.setFrom(layout.projectDirectory)
	config.setFrom(layout.projectDirectory.file("detekt.yml"))
	baseline.set(layout.projectDirectory.file("detekt-baseline.xml"))
	buildUponDefaultConfig.set(true)
	parallel.set(true)
	ignoreFailures.set(false)
	failOnSeverity.set(FailOnSeverity.Error)
	basePath.set(layout.projectDirectory)
}

fun PatternFilterable.configureRepositoryDetektSources() {
	include("**/*.kt", "**/*.kts")
	exclude(
		"**/.gradle/**",
		"**/build/**",
		"**/generated/**",
		"**/node_modules/**",
	)
}

tasks.withType<Detekt>().configureEach {
	configureRepositoryDetektSources()
	reports {
		html.required.set(true)
		checkstyle.required.set(true)
		sarif.required.set(true)
		markdown.required.set(true)
	}
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
	configureRepositoryDetektSources()
}

// OpenGL map renderer toggle (emulator builds only). See the dependency-substitution block in
// `subprojects` below for the rationale. Resolved once here where the `libs` version-catalog
// accessor is in scope; `maplibreOpenGlModule` reuses the catalog's android-sdk-opengl coordinates
// so the OpenGL build version stays in lockstep with the bundled native SDK.
val useOpenGlMapRenderer: Boolean = providers.gradleProperty("useOpenGlMapRenderer")
	.map(String::toBoolean)
	.getOrElse(false)
val maplibreOpenGlModule: String = libs.maplibre.android.opengl.get().toString()
val maplibreVulkanModule =
	"org.maplibre.gl:android-sdk:${libs.versions.maplibreAndroid.get()}"
val maplibreRendererModule =
	if (useOpenGlMapRenderer) maplibreOpenGlModule else maplibreVulkanModule
val maplibreRendererReason = if (useOpenGlMapRenderer) {
	"Vulkan renderer segfaults on software-emulated GPUs; use the OpenGL native build"
} else {
	"Release native SDK is pinned to the reviewed 16 KiB-aligned MapLibre version"
}

subprojects {
	tasks.withType<JavaCompile>().configureEach {
		options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
	}

	// Optional OpenGL map renderer for emulator builds. MapLibre Android 13 defaults to the Vulkan
	// renderer, which segfaults inside libmaplibre.so (mbgl::android::MapRenderer::render) on the
	// software-emulated GPUs that Android emulators expose. The renderer backend is fixed per
	// native-SDK build (not runtime-toggleable), so we swap the whole native SDK for its drop-in
	// OpenGL build. Pass -PuseOpenGlMapRenderer=true to enable; device and release builds keep
	// Vulkan by leaving the flag unset.
	configurations.configureEach {
		resolutionStrategy.dependencySubstitution {
			substitute(module("org.maplibre.gl:android-sdk"))
				.using(module(maplibreRendererModule))
				.because(maplibreRendererReason)
		}
	}
}

tasks.register("clean", Delete::class) {
	delete(rootProject.layout.buildDirectory)
}

val releaseBundletool = configurations.create("releaseBundletool") {
	isCanBeConsumed = false
	isCanBeResolved = true
	description = "Pinned bundletool classpath for non-deploying release evidence"
}

dependencies {
	add(releaseBundletool.name, libs.android.bundletool)
}

val releasePythonExecutable = providers.environmentVariable("PYTHON").orElse(
	if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
		"python"
	} else {
		"python3"
	}
)
val releaseEvidenceOutput = layout.buildDirectory.dir("release-evidence")

val testReleaseEvidence = tasks.register<Exec>("testReleaseEvidence") {
	group = "verification"
	description = "Runs controlled bad-input fixtures for release evidence validators."
	workingDir(rootDir)
	commandLine(
		releasePythonExecutable.get(),
		"-m",
		"unittest",
		"discover",
		"-s",
		"tools/tests",
		"-p",
		"test_*.py",
		"-v",
	)
}

val verifyReleaseDependencyMetadata = tasks.register<Exec>("verifyReleaseDependencyMetadata") {
	group = "verification"
	description = "Verifies the canonical selective dependency checksum policy."
	workingDir(rootDir)
	inputs.files(
		layout.projectDirectory.file("tools/selective_verification_metadata.py"),
		layout.projectDirectory.file("release/release-inputs.json"),
		layout.projectDirectory.file("gradle/verification-metadata.xml"),
	)
	commandLine(
		releasePythonExecutable.get(),
		"tools/selective_verification_metadata.py",
		"--check",
	)
}

tasks.named("ciCheck").configure {
	dependsOn(testReleaseEvidence, verifyReleaseDependencyMetadata)
}

tasks.register<CollectReleaseEvidenceTask>("releaseValidation") {
	group = "verification"
	description =
		"Builds the release AAB and representative debug-signed APK set, then records strict evidence."
	notCompatibleWithConfigurationCache(
		"The Google OSS Licenses release task is not configuration-cache serializable."
	)
	dependsOn(
		testReleaseEvidence,
		verifyReleaseDependencyMetadata,
		"checkRoomSchemaDrift",
		":app:bundleRelease",
	)
	outputs.upToDateWhen { false }
	validationScript.set(layout.projectDirectory.file("tools/release_validation.py"))
	releaseInputs.set(layout.projectDirectory.file("release/release-inputs.json"))
	bundletoolClasspath.from(releaseBundletool)
	pythonExecutable.set(releasePythonExecutable)
	repositoryDirectory.set(layout.projectDirectory)
	evidenceDirectory.set(releaseEvidenceOutput)
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
