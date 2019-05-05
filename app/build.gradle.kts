import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL

plugins {
	id("com.android.application")
	id("org.jetbrains.dokka-android")
	id("com.google.gms.oss.licenses.plugin")
	id("io.fabric")
	Libraries.corePlugins(this)
}


if (file("key.gradle").exists())
	apply("key.gradle")

android {
	compileSdkVersion(Android.compile)
	buildToolsVersion("28.0.3")
	defaultConfig {
		applicationId = "com.adsamcik.signalcollector"
		minSdkVersion(Android.min)
		targetSdkVersion(Android.target)
		versionCode = 292
		versionName = "7.0α11 Offline"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		javaCompileOptions {
			annotationProcessorOptions {
				arguments = mapOf("room.schemaLocation" to "$projectDir/schemas")
			}
		}
	}

	tasks.withType<KotlinCompile> {
		with(kotlinOptions) {
			jvmTarget = "1.8"
			freeCompilerArgs = listOf("-Xuse-experimental=kotlin.ExperimentalUnsignedTypes")
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

	lintOptions.isCheckReleaseBuilds = false
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

//gradlew dependencyUpdates -Drevision=release
dependencies {
	implementation(project(":common"))

	Libraries.core(this)
	//1st party dependencies
	implementation("com.adsamcik.android-components:slider:0.8.0")
	implementation("com.adsamcik.android-components:recycler:0.4.0")
	implementation("com.adsamcik.android-components:draggable:0.14.1")
	implementation("com.adsamcik.android-forks:spotlight:2.0.7")

	//3rd party dependencies
	Libraries.moshi(this)

	implementation("com.jaredrummler:colorpicker:1.1.0")

	implementation("de.psdev.licensesdialog:licensesdialog:2.0.0")
	implementation("com.luckycatlabs:SunriseSunsetCalculator:1.2")
	implementation("com.appeaser.sublimepickerlibrary:sublimepickerlibrary:2.1.2")
	//Looks nice doesn"t work, check later
	//implementation "com.github.codekidX:storage-chooser:2.0.4.4"

	//GPX
	implementation("stax:stax-api:1.0.1")
	implementation("com.fasterxml:aalto-xml:1.1.1")
	implementation("io.jenetics:jpx:1.4.0")

	//Google dependencies
	implementation("com.google.android:flexbox:1.1.0")

	//AndroidX
	implementation("androidx.cardview:cardview:1.0.0")
	implementation("androidx.preference:preference:1.1.0-alpha04")

	//PlayServices
	implementation("com.google.android.gms:play-services-location:16.0.0")
	implementation("com.google.firebase:firebase-core:16.0.8")
	implementation("com.crashlytics.sdk.android:crashlytics:2.10.0")

	implementation("com.google.android.play:core:1.5.0")


	//Room
	Libraries.database(this)
	Libraries.work(this)


	//Test implementations
	androidTestImplementation("androidx.test:runner:1.2.0-alpha05")
	androidTestImplementation("androidx.test:rules:1.2.0-alpha05")
	androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
	androidTestImplementation("androidx.test.ext:junit:1.1.1-alpha05")
	androidTestImplementation("androidx.arch.core:core-testing:2.0.1")
	androidTestImplementation("com.jraska.livedata:testing-ktx:1.1.0")

	//Kotlin )
	androidTestImplementation("androidx.test.espresso:espresso-core:3.2.0-alpha04") {
		exclude("com.android.support", "support-annotations")
	}
	androidTestImplementation("androidx.test.espresso:espresso-contrib:3.2.0-alpha04") {
		exclude("com.android.support", "support-annotations")
		exclude("com.android.support", "support-v4")
		exclude("com.android.support", "design")
		exclude("com.android.support", "recyclerview-v7")
	}
}
apply {
	plugin("com.google.gms.google-services")
}