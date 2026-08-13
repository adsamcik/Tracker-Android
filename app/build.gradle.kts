import org.gradle.api.GradleException
import java.util.Properties

plugins {
    id("tracker.android.application")
    id("tracker.android.compose")
    id("tracker.android.hilt")
    id("tracker.android.test")
    id("tracker.android.instrumented-test")
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
    }

    androidResources {
        localeFilters += "en"
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
            matchingFallbacks += listOf("release")
            isMinifyEnabled = false
            //noinspection NotShrinkingResources
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
        // Version selection is centralized in the stable-release-aware root dependencyUpdates
        // task. Android lint cannot express that policy and otherwise recommends major upgrades.
        disable += setOf("GradleDependency", "NewerVersionAvailable")
    }

    // dynamicFeatures removed; modules are now statically linked libraries
    namespace = "com.adsamcik.tracker"
    dependenciesInfo {
        includeInApk = true
        includeInBundle = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":core:common"))
    implementation(project(":core:base"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:network"))
    implementation(project(":tracker:engine"))
    implementation(project(":feature:tracker"))
    implementation(project(":sensor:activity-api"))
    implementation(project(":sensor:activity"))
    implementation(project(":feature:activity"))
    implementation(project(":domain:points"))
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
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
    // Core
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

    // Glance (App Widgets)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // App Startup
    implementation(libs.androidx.startup.runtime)

    // Room APIs used directly by application workers and debug database setup.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // Tracebox is Tracker's sole production crash and diagnostic recorder.
    implementation(libs.tracebox)
    implementation(libs.tracebox.native)
    implementation(libs.tracebox.ui.compose)

    // Compose
    androidTestImplementation(platform(libs.compose.bom))

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
    testImplementation(libs.turbine)
    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(project(":core:sqlite-runtime"))
    testImplementation(project(":core:testing"))
}
