package com.adsamcik.tracker.buildlogic

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import javax.inject.Inject

private const val REQUIRED_RELEASE_SQLITE_VERSION = "3.51.3"
private const val BUNDLED_SQLITE_RUNTIME_VERSION = "3.53.3"
private const val BUNDLED_SQLITE_RUNTIME_SOURCE_ID =
    "2026-06-26 20:14:12 d4c0e51e4aeb96955b99185ab9cde75c339e2c29c3f3f12428d364a10d782c62"
private const val BUNDLED_SQLITE_RUNTIME_SHA3 =
    "d7a6e906a0d06472b56ef7bb4824a6be7b5eb5f162b24be0a0bad2e0c917ed93"
private val BUNDLED_SQLITE_RUNTIME_ABIS =
    listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

private fun parseNumericVersion(raw: String): List<Int> = raw
    .substringBefore('-')
    .split('.')
    .map { component ->
        component.toIntOrNull()
            ?: throw GradleException("SQLite runtime version is not numeric: $raw")
    }

private fun isVersionAtLeast(actual: String, minimum: String): Boolean {
    val actualParts = parseNumericVersion(actual)
    val minimumParts = parseNumericVersion(minimum)
    val size = maxOf(actualParts.size, minimumParts.size)
    for (index in 0 until size) {
        val comparison =
            actualParts.getOrElse(index) { 0 }.compareTo(minimumParts.getOrElse(index) { 0 })
        if (comparison != 0) return comparison > 0
    }
    return true
}

private fun sha3_256(file: File): String {
    val digest = MessageDigest.getInstance("SHA3-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

@DisableCachingByDefault(because = "Fast release-safety verification has no output artifact")
abstract class VerifyVendoredSqliteRuntimeTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeAar: RegularFileProperty

    @get:Input
    abstract val bundledVersion: Property<String>

    @get:Input
    abstract val requiredVersion: Property<String>

    @get:Input
    abstract val expectedSha3: Property<String>

    @get:Input
    abstract val expectedSourceId: Property<String>

    @get:Input
    abstract val expectedAbis: ListProperty<String>

    @TaskAction
    fun verify() {
        val aar = runtimeAar.asFile.get()
        if (!aar.isFile) {
            throw GradleException(
                "Release packaging is blocked: fixed SQLite runtime AAR is missing at $aar.",
            )
        }

        val actualVersion = bundledVersion.get()
        val minimumVersion = requiredVersion.get()
        if (!isVersionAtLeast(actualVersion, minimumVersion)) {
            throw GradleException(
                "Release packaging is blocked: vendored SQLite $actualVersion is below " +
                    "the required $minimumVersion WAL-reset-fix floor.",
            )
        }

        if (sha3_256(aar) != expectedSha3.get()) {
            throw GradleException(
                "Release packaging is blocked: vendored SQLite AAR SHA3-256 does not match " +
                    "the verified official artifact.",
            )
        }

        val sourceId = expectedSourceId.get()
        ZipFile(aar).use { archive ->
            expectedAbis.get().forEach { abi ->
                val entryName = "jni/$abi/libsqliteX.so"
                val entry = archive.getEntry(entryName)
                    ?: throw GradleException(
                        "Release packaging is blocked: vendored SQLite AAR is missing ABI $abi.",
                    )
                val containsSourceId = archive.getInputStream(entry).use { input ->
                    input.readBytes()
                        .toString(Charsets.ISO_8859_1)
                        .contains(sourceId)
                }
                if (!containsSourceId) {
                    throw GradleException(
                        "Release packaging is blocked: SQLite ABI $abi does not contain the " +
                            "expected fixed source ID $sourceId.",
                    )
                }
            }
        }
    }
}

@DisableCachingByDefault(because = "Fast release-safety verification has no output artifact")
abstract class VerifyResolvedSqliteRuntimeTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeNativeLibraries: ConfigurableFileCollection

    @get:Input
    abstract val configurationDisplayName: Property<String>

    @get:Input
    abstract val expectedSourceId: Property<String>

    @get:Input
    abstract val expectedAbis: ListProperty<String>

    @TaskAction
    fun verify() {
        val sqliteLibraries = runtimeNativeLibraries.files
            .flatMap { artifact ->
                when {
                    artifact.isDirectory -> artifact
                        .walkTopDown()
                        .filter { it.isFile && it.name == "libsqliteX.so" }
                        .toList()
                    artifact.isFile && artifact.name == "libsqliteX.so" -> listOf(artifact)
                    else -> emptyList()
                }
            }
            .distinctBy(File::getAbsolutePath)

        val sourceId = expectedSourceId.get()
        expectedAbis.get().forEach { abi ->
            val matchingLibrary = sqliteLibraries.firstOrNull { library ->
                library.invariantSeparatorsPath
                    .split('/')
                    .contains(abi) &&
                    library.readBytes()
                        .toString(Charsets.ISO_8859_1)
                        .contains(sourceId)
            }
            if (matchingLibrary == null) {
                throw GradleException(
                    "Release packaging is blocked: ${configurationDisplayName.get()} does not " +
                        "resolve the verified SQLite native library for ABI $abi.",
                )
            }
        }
    }
}

