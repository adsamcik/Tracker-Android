import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("com.android.dynamic-feature")
	Dependencies.corePlugins(this)
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

	tasks.withType<KotlinCompile> {
		with(kotlinOptions) {
			jvmTarget = "1.8"
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
	Dependencies.test(this)
	Dependencies.sectionedRecyclerAdapter(this)

	implementation("com.github.PhilJay:MPAndroidChart:3.1.0")
	implementation("com.goebl:simplify:1.0.0")

	implementation(fileTree("libs").include("*.jar"))
	implementation(project(":app"))
	implementation(project(":sbase"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
}
