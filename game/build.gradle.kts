import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("com.android.dynamic-feature")
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

	tasks.withType<KotlinCompile> {
		with(kotlinOptions) {
			jvmTarget = "1.8"
		}
	}

	buildTypes {
		create("release_nominify")
	}

}

dependencies {
	Libraries.core(this)
	Libraries.draggable(this)
	Libraries.database(this)
	Libraries.preference(this)
	Libraries.test(this)

	implementation(project(":app"))
	implementation(project(":common"))
}