import org.gradle.api.GradleException
import java.util.Properties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    id("tracker.android.application")
    id("tracker.android.compose")
    id("tracker.android.hilt")
    id("tracker.android.room")
    id("tracker.android.test")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.oss.licenses)
}
val localProperties = Properties().apply {
	val localPropertiesFile = rootProject.file("local.properties")
	if (localPropertiesFile.isFile) {
		localPropertiesFile.inputStream().use(::load)
	}
}

fun signingProperty(environmentName: String, localPropertyName: String): String? =
	providers.environmentVariable(environmentName).orNull?.takeIf(String::isNotBlank)
		?: localProperties.getProperty(localPropertyName)?.takeIf(String::isNotBlank)

data class ReleaseSigningProperties(
	val storeFile: File,
	val storePassword: String,
	val keyAlias: String,
	val keyPassword: String
)

val releaseStoreFilePath = signingProperty(
	"TRACKER_RELEASE_STORE_FILE",
	"tracker.release.storeFile"
)
val releaseStorePassword = signingProperty(
	"TRACKER_RELEASE_STORE_PASSWORD",
	"tracker.release.storePassword"
)
val releaseKeyAlias = signingProperty(
	"TRACKER_RELEASE_KEY_ALIAS",
	"tracker.release.keyAlias"
)
val releaseKeyPassword = signingProperty(
	"TRACKER_RELEASE_KEY_PASSWORD",
	"tracker.release.keyPassword"
)
val releaseSigningValues = listOf(
	releaseStoreFilePath,
	releaseStorePassword,
	releaseKeyAlias,
	releaseKeyPassword
)

if (releaseSigningValues.any { it != null } && releaseSigningValues.any { it == null }) {
	throw GradleException(
		"Partial release signing configuration found. Provide all TRACKER_RELEASE_* environment variables " +
			"or all tracker.release.* local.properties entries."
	)
}

val releaseSigningProperties = releaseStoreFilePath?.let { storeFilePath ->
	val storeFile = rootProject.file(storeFilePath)
	if (!storeFile.isFile) {
		throw GradleException("Release keystore file does not exist: $storeFilePath")
	}
	ReleaseSigningProperties(
		storeFile = storeFile,
		storePassword = checkNotNull(releaseStorePassword),
		keyAlias = checkNotNull(releaseKeyAlias),
		keyPassword = checkNotNull(releaseKeyPassword)
	)
}

android {
    defaultConfig {
        applicationId = "com.adsamcik.tracker"
        versionCode = 400
        versionName = "10.0.0"
        resourceConfigurations.addAll(listOf("en", "cs-rCZ"))
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            optIn.add("kotlin.ExperimentalUnsignedTypes")
        }
    }

    val releaseSigningConfig = releaseSigningProperties?.let { signing ->
        signingConfigs.create("release") {
            storeFile = signing.storeFile
            storePassword = signing.storePassword
            keyAlias = signing.keyAlias
            keyPassword = signing.keyPassword
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "COMPOSE_MAIN", "true")
        }

        val release = getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "COMPOSE_MAIN", "true")
            releaseSigningConfig?.let {
                signingConfig = it
            }
        }

        // Installable alongside production: non-debuggable, unique appId/label
        create("dev") {
            // Base on release settings for closer-to-prod behavior
            initWith(release)
            // Use debug dependencies if a matching dev variant doesn't exist in deps
            matchingFallbacks += listOf("debug", "release")
            // Distinct identity on device and in Play/adb lists
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // Clear label marker so users can tell builds apart
            resValue("string", "app_name", "Advention Dev")
            // Sign with debug key for easy local installs (customize if you have a dev keystore)
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("boolean", "COMPOSE_MAIN", "true")
        }

        getByName("release_nominify") {
            initWith(release)
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
        // viewBinding no longer used; Compose-only UI
        viewBinding = false
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }

    sourceSets.getByName("main").res.srcDir("$buildDir/generated/third_party_licenses_fallback/res")

    // dynamicFeatures removed; modules are now statically linked libraries
    namespace = "com.adsamcik.tracker"
    dependenciesInfo {
        includeInApk = true
        includeInBundle = true
    }
}
val releaseLintReport = layout.buildDirectory.file("reports/lint-results-release.xml")

