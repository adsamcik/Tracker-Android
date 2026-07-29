import org.gradle.api.GradleException
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

plugins {
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

// OpenGL map renderer toggle (emulator builds only). See the dependency-substitution block in
// `subprojects` below for the rationale. Resolved once here where the `libs` version-catalog
// accessor is in scope; `maplibreOpenGlModule` reuses the catalog's android-sdk-opengl coordinates
// so the OpenGL build version stays in lockstep with the bundled native SDK.
val useOpenGlMapRenderer: Boolean = providers.gradleProperty("useOpenGlMapRenderer")
	.map(String::toBoolean)
	.getOrElse(false)
val maplibreOpenGlModule: String = libs.maplibre.android.opengl.get().toString()

/**
 * A release must never silently package the known WAL-reset-affected SQLite
 * runtime.  Runtime telemetry remains necessary to attest the selected ABI on
 * real hardware, but it cannot make an unsafe binary safe after it ships.
 *
 * Keep this version floor aligned with the first upstream SQLite release that
 * contains the WAL-reset fix.  The official Android binding is vendored with
 * its upstream SHA3-256 and every supported ABI's SQLite source ID is checked
 * before a release package can be created.
 */
val requiredReleaseSqliteVersion = "3.51.3"
val bundledSqliteRuntimeVersion = "3.53.3"
val bundledSqliteRuntimeSourceId =
	"2026-06-26 20:14:12 d4c0e51e4aeb96955b99185ab9cde75c339e2c29c3f3f12428d364a10d782c62"
val bundledSqliteRuntimeSha3 =
	"d7a6e906a0d06472b56ef7bb4824a6be7b5eb5f162b24be0a0bad2e0c917ed93"
val bundledSqliteRuntimeAar =
	rootProject.layout.projectDirectory.file("core/sqlite-runtime/libs/sqlite-android-3530300.aar").asFile
val bundledSqliteRuntimeAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

fun parseNumericVersion(raw: String): List<Int> = raw
	.substringBefore('-')
	.split('.')
	.map { component ->
		component.toIntOrNull()
			?: throw GradleException("SQLite runtime version is not numeric: $raw")
	}

fun isVersionAtLeast(actual: String, minimum: String): Boolean {
	val actualParts = parseNumericVersion(actual)
	val minimumParts = parseNumericVersion(minimum)
	val size = maxOf(actualParts.size, minimumParts.size)
	for (index in 0 until size) {
		val comparison = actualParts.getOrElse(index) { 0 }.compareTo(minimumParts.getOrElse(index) { 0 })
		if (comparison != 0) return comparison > 0
	}
	return true
}

fun sha3_256(bytes: ByteArray): String = MessageDigest.getInstance("SHA3-256")
	.digest(bytes)
	.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

fun containsAscii(bytes: ByteArray, value: String): Boolean =
	bytes.toString(Charsets.ISO_8859_1).contains(value)

val verifyReleaseSqliteRuntime = tasks.register("verifyReleaseSqliteRuntime") {
	group = "verification"
	description = "Verifies the vendored SQLite runtime contains the WAL-reset fix for every packaged ABI."
	inputs.file(bundledSqliteRuntimeAar)
	inputs.property("bundledSqliteRuntimeVersion", bundledSqliteRuntimeVersion)
	inputs.property("bundledSqliteRuntimeSourceId", bundledSqliteRuntimeSourceId)
	inputs.property("requiredSqliteVersion", requiredReleaseSqliteVersion)

	doLast {
		if (!bundledSqliteRuntimeAar.isFile) {
			throw GradleException(
				"Release packaging is blocked: fixed SQLite runtime AAR is missing at " +
					bundledSqliteRuntimeAar.relativeTo(rootProject.projectDir),
			)
		}
		if (!isVersionAtLeast(bundledSqliteRuntimeVersion, requiredReleaseSqliteVersion)) {
			throw GradleException(
				"Release packaging is blocked: vendored SQLite $bundledSqliteRuntimeVersion is below " +
					"the required $requiredReleaseSqliteVersion WAL-reset-fix floor.",
			)
		}
		if (sha3_256(bundledSqliteRuntimeAar.readBytes()) != bundledSqliteRuntimeSha3) {
			throw GradleException(
				"Release packaging is blocked: vendored SQLite AAR SHA3-256 does not match the " +
					"verified official artifact.",
			)
		}
		ZipFile(bundledSqliteRuntimeAar).use { archive ->
			bundledSqliteRuntimeAbis.forEach { abi ->
				val entryName = "jni/$abi/libsqliteX.so"
				val entry = archive.getEntry(entryName)
					?: throw GradleException(
						"Release packaging is blocked: vendored SQLite AAR is missing ABI $abi.",
					)
				val sourceMatches = archive.getInputStream(entry).use { input ->
					containsAscii(input.readBytes(), bundledSqliteRuntimeSourceId)
				}
				if (!sourceMatches) {
					throw GradleException(
						"Release packaging is blocked: SQLite ABI $abi does not contain the expected " +
							"fixed source ID $bundledSqliteRuntimeSourceId.",
					)
				}
			}
		}
	}
}

subprojects {
	// Gate every artifact-producing release task, including flavor variants.  This
	// deliberately leaves debug/dev builds usable for development while making a
	// known-unsafe native runtime impossible to package as a release artifact.
	tasks.matching { task ->
		val name = task.name.lowercase(Locale.ROOT)
		(name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("package")) &&
			name.contains("release")
	}.configureEach {
		dependsOn(rootProject.tasks.named("verifyReleaseSqliteRuntime"))
	}

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
