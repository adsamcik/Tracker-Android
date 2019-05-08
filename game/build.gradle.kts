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

}

dependencies {
	Libraries.core(this)
	Libraries.work(this)
	Libraries.draggable(this)
	Libraries.database(this)
	Libraries.preference(this)

	implementation(fileTree("libs").include("*.jar"))
	implementation(project(":app"))
	implementation(project(":common"))
}