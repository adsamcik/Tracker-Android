import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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

		kapt {
			arguments {
				this.arg("room.schemaLocation", "$projectDir/schemas")
			}
		}
	}

	sourceSets {
		this.maybeCreate("androidTest").assets.srcDirs(files("$projectDir/schemas"))
	}

	with(compileOptions) {
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
	}

	tasks.withType<KotlinCompile> {
		with(kotlinOptions) {
			jvmTarget = "1.8"
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
		}
	}
}

dependencies {
	implementation(project(":common"))

	Libraries.core(this)
	Libraries.database(this)
	Libraries.crashlytics(this)
	Libraries.location(this)
	Libraries.test(this)
}