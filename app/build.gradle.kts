import org.jetbrains.dokka.gradle.DokkaTask
import java.net.URI

plugins {
	id("com.android.application")
	id("org.jetbrains.dokka-android")
	id("com.google.android.gms.oss-licenses-plugin")
	id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
	Dependencies.corePlugins(this)
	Dependencies.composePlugins(this)
}

apply(plugin = "com.google.gms.google-services")

android {
	compileSdk = Android.COMPILE_VERSION
	buildToolsVersion = Android.BUILD_TOOLS_VERSION
	defaultConfig {
		applicationId = "com.adsamcik.tracker"
		minSdk = Android.MIN_VERSION
		targetSdk = Android.TARGET_VERSION
		versionCode = 384
		versionName = "2024.3.0 α1"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

		resourceConfigurations.add("en")
		resourceConfigurations.add("cs-rCZ")
	}

	compileOptions {
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
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


	buildTypes {
		getByName("debug") {
			enableAndroidTestCoverage = true
			enableUnitTestCoverage = true
			applicationIdSuffix = ".debug"
		}

		create("release_nominify") {
			isMinifyEnabled = false
		}
		getByName("release") {
			isMinifyEnabled = true
			proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
		}
	}

	buildFeatures {
		compose = true
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}

	sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

	packaging {
		resources.pickFirsts.add("META-INF/atomicfu.kotlin_module")
	}

	dynamicFeatures.add(":statistics")
	dynamicFeatures.add(":game")
	dynamicFeatures.add(":map")
	namespace = "com.adsamcik.tracker"
	dependenciesInfo {
		includeInApk = true
		includeInBundle = true
	}
	buildFeatures {
		buildConfig = true
	}
}

tasks.withType<DokkaTask> {
	outputFormat = "html"
	outputDirectory = "${layout.buildDirectory}/javadoc"
	jdkVersion = Android.JAVA_VERSION
	skipEmptyPackages = true
	skipDeprecated = true

	externalDocumentationLink {
		url = URI("https://developer.android.com/reference/").toURL()
		packageListUrl = URI("https://developer.android.com/reference/android/support/package-list").toURL()
	}
}

dependencies {
	implementation(project(":sbase"))
	implementation(project(":tracker"))
	implementation(project(":activity"))
	implementation(project(":points"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
	implementation(project(":logger"))
	implementation(project(":impexp"))

	// debugImplementation("com.squareup.leakcanary:leakcanary-android:2.6")

	Dependencies.core(this)
	Dependencies.compose(this)
	// 1st party dependencies
	Dependencies.slider(this)
	Dependencies.draggable(this)

	Dependencies.introduction(this)

	// 3rd party dependencies
	Dependencies.colorChooser(this)
	Dependencies.inputDialog(this)

	Dependencies.json(this)

	// Google dependencies
	implementation("androidx.cardview:cardview:1.0.0")

	// Preference
	Dependencies.preference(this)

	// Open-source licenses
	implementation("de.psdev.licensesdialog:licensesdialog:2.2.0")
	implementation("com.google.android.gms:play-services-oss-licenses:17.1.0")

	// PlayServices
	Dependencies.location(this)

	// Database
	Dependencies.database(this)

	Dependencies.test(this)
	// workaround  Multiple APKs packaging the same library can cause runtime errors.
	implementation(project(":smap"))
	Dependencies.map(this)
}
apply {
	plugin("com.google.gms.google-services")
}