tasks.register("checkReleaseLintReport") {
	group = "verification"
	description = "Fails when app release lint reports unbaselined fatal/error issues."
	dependsOn("lintReportRelease")
	mustRunAfter("lintRelease")
	inputs.file(releaseLintReport)

	doLast {
		val report = releaseLintReport.get().asFile
		if (!report.isFile) {
			throw GradleException("Release lint report was not generated: ${report.absolutePath}")
		}

		val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
			setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
			isExpandEntityReferences = false
		}
		val document = documentBuilderFactory.newDocumentBuilder().parse(report)
		val issues = document.getElementsByTagName("issue")
		val blockingIssues = buildList {
			for (index in 0 until issues.length) {
				val issue = issues.item(index) as? Element ?: continue
				val severity = issue.getAttribute("severity")
				if (severity != "Fatal" && severity != "Error") continue

				val id = issue.getAttribute("id")
				val message = issue.getAttribute("message")
				val locations = issue.getElementsByTagName("location")
				val location = if (locations.length > 0) {
					val element = locations.item(0) as Element
					val file = element.getAttribute("file")
					val line = element.getAttribute("line")
					if (line.isBlank()) file else "$file:$line"
				} else {
					"no location"
				}
				add("[$severity][$id] $message ($location)")
			}
		}

		if (blockingIssues.isNotEmpty()) {
			val visibleIssues = blockingIssues.take(20).joinToString(separator = "\n")
			val remaining = blockingIssues.size - 20
			val suffix = if (remaining > 0) "\n... and $remaining more" else ""
			throw GradleException(
				"App release lint reported ${blockingIssues.size} unbaselined fatal/error issue(s):\n" +
					visibleIssues +
					suffix
			)
		}
	}
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":core:base"))
    implementation(project(":core:logging-api"))
    implementation(project(":core:network"))
    implementation(project(":tracker:engine"))
    implementation(project(":feature:tracker"))
    implementation(project(":sensor:activity-api"))
    implementation(project(":sensor:activity"))
    implementation(project(":feature:activity"))
    implementation(project(":domain:points"))
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
    implementation(project(":core:logging"))
    implementation(project(":feature:import-export"))
    implementation(project(":feature:statistics:api"))
    implementation(project(":feature:statistics"))
    implementation(project(":stats:data"))
    implementation(project(":feature:map:api"))
    implementation(project(":feature:map"))
    implementation(project(":feature:game:api"))
    implementation(project(":feature:game"))
    implementation(project(":feature:dashboard:api"))
    implementation(project(":feature:dashboard"))
    implementation(project(":domain:osm"))

    // Core
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)
    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    androidTestImplementation(libs.androidx.work.testing)

    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)

    // Glance App Widgets
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // App Startup
    implementation(libs.androidx.startup.runtime)

    // Compose
    androidTestImplementation(platform(libs.compose.bom))

    // Glance (App Widgets)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.graphics)
    // AndroidViewBinding is no longer used
    implementation(libs.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.constraintlayout.compose)
    implementation(libs.haze)
    androidTestImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    // proto-java is needed at compile time for debug TestDataSeeder which interacts with
    // OnboardingStateProto's GeneratedMessageV3 supertype. Release does not depend on this.
    debugImplementation(libs.protobuf.java)
    // 1st party dependencies
    implementation(libs.component.slider)
    // Draggable overlay removed with legacy fallback

    implementation(libs.spotlight)

    // 3rd party dependencies
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    // Google dependencies
    implementation(libs.androidx.cardview)

    // Open-source licenses
    implementation(libs.licensesdialog)

    // PlayServices
    implementation(libs.google.play.services.location)

    testImplementation(libs.androidx.work.testing)
    testImplementation(project(":stats:api"))
    testImplementation(project(":stats:engine"))
    androidTestImplementation(project(":core:testing"))
    testImplementation(project(":core:testing"))
}
// Configure JUnit 5 for unit tests
tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

val syncReleaseLicenseFallback by tasks.registering(Sync::class) {
	dependsOn(tasks.named("releaseOssLicensesTask"))
	from(layout.buildDirectory.dir("generated/third_party_licenses/release/res/raw")) {
		include("third_party_license_metadata", "third_party_licenses")
		rename("third_party_license_metadata", "third_party_license_metadata_release_fallback")
		rename("third_party_licenses", "third_party_licenses_release_fallback")
	}
	into(layout.buildDirectory.dir("generated/third_party_licenses_fallback/res/raw"))
}

tasks.configureEach {
	if (
		name in setOf(
			"mapDebugSourceSetPaths",
			"generateDebugResources",
			"mergeDebugResources",
			"processDebugNavigationResources",
			"packageDebugResources",
			"mapDevSourceSetPaths",
			"generateDevResources",
			"mergeDevResources",
			"processDevNavigationResources",
			"packageDevResources"
		)
	) {
		dependsOn(syncReleaseLicenseFallback)
	}
}

// Fix for KSP running before R class generation
// Ensure KSP waits for resource processing to complete
afterEvaluate {
	tasks.named("kspDebugKotlin") {
		dependsOn("processDebugResources")
	}
	tasks.named("kspReleaseKotlin") {
		dependsOn("processReleaseResources")
	}
	tasks.findByName("kspDevKotlin")?.dependsOn("processDevResources")
}




