import org.gradle.api.GradleException
import java.util.Properties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.ksp)
	alias(libs.plugins.hilt)
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
	compileSdk = Android.COMPILE_VERSION
	buildToolsVersion = Android.BUILD_TOOLS_VERSION
	defaultConfig {
		applicationId = "com.adsamcik.tracker"
		minSdk = Android.MIN_VERSION
		targetSdk = Android.TARGET_VERSION
		versionCode = 400
		versionName = "10.0.0"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		resourceConfigurations.addAll(listOf("en", "cs-rCZ"))
	}

	testOptions {
		unitTests.isIncludeAndroidResources = true
	}

	compileOptions {
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
		isCoreLibraryDesugaringEnabled = true
	}

	kotlin {
		jvmToolchain(Android.JAVA_VERSION)
		compilerOptions {
			optIn.add("kotlin.ExperimentalUnsignedTypes")
		}
	}

	java {
		toolchain {
			setSourceCompatibility(Android.JAVA_VERSION)
			setTargetCompatibility(Android.JAVA_VERSION)
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
			buildConfigField("boolean", "COMPOSE_MAIN", "true")
		}

		create("release_nominify") {
			initWith(release)
			isMinifyEnabled = false
		}
	}

	buildFeatures {
		compose = true
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

	sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
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
	
	implementation(project(":sbase"))
	implementation(project(":logging-api"))
	implementation(project(":tracker"))
	implementation(project(":activity"))
	implementation(project(":points"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
	implementation(project(":logger"))
	implementation(project(":impexp"))
	implementation(project(":statistics"))
	implementation(project(":stats-data"))
	implementation(project(":map"))
	implementation(project(":game"))
	implementation(project(":dashboard"))

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

	// Hilt (Dependency Injection)
	implementation(libs.hilt.android)
	ksp(libs.hilt.compiler)
	ksp(libs.androidx.hilt.compiler)
	implementation(libs.hilt.navigation.compose)
	implementation(libs.hilt.work)

	// Glance App Widgets
	implementation(libs.androidx.glance.appwidget)
	implementation(libs.androidx.glance.material3)

	// App Startup
	implementation(libs.androidx.startup.runtime)

	// Compose
	implementation(platform(libs.compose.bom))
	androidTestImplementation(platform(libs.compose.bom))
	implementation(libs.compose.material3)

	// Glance (App Widgets)
	implementation(libs.glance.appwidget)
	implementation(libs.glance.material3)
	implementation(libs.compose.ui.tooling.preview)
	debugImplementation(libs.compose.ui.tooling)
	implementation(libs.activity.compose)
	implementation(libs.androidx.core.splashscreen)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.compose.animation)
	implementation(libs.compose.animation.graphics)
	// AndroidViewBinding is no longer used
	implementation(libs.navigation.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.compose.runtime)
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

	// Database
	implementation(libs.androidx.room.runtime)
	ksp(libs.androidx.room.compiler)
	implementation(libs.androidx.room.ktx)
	implementation(libs.androidx.room.paging)
	implementation(libs.sqlite.android)
	androidTestImplementation(libs.androidx.room.testing)

	// Unit test deps - JUnit 5 for modern testing
	testImplementation(platform(libs.junit5.bom))
	testImplementation(libs.junit5.jupiter)
	testImplementation(libs.junit5.jupiter.params)
	testRuntimeOnly(libs.junit5.jupiter.engine)
	testRuntimeOnly(libs.junit5.vintage.engine)
	testImplementation(libs.junit4)
	testImplementation(libs.kotlin.test)
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.androidx.work.testing)
	testImplementation(libs.robolectric)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.mockk)
	testImplementation(libs.turbine)
	testImplementation(libs.kotest.assertions.core)
	testImplementation(project(":stats-api"))
	implementation(project(":stats-api"))
	testImplementation(project(":stats-engine"))

	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.androidx.test.rules)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(libs.mockk.android)
	androidTestImplementation(project(":testing-common"))
	testImplementation(project(":testing-common"))
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
