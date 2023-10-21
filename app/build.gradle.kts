import org.jetbrains.dokka.gradle.DokkaTask
import java.net.URL

plugins {
	id("com.google.secrets_gradle_plugin") version "0.6.1"
	id("com.android.application")
	id("org.jetbrains.dokka-android")
	id("com.google.android.gms.oss-licenses-plugin")
	Dependencies.corePlugins(this)
}

apply(plugin = "com.google.gms.google-services")
apply(plugin = "com.google.firebase.crashlytics")

android {
	compileSdk = Android.compile
	buildToolsVersion = Android.buildTools
	defaultConfig {
		applicationId = "com.adsamcik.tracker"
		minSdk = Android.min
		targetSdk = Android.target
		versionCode = 375
		versionName = "2023.1-⍺6"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

		resourceConfigurations.add("en")
		resourceConfigurations.add("cs-rCZ")
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	kotlin {
		jvmToolchain(Android.javaVersion)
		compilerOptions {
			optIn.add("kotlin.ExperimentalUnsignedTypes")
		}
	}

	java {
		toolchain {
			setSourceCompatibility(Android.javaVersion)
			setTargetCompatibility(Android.javaVersion)
		}
	}


	buildTypes {
		getByName("debug") {
			enableAndroidTestCoverage = true
			enableUnitTestCoverage = true
		}

		create("release_nominify") {
			isMinifyEnabled = false
		}
		getByName("release") {
			isMinifyEnabled = true
			proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
		}
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
}

tasks.withType<DokkaTask> {
	outputFormat = "html"
	outputDirectory = "${layout.buildDirectory}/javadoc"
	jdkVersion = Android.javaVersion
	skipEmptyPackages = true
	skipDeprecated = true

	externalDocumentationLink {
		url = URL("https://developer.android.com/reference/")
		packageListUrl = URL("https://developer.android.com/reference/android/support/package-list")
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
	implementation("com.google.android.gms:play-services-oss-licenses:17.0.1")

	// PlayServices
	Dependencies.location(this)
	Dependencies.crashlytics(this)

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
