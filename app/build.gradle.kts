import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
		versionCode = 368
		versionName = "2021.2α1"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

		resourceConfigurations.add("en")
		resourceConfigurations.add("cs-rCZ")
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	tasks.withType<KotlinCompile> {
		with(kotlinOptions) {
			jvmTarget = Android.jvmTarget
			freeCompilerArgs = listOf("-Xuse-experimental=kotlin.ExperimentalUnsignedTypes", "-Xjvm-default=enable")
		}
	}

	buildTypes {
		getByName("debug") {
			isTestCoverageEnabled = true
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

	packagingOptions {
		resources.pickFirsts.add("META-INF/atomicfu.kotlin_module")
	}

	dynamicFeatures.add(":statistics")
	dynamicFeatures.add(":game")
	dynamicFeatures.add(":map")
	dynamicFeatures.add(":external")
    namespace = "com.adsamcik.tracker"
}

tasks.withType<DokkaTask> {
	outputFormat = "html"
	outputDirectory = "$buildDir/javadoc"
	jdkVersion = 8
	skipEmptyPackages = true
	skipDeprecated = true

	//externalDocumentationLink {
		//url = URL("https://developer.android.com/reference/")
		//packageListUrl = URL("https://developer.android.com/reference/android/support/package-list")
	//}
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
	implementation("com.google.android.gms:play-services-oss-licenses:17.0.0")

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
