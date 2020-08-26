import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("com.android.dynamic-feature")
	Dependencies.corePlugins(this)
}


android {
	compileSdkVersion(Android.compile)
	buildToolsVersion(Android.buildTools)

	defaultConfig {
		minSdkVersion(Android.min)
		targetSdkVersion(Android.target)
		versionCode = 1
		versionName = "1.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}


	compileOptions {
		isCoreLibraryDesugaringEnabled = true
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
	}

	tasks.withType<KotlinCompile> {
		with(kotlinOptions) {
			jvmTarget = "1.8"
			freeCompilerArgs = listOf("-Xuse-experimental=kotlin.ExperimentalUnsignedTypes")
		}
	}

	buildTypes {
		create("release_nominify")
	}

	lintOptions {
		isCheckReleaseBuilds = true
		isAbortOnError = false
	}
}

dependencies {
	implementation(project(":smap"))
	Dependencies.core(this)
	Dependencies.draggable(this)
	Dependencies.map(this)
	Dependencies.location(this)
	Dependencies.preference(this)
	Dependencies.test(this)
	Dependencies.introduction(this)

	implementation(project(":app"))
	implementation(project(":sbase"))
	implementation(project(":activity"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
}
