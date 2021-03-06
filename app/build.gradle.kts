import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL

plugins {
	id("com.android.application")
	id("org.jetbrains.dokka-android")
	id("com.google.android.gms.oss-licenses-plugin")
	id("com.google.firebase.crashlytics")
	Dependencies.corePlugins(this)
}


if (file("key.gradle").exists()) {
	apply("key.gradle")
}

android {
	compileSdkVersion(Android.compile)
	buildToolsVersion(Android.buildTools)
	defaultConfig {
		applicationId = "com.adsamcik.tracker"
		minSdkVersion(Android.min)
		targetSdkVersion(Android.target)
		versionCode = 355
		versionName = "2021.1α1"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		resConfigs("en", "cs-rCZ")
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
	}

	tasks.withType<KotlinCompile> {
		with(kotlinOptions) {
			jvmTarget = "1.8"
			freeCompilerArgs = listOf("-Xuse-experimental=kotlin.ExperimentalUnsignedTypes", "-Xjvm-default=enable")
			useIR = true
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

	lintOptions {
		isCheckReleaseBuilds = true
		isAbortOnError = false
	}

	sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

	packagingOptions {
		pickFirst("META-INF/atomicfu.kotlin_module")
	}

	dynamicFeatures = mutableSetOf(":statistics", ":game", ":map")
}

tasks.withType<DokkaTask> {
	outputFormat = "html"
	outputDirectory = "$buildDir/javadoc"
	jdkVersion = 8
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

	//debugImplementation("com.squareup.leakcanary:leakcanary-android:2.6")

	Dependencies.core(this)
	//1st party dependencies
	Dependencies.slider(this)
	Dependencies.draggable(this)

	Dependencies.introduction(this)

	//3rd party dependencies
	Dependencies.json(this)

	Dependencies.colorChooser(this)
	Dependencies.fileChooser(this)

	Dependencies.gpx(this)

	//Google dependencies
	implementation("androidx.cardview:cardview:1.0.0")

	//Preference
	Dependencies.preference(this)

	//Open-source licenses
	implementation("de.psdev.licensesdialog:licensesdialog:2.1.0")
	implementation("com.google.android.gms:play-services-oss-licenses:17.0.0")

	//PlayServices
	Dependencies.location(this)
	Dependencies.crashlytics(this)

	//Database
	Dependencies.database(this)


	Dependencies.test(this)
	//workaround  Multiple APKs packaging the same library can cause runtime errors.
	implementation(project(":smap"))
	Dependencies.map(this)
}
apply {
	plugin("com.google.gms.google-services")
}
