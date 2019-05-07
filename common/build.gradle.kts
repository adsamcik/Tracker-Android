plugins {
	id("com.android.library")
	Libraries.corePlugins(this)
}

android {
	compileSdkVersion(Android.compile)

	defaultConfig {
		minSdkVersion(Android.min)
		targetSdkVersion(Android.target)
		versionCode = 1
		versionName = "1.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	with(compileOptions) {
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
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
		}
	}
}

dependencies {
	Libraries.core(this)
	Libraries.moshi(this)
	Libraries.database(this)
	Libraries.crashlytics(this)
	Libraries.location(this)

	implementation("com.luckycatlabs:SunriseSunsetCalculator:1.2")

	implementation(fileTree("libs").setIncludes(listOf("*.jar")))

	testImplementation("junit:junit:4.12")
	androidTestImplementation("androidx.test:runner:1.1.1")
	androidTestImplementation("androidx.test.espresso:espresso-core:3.1.1")
}