@DisableCachingByDefault(because = "Checks Git working-tree state rather than producing an artifact")
abstract class CheckRoomSchemaDriftTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaDirectories: ConfigurableFileCollection

    @get:Input
    abstract val schemaPaths: ListProperty<String>

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @TaskAction
    fun checkDrift() {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            workingDir(repositoryDirectory.get().asFile)
            commandLine(
                listOf("git", "status", "--porcelain", "--") + schemaPaths.get(),
            )
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }

        val schemaStatus = output.toString(Charsets.UTF_8).trim()
        if (result.exitValue != 0) {
            throw GradleException(
                "Unable to check Room schema drift with git status:\n$schemaStatus",
            )
        }
        if (schemaStatus.isNotEmpty()) {
            throw GradleException(
                "Room schema drift detected. Commit generated schema JSON changes or fix " +
                    "the migration.\n$schemaStatus",
            )
        }
    }
}

class RootVerificationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            require(this == rootProject) {
                "tracker.root.verification must only be applied to the root project"
            }

            val verifyVendoredRuntime =
                tasks.register(
                    "verifyVendoredReleaseSqliteRuntime",
                    VerifyVendoredSqliteRuntimeTask::class.java,
                ) {
                    group = "verification"
                    description =
                        "Verifies the vendored SQLite runtime contains the WAL-reset fix for every ABI."
                    runtimeAar.set(
                        layout.projectDirectory.file(
                            "core/sqlite-runtime/libs/sqlite-android-3530300.aar",
                        ),
                    )
                    bundledVersion.set(BUNDLED_SQLITE_RUNTIME_VERSION)
                    requiredVersion.set(REQUIRED_RELEASE_SQLITE_VERSION)
                    expectedSha3.set(BUNDLED_SQLITE_RUNTIME_SHA3)
                    expectedSourceId.set(BUNDLED_SQLITE_RUNTIME_SOURCE_ID)
                    expectedAbis.set(BUNDLED_SQLITE_RUNTIME_ABIS)
                }

            val verifyReleaseRuntime = tasks.register("verifyReleaseSqliteRuntime") {
                group = "verification"
                description =
                    "Verifies the vendored SQLite binary and all app release runtime classpaths."
                dependsOn(verifyVendoredRuntime)
            }

            subprojects.forEach { subproject ->
                subproject.pluginManager.withPlugin("com.android.application") {
                    val androidComponents = subproject.extensions.getByType(
                        ApplicationAndroidComponentsExtension::class.java,
                    )
                    androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                        if (!variant.buildType.orEmpty().contains("release", ignoreCase = true)) {
                            return@onVariants
                        }

                        val configurationName = "${variant.name}RuntimeClasspath"
                        val runtimeConfiguration =
                            subproject.configurations.named(configurationName)
                        val resolvedNativeLibraries = runtimeConfiguration.get()
                            .incoming
                            .artifactView {
                                attributes.attribute(
                                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                    "android-jni",
                                )
                            }
                            .files
                        val variantSegment = variant.name.capitalized()
                        // Keep configuration-backed tasks in the project that owns
                        // the configuration. This gives configuration-cache
                        // serialization the correct project lock.
                        val linkageTask = subproject.tasks.register(
                            "verify${variantSegment}SqliteRuntimeLinkage",
                            VerifyResolvedSqliteRuntimeTask::class.java,
                        ) {
                            group = "verification"
                            description =
                                "Verifies ${subproject.path}:$configurationName resolves the vendored SQLite runtime."
                            dependsOn(verifyVendoredRuntime)
                            runtimeNativeLibraries.from(resolvedNativeLibraries)
                            configurationDisplayName.set(
                                "${subproject.path}:$configurationName",
                            )
                            expectedSourceId.set(BUNDLED_SQLITE_RUNTIME_SOURCE_ID)
                            expectedAbis.set(BUNDLED_SQLITE_RUNTIME_ABIS)
                        }

                        // A variant's public artifact tasks verify their own classpath.
                        subproject.tasks.matching { task ->
                            task.name == "assemble$variantSegment" ||
                                task.name == "bundle$variantSegment" ||
                                task.name.startsWith("package$variantSegment")
                        }.configureEach {
                            dependsOn(linkageTask)
                        }

                        verifyReleaseRuntime.configure {
                            dependsOn(linkageTask)
                        }
                    }
                }
            }

            val checkRoomSchemaDrift =
                tasks.register("checkRoomSchemaDrift", CheckRoomSchemaDriftTask::class.java) {
                    group = "verification"
                    description =
                        "Generates Room schemas and fails when schema JSON changes are not committed."
                    repositoryDirectory.set(layout.projectDirectory)
                    schemaPaths.convention(emptyList())
                }

            subprojects.forEach { subproject ->
                subproject.pluginManager.withPlugin("tracker.android.room") {
                    val schemaPath = subproject.projectDir
                        .relativeTo(rootDir)
                        .invariantSeparatorsPath + "/schemas"
                    checkRoomSchemaDrift.configure {
                        dependsOn("${subproject.path}:kspDebugKotlin")
                        schemaPaths.add(schemaPath)
                        schemaDirectories.from(
                            subproject.layout.projectDirectory.dir("schemas"),
                        )
                    }
                }
            }
        }
    }
}

private fun String.capitalized(): String =
    replaceFirstChar { first ->
        if (first.isLowerCase()) first.titlecase(Locale.ROOT) else first.toString()
    }